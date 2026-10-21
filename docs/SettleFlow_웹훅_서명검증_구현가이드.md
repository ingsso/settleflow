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
~~바디를 `String`으로 받아 먼저 검증하고, 통과한 뒤에 파싱한다.~~
바디를 `byte[]`로 받아 먼저 검증하고, 통과한 뒤에 같은 바이트를 파싱한다.

> **정정 (2026-10-21, W6D2 §9).** `String` 은 Spring 이 Content-Type 문자셋으로 푼 결과라, 서명 계산에서 UTF-8 로 다시
> 인코딩하면 선언 문자셋이 실제 바이트와 다를 때 원본이 복원되지 않는다. `charset=ISO-8859-1` + 한글 필드로 정상 서명한
> 요청이 `String` 에선 401, `byte[]` 에선 200 (실측). **여전히 참인 것**: 파싱된 객체가 아니라 원본으로 검증한다는 원칙.

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

**음성 대조군 결과 [실측 2026-10-21]** — 검증 블록을 `setIfAbsent` 아래로 옮겨 `WebhookSignatureTest` 실행 후 원복.

```
[실측] 위조→진짜(같은 eventId): 위조 HTTP 401, 위조 뒤 멱등키 있음, 진짜 HTTP 200, 최종 status=PENDING
```

| 케이스 | 결과 | 실패 메시지 |
|---|---|---|
| 정상 서명 | 🟢 | |
| 서명 틀림 | 🔴 | 멱등키 `expected false but was true` |
| 헤더 누락 | 🔴 | 두 번째 요청 `expected 401 but was 200` |
| 바디 변조 | 🟢 | |
| 5분 지난 타임스탬프 | 🟢 | |
| 위조 → 같은 eventId 진짜 | 🔴 | `expected PAID but was PENDING` |
| 외부 계산 고정값 대조 | 🟢 | |

**3 red / 4 green.** 원복 후 같은 클래스 7/7 green, `[실측]` 은 `멱등키 없음 · PAID` 로 돌아옴.

예측 대조:
- 사용자 예측 *"2건 red, 위조 뒤 멱등키 있음, 진짜 200인데 PENDING"* — `[실측]` 세 값은 전부 적중, **개수가 2 → 3**.
- 검토 메모의 기대 *"위조→진짜만 red, 나머지 6건 green"* — 빗나감. 테스트 javadoc 에 Claude 가 적어둔
  *"다른 케이스는 앞이든 뒤든 초록불"* 도 같은 오류였다(javadoc 정정함).

빨간불 셋은 원인이 하나다 — **401 이 멱등키를 남긴다.** 그걸 키로 직접 보면 "서명 틀림", 다음 요청의
응답으로 보면 "헤더 누락", 결제 상태로 보면 "위조→진짜". 헤더 누락 케이스가 덤으로 보여준 것: 순서가
뒤집히면 **서명 없는 두 번째 요청이 검증까지 가지도 않고 200** 을 받는다.

---

## 포트폴리오 반영

**8장 연동 가이드 — 요청 인증 항목**
- 헤더 `X-Signature`: `timestamp.body`를 공유 시크릿으로 HMAC-SHA256 서명한 값
- 헤더 `X-Timestamp`: 서명 시각. 5분 이상 차이 나면 거부
- ~~서명 불일치 시 401, 재전송하지 말 것~~
- 응답 규약

| 상황 | 응답 | 제휴사 조치 |
|---|---|---|
| 서명 불일치 | 401 | 재전송 (가맹점 측 시크릿 확인 후 정상 처리됨) |
| 바디 형식 오류 | 400 | 재전송해도 결과 동일, 연동 담당자 확인 필요 |

> **정정 (2026-10-21, 구현·머지 뒤 검토에서).** "재전송하지 말 것"은 틀렸다.
> 401 의 실제 원인은 대부분 위조가 아니라 **우리 쪽 시크릿 설정 실수**다. 그때 PG 가 재전송을 멈추면
> 진짜 결제 통보가 영영 사라진다. 재전송을 계속하면 시크릿을 고친 뒤 들어오는 재전송이 정상 처리되고,
> 위조 요청은 몇 번을 재전송해도 401 이라 손해가 없다.
> 이게 성립하는 근거는 구현에 이미 있다 — 401·400 은 SETNX **전에** 반환되므로 멱등키가 안 남고,
> 그래서 재전송이 "중복"으로 막히지 않는다(§3).
> 400 도 같은 맥락이다. 실제 PG 는 2xx 가 아니면 보통 재전송하므로 "재전송 금지"를 요구할 수 없다 —
> 결과가 같다는 사실을 명시하는 쪽이 정확하다.
>
> **여전히 참인 것**: 401·400 의 응답 코드와 판정 순서. 바뀐 건 제휴사에게 요구하는 조치뿐이다.

**9장 검증 — 추가 문구**
> 서명 검증을 멱등 처리보다 앞에 둔 이유: 위조 요청이 멱등키를 선점해 진짜 통보를 막는 것을 방지. 순서를 바꾼 대조 실험에서 진짜 통보 미반영 재현.

~~⚠️ **아직 쓸 수 없는 문장이다 (2026-10-21 기준).** 머지(`6afb80c`)의 실측은 *정상 순서에서 막힌다*까지만
증명한다. 뒷 문장 "대조 실험에서 재현"은 음성대조군을 돌린 뒤에야 사실이 된다.~~

✅ **같은 날 음성대조군을 돌려 사실이 됐다** — 아래 §음성 대조군 결과.
