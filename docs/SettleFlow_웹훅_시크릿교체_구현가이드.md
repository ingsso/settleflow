# SettleFlow 웹훅 시크릿 교체(신·구 키 병행 검증) 구현 가이드

## 배경

현재 `WebhookSignatureVerifier`는 시크릿을 하나만 가진다.
시크릿을 교체하는 순간 PG와 가맹점 중 한쪽이 먼저 바뀌면, 그 사이에 들어오는 통보는 전부 401이 된다.
401은 재전송되므로 유실되지는 않지만, 전환이 끝날 때까지 결제 반영이 멈춘다.

실무에서는 교체 기간 동안 **신·구 키를 둘 다 허용**하고, 구 키 사용이 사라진 것을 확인한 뒤 구 키를 제거한다.
인증서 갱신과 같은 구조의 문제다: 새 것을 먼저 받아들일 준비를 하고, 상대가 넘어온 뒤 옛것을 내린다.

---

## 설계

### 키 구성

목록(`secrets: a,b,c`) 대신 **역할이 다른 두 개**로 둔다.

| 설정 | 의미 | 기본값 |
|---|---|---|
| `settleflow.webhook.secret` | 현재 키. 검증 + 서명(`sign()`)에 사용 | 없음 (기존과 동일, 없으면 기동 실패) |
| `settleflow.webhook.previous-secret` | 직전 키. **검증에만** 사용 | 빈 값 (= 교체 기간 아님) |

목록 대신 둘로 나누는 이유:
- 어느 키가 "지금 키"인지가 설정에서 바로 드러난다
- 기존 `secret` 설정·테스트·k6를 건드리지 않는다
- 교체는 실무상 한 번에 한 단계(구 → 신)라 키가 셋 이상 동시에 살아 있을 일이 없다

`previous-secret`은 기본값을 빈 값으로 둬도 된다. `secret`에 기본값을 두지 않은 이유(운영에서 누락돼도 "아무나 아는 키"로 뜨는 것)가 여기엔 해당하지 않는다. 빈 값은 "구 키 없음"이라는 뜻이고, 아무 키도 허용하지 않는다.

### 빈 구 키 처리

`SecretKeySpec`은 빈 바이트 배열을 받으면 `IllegalArgumentException`을 던진다.
빈 `previous-secret`을 그대로 키 목록에 넣으면 **모든 검증 실패 경로가 500**이 된다.
생성자에서 공백이면 목록에 넣지 않는다.

### 검증 순서

1. 타임스탬프 검사 (기존과 동일, 키와 무관하므로 먼저)
2. 현재 키로 대조 → 맞으면 통과
3. 구 키가 있으면 구 키로 대조 → 맞으면 통과
4. 둘 다 아니면 실패

`sign()`은 항상 현재 키만 쓴다.

### 구 키 사용량 기록

구 키를 언제 지워도 되는지 판단하려면 **구 키로 통과한 요청이 아직 있는지** 알아야 한다.
검증 결과를 어느 키로 통과했는지까지 반환하고, 컨트롤러에서 카운터를 올린다.

```
~~webhook_signature_verified_total{key="current"}~~
~~webhook_signature_verified_total{key="previous"}~~
settleflow_webhook_signature_verified_total{key="current"}     ← 구현 (SettlementMetrics 의 settleflow_ 접두어에 맞춤)
settleflow_webhook_signature_verified_total{key="previous"}
```

~~`previous`가 일정 기간 0이면 PG가 새 키로 완전히 넘어온 것이므로 구 키를 제거한다.~~

> **정정 (2026-10-21, 구현 중).** 카운터는 누적이라 0 으로 돌아오지 않는다. 판단은 값이 아니라 **증가량**이다:
> `increase(settleflow_webhook_signature_verified_total{key="previous"}[관찰기간]) == 0` 이면 구 키를 제거한다.
> 그리고 두 시계열을 **기동 시점에 미리 등록**해야 한다 — 첫 증가 때 만들면 구 키로 한 번도 안 온 동안 `previous`
> 시계열이 아예 없어서 "0 건"과 "수집 안 됨"이 갈리지 않고, 없는 걸 0 으로 읽고 구 키를 지우면 401 사고다.
> **여전히 참인 것**: 구 키 사용이 멈춘 걸 확인한 뒤 제거한다는 판단 구조.
프로젝트에 이미 Micrometer·Prometheus가 있으므로(`SettlementMetrics`) 같은 방식으로 붙인다.

---

## 구현 순서

### 1. 설정 추가 (`application.yaml`)

