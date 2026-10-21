# SettleFlow 웹훅 서명 검증 구현 가이드

## 배경

현재 `POST /webhooks/pg`는 바디 형식만 맞으면 발신자와 무관하게 처리한다.
서명(HMAC), 발신 IP 제한, 시크릿 헤더 등 발신자 검증 장치가 없다.
제휴사 API를 운영하는 관점에서 "요청이 진짜 그 제휴사에서 왔는가"를 확인하는 단계를 추가한다.

핵심: **서명 검증을 Redis SETNX보다 먼저 둔다.**

---

## 설계

### 서명 방식

- 서명 대상: `timestamp + "." + 원본 바디 문자열`
- 알고리즘: HMAC-SHA256, 공유 시크릿 사용
- 헤더
  - `X-Timestamp`: 서명 시각 (epoch 초)
  - `X-Signature`: 서명값 (hex)
- 타임스탬프를 서명 대상에 포함해야 공격자가 시각만 바꿔 옛날 요청을 재사용하지 못한다.
- 허용 오차(5분) 안의 재전송은 기존 `eventId` 멱등 처리가 막는다.

### 순서가 중요한 이유

현재 `receive()`의 첫 줄은 SETNX다. 검증을 그 뒤에 두면:

1. 위조 요청이 진짜 `eventId`를 담아 먼저 도착
2. SETNX가 멱등키를 선점
3. 나중에 온 진짜 통보가 "중복"으로 판정되어 무시됨

→ 위조 요청이 결제 반영을 **막는** 공격이 된다.

따라서 순서는 **서명 검증 → SETNX → 상태 전이**.

### 원본 바이트로 검증

`@RequestBody PgWebhookRequest`로 받으면 이미 파싱된 객체라 원본 바디를 잃는다.
공백·필드 순서만 달라도 서명이 맞지 않는다.
바디를 `String`으로 받아 먼저 검증하고, 통과한 뒤에 파싱한다.

---

## 구현 순서

### 1. 설정 추가 (`application.yaml`)

```yaml
settleflow:
  webhook:
    secret: ${WEBHOOK_SECRET}
    tolerance-seconds: 300
```

테스트 설정에는 고정 테스트 시크릿을 둔다.

### 2. 검증기 클래스 `payment/api/WebhookSignatureVerifier`

```java
@Component
public class WebhookSignatureVerifier {

    private final byte[] secret;
    private final long toleranceSeconds;
    private final Clock clock;

    // 생성자에서 @Value로 secret, tolerance 주입
    // Clock은 테스트에서 시각을 고정할 수 있도록 주입 가능하게

    public boolean verify(String timestampHeader, String signatureHeader, String rawBody) {
        if (timestampHeader == null || signatureHeader == null) return false;

        long ts;
        try { ts = Long.parseLong(timestampHeader); }
        catch (NumberFormatException e) { return false; }

        long now = clock.instant().getEpochSecond();
        if (Math.abs(now - ts) > toleranceSeconds) return false;

        byte[] expected = hmac(timestampHeader + "." + rawBody);
        byte[] actual;
        try { actual = HexFormat.of().parseHex(signatureHeader); }
        catch (IllegalArgumentException e) { return false; }

        return MessageDigest.isEqual(expected, actual); // 상수 시간 비교
    }

    public String sign(String timestamp, String rawBody) { // 테스트·k6 대조용
        return HexFormat.of().formatHex(hmac(timestamp + "." + rawBody));
    }

    private byte[] hmac(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
```

`equals` 대신 `MessageDigest.isEqual`을 쓰는 이유: 비교가 일찍 끝나는 시간 차로 서명을 한 글자씩 추측하는 타이밍 공격을 막기 위해.

### 3. 컨트롤러 수정 `WebhookController`

```java
@PostMapping
public ResponseEntity<Void> receive(
        @RequestHeader(value = "X-Timestamp", required = false) String timestamp,
        @RequestHeader(value = "X-Signature", required = false) String signature,
        @RequestBody String rawBody) {

    if (!verifier.verify(timestamp, signature, rawBody)) {
        log.warn("웹훅 서명 검증 실패");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    PgWebhookRequest req = parseAndValidate(rawBody); // 실패 시 400
    String key = "webhook:dedup:" + req.eventId();
    // 이하 기존 SETNX → dispatch 그대로
}
```

- `@Valid`가 빠지므로 `parseAndValidate`에서 프로젝트의 JSON 매퍼로 파싱한 뒤 `Validator`로 직접 검증한다.
- Spring Boot 4 / Jackson 3 환경이므로 기존에 주입받는 매퍼 빈을 그대로 사용한다.
- 401 응답은 SETNX 전에 반환되므로 멱등키가 남지 않는다.

### 4. 기존 테스트·k6 수정

웹훅을 직접 쏘는 곳이 서명 없이 보내고 있어 전부 401이 난다.

**테스트**
- `IntegrationTestSupport`에 `postSignedWebhook(body)` 헬퍼 추가
- 아래 테스트의 웹훅 호출을 헬퍼로 교체
  - `PaymentFlowTest`
  - `SubscriptionWebhookTest`
  - `SubscriptionIdempotencyTest`
  - `DunningBlindSpotTest`

**k6 스크립트**
- `webhook-gate-rate.js`
- `webhook-crash-orphan.js`
- `webhook-duplicate-burst.js`

`k6/crypto`의 `hmac('sha256', secret, payload, 'hex')`로 `X-Timestamp`, `X-Signature` 헤더를 붙인다.

### 5. 새 테스트 `WebhookSignatureTest`

| 케이스 | 기대 결과 |
|---|---|
| 정상 서명 | 200, 결제 상태 전이 |
| 서명 틀림 | 401, 상태 변화 없음 |
| 헤더 누락 | 401 |
| 바디 변조 (amount만 변경) | 401 |
| 5분 지난 타임스탬프 | 401 |
| **위조 요청 후 같은 eventId로 진짜 요청** | 위조 401, 진짜 200 + 정상 반영 |

마지막 케이스가 핵심이다.

**음성 대조군**
검증을 SETNX 뒤로 옮겨서 같은 테스트를 돌린다.
진짜 요청이 "중복"으로 무시되는 현상이 재현되어야 한다.
이 결과가 순서 결정의 근거가 된다.

---

## 포트폴리오 반영

**8장 연동 가이드 — 요청 인증 항목**
- 헤더 `X-Signature`: `timestamp.body`를 공유 시크릿으로 HMAC-SHA256 서명한 값
- 헤더 `X-Timestamp`: 서명 시각. 5분 이상 차이 나면 거부
- 서명 불일치 시 401, 재전송하지 말 것

**9장 검증 — 추가 문구**
> 서명 검증을 멱등 처리보다 앞에 둔 이유: 위조 요청이 멱등키를 선점해 진짜 통보를 막는 것을 방지. 순서를 바꾼 대조 실험에서 진짜 통보 미반영 재현.