```yaml
settleflow:
  webhook:
    secret: ${WEBHOOK_SECRET}
    # 시크릿 교체 기간에만 직전 키를 넣는다. 비어 있으면 현재 키만 허용.
    previous-secret: ${WEBHOOK_SECRET_PREVIOUS:}
    tolerance-seconds: 300
```

### 2. 검증 결과 타입

```java
public enum VerifiedKey { CURRENT, PREVIOUS }
```

`verify()`의 반환을 `boolean` → `Optional<VerifiedKey>`로 바꾼다.
비어 있으면 실패, 값이 있으면 어느 키로 통과했는지.

### 3. `WebhookSignatureVerifier` 수정

```java
@Component
public class WebhookSignatureVerifier {

    private static final String ALGORITHM = "HmacSHA256";

    private final byte[] current;
    private final byte[] previous;   // 교체 기간이 아니면 null
    private final long toleranceSeconds;
    private final Clock clock;

    @Autowired
    public WebhookSignatureVerifier(
            @Value("${settleflow.webhook.secret}") String secret,
            @Value("${settleflow.webhook.previous-secret:}") String previousSecret,
            @Value("${settleflow.webhook.tolerance-seconds}") long toleranceSeconds) {
        this(secret, previousSecret, toleranceSeconds, Clock.systemUTC());
    }

    WebhookSignatureVerifier(String secret, String previousSecret, long toleranceSeconds, Clock clock) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("settleflow.webhook.secret 이 비어 있다 (WEBHOOK_SECRET)");
        }
        this.current = secret.getBytes(StandardCharsets.UTF_8);
        // 빈 값이면 SecretKeySpec 이 요청 시점에 예외 → 500. 아예 키로 취급하지 않는다.
        this.previous = (previousSecret == null || previousSecret.isBlank())
                ? null
                : previousSecret.getBytes(StandardCharsets.UTF_8);
        this.toleranceSeconds = toleranceSeconds;
        this.clock = clock;
    }

    public Optional<VerifiedKey> verify(String timestampHeader, String signatureHeader, String rawBody) {
        if (timestampHeader == null || signatureHeader == null) return Optional.empty();

        long ts;
        try { ts = Long.parseLong(timestampHeader); }
        catch (NumberFormatException e) { return Optional.empty(); }

        long now = clock.instant().getEpochSecond();
        if (Math.abs(now - ts) > toleranceSeconds) return Optional.empty();

        byte[] actual;
        try { actual = HexFormat.of().parseHex(signatureHeader); }
        catch (IllegalArgumentException e) { return Optional.empty(); }

        String payload = timestampHeader + "." + rawBody;

        if (MessageDigest.isEqual(hmac(current, payload), actual)) {
            return Optional.of(VerifiedKey.CURRENT);
        }
        if (previous != null && MessageDigest.isEqual(hmac(previous, payload), actual)) {
            return Optional.of(VerifiedKey.PREVIOUS);
        }
        return Optional.empty();
    }

    /** 항상 현재 키로 서명한다. */
    public String sign(String timestamp, String rawBody) {
        return HexFormat.of().formatHex(hmac(current, timestamp + "." + rawBody));
    }

    private byte[] hmac(byte[] key, String payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
```

### 4. 컨트롤러 수정

```java
Optional<VerifiedKey> verified = verifier.verify(timestamp, signature, rawBody);
if (verified.isEmpty()) {
    log.warn("웹훅 서명 검증 실패");
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
}
metrics.webhookVerified(verified.get());   // key=current|previous 카운터
```

이후 파싱 → SETNX → 전이는 그대로.

### 5. 기존 테스트 호출 수정

`WebhookSignatureTest`가 `new WebhookSignatureVerifier("not-the-secret", 300)`처럼 2인자 생성자를 쓴다.
생성자가 3인자로 바뀌므로 둘 중 하나:
- 호출부를 `new WebhookSignatureVerifier("not-the-secret", "", 300)`으로 수정
- 또는 테스트 편의용 2인자 생성자를 남겨두기 (`@Autowired`는 3인자 쪽에만)

`verify()` 반환 타입이 바뀌므로 `.isTrue()` 단언은 `.isPresent()` / `.contains(VerifiedKey.CURRENT)`로 바꾼다.

`IntegrationTestSupport`와 k6는 `secret`만 쓰므로 수정 불필요.

### 6. 새 테스트 `WebhookSecretRotationTest`

시각을 고정한 단위 테스트(패키지 생성자 + `Clock.fixed`)로 충분하다. Testcontainers 불필요.

| 케이스 | 설정 | 서명 키 | 기대 |
|---|---|---|---|
| 현재 키 | current=new, previous=old | new | `CURRENT` |
| 구 키 (교체 기간) | current=new, previous=old | old | `PREVIOUS` |
| 구 키 (교체 종료) | current=new, previous="" | old | 실패 |
| 목록에 없는 키 | current=new, previous=old | other | 실패 |
| ~~빈 previous로 생성~~ | ~~current=new, previous=" "~~ | ~~new~~ | ~~생성 성공, `CURRENT`~~ |
| 빈 previous는 키가 아니다 | current=new, previous=`""`·`" "`·null | **other(틀린 서명)** | 예외 없이 실패 |
| 공백 previous로는 통과 못 함 | current=new, previous=" " | " " | 실패 |
| sign은 현재 키 | current=new, previous=old | — | `sign()` 결과가 new로 계산한 값과 일치 |

> **정정 (2026-10-21, 구현 중).** 원안의 "빈 previous로 생성"은 **현재 키로 서명**해서 첫 비교에서 돌아온다 —
> 구 키 분기를 아예 안 밟으므로 빈 값 필터가 없어도 초록불이다(음성대조군 B 에서 실측 확인: 그 모양의 케이스는 green).
> 틀린 서명으로 바꾸고, 필터 없이 망가지는 방식이 다른 `""`·`" "` 를 둘 다 본다.

**음성 대조군**
- `previous` 대조 블록을 지우고 실행 → "구 키 (교체 기간)"만 red여야 한다
- ~~빈 값 필터를 지우고 "빈 previous로 생성" 케이스에 틀린 서명을 넣어 실행 → 401이 아니라 예외(500 경로)가 나는지 확인~~

> **정정 (2026-10-21, 실행 후).** `" "` 는 예외를 안 낸다. 예외는 길이 0 인 `""` 에서만 나고, `" "` 는 길이 1 인
> **정상 키**가 되어 공백 한 글자로 서명한 요청이 `PREVIOUS` 로 통과한다 — 500 보다 위험한 쪽이다.
> 그리고 운영 yaml 기본값 `${WEBHOOK_SECRET_PREVIOUS:}` 가 바로 `""` 이라, 필터가 없으면 **교체 기간이 아닌 평소**에
> 현재 키로 실패한 요청이 전부 500 이 된다.

**음성 대조군 결과 [실측 2026-10-21]** — `WebhookSecretRotationTest` 8건, 각각 걸고 백업본으로 원복(원복 후 8/8 green)

| 대조군 | 결과 | red |
|---|---|---|
| A. 구 키 대조 블록 제거 | 1 red / 7 green | 교체기간 구 키 → `Optional to contain PREVIOUS but was empty` |
| B. 빈 값 필터 제거 | 3 red / 5 green | 교체 종료(`""`) → `IllegalArgumentException: Empty key` · 빈 구 키(`""`) → 같은 예외 · 공백 구 키(`" "`) → `PREVIOUS` 로 **통과** |

A 는 위 기대와 일치. B 는 기대가 빗나갔다(위 정정).

---

## 운영 절차 (교체 순서)

연동 가이드 8장에 들어갈 내용. **순서가 핵심이다.**

| 단계 | 가맹점(우리) | PG | 상태 |
|---|---|---|---|
| 0 | `secret=old` | old로 서명 | 평소 |
| 1 | `secret=new`, `previous-secret=old` 배포 | old로 서명 | 우리가 둘 다 받을 준비 완료 |
| 2 | — | new로 전환 (서버별로 순차 전환돼도 무방) | 혼재 구간, 둘 다 통과 |
| 3 | ~~`previous` 카운터가 일정 기간 0인지 확인~~ `increase(...{key="previous"}[기간]) == 0` 확인 | new로 서명 | 전환 완료 확인 |
| 4 | `previous-secret` 제거 배포 | new로 서명 | 교체 종료 |

1단계를 PG 전환보다 **먼저** 해야 한다. 순서가 뒤집히면 PG가 new로 보낸 통보가 우리 쪽에서 전부 401이 된다.
4단계를 카운터 확인 없이 하면, 아직 old로 보내던 PG 서버의 통보가 401이 된다.

---

## 포트폴리오 반영

**8장 연동 가이드 — 요청 인증 항목에 추가**
- 시크릿 교체 시 유예 기간 동안 신·구 키를 모두 검증. 구 키 사용량이 0이 된 뒤 구 키 제거

**8장 또는 9장 — 교체 절차 한 줄 요약**
> 키 교체는 "받을 준비 → 상대 전환 → 사용량 0 확인 → 옛 키 제거" 순서. 순서가 뒤집히면 전환 구간의 통보가 전부 인증 실패.

인증서·라이선스 갱신 업무와 같은 구조라는 점을 면접에서 연결할 수 있다.
