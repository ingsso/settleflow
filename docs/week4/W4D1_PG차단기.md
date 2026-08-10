# Week 4 Day 1 — PG 차단기(resilience4j CircuitBreaker)

**날짜**: 2026-08-03
**산출물**: `CircuitBreakerSmokeTest`(1 — §2) + `ResilientPgClient`(§6) +
`PaymentService` 네 번째 catch·`RefundConfirmService` 네 번째 줄·`RefundRequestedConsumer` javadoc(§4) +
`application.yaml` `resilience4j.circuitbreaker.instances`(§8) + `PgCircuitBreakerTest`(4 — §9).
전체 **92 → 97건**(스모크 1 + 차단기 4).
커밋 `4e0761b`(스모크) · `230bed5`(VACUUM 수리 — §10) · `0093114`(차단기) · `33cb93c`(체크리스트).
부수: `1bf5bc9`(W3 머지·푸시) · `d6306b0`(W1·W2 회고 재료 — 10일간 untracked 였다).
**방식**: **Q1**(회로가 열렸을 때 무슨 상태냐)은 본인이 선택지 없이 먼저 답했고 그게 오늘의 축이 됐다 —
승인 경로에선 그대로 채택, 환불 경로에서 반대에 부딪혀 수정. **Q2**(회로 개수)·**Q3**(장애 주입)·
데코레이터 구조·EXPLAIN 수리 방향은 Claude 가 낸 선택지에서 고른 것.

**상태**: ✅ 계획서 W4D1 구현 완료 + W3 회고 §5-3(resilience4j × Boot 4 호환성 미검증) 닫힘(§2).
⚠️ **커밋 시점 스위트는 97건 중 1실패** — `SettlementIndexPlanTest`, 오늘 만진 파일과 무관한 별건(§10).

> **증거 원칙**: 아래 `[실측]` 은 전부 실제 실행 결과. 미검증·한계는 §12.
> §10 에는 **Claude 가 증거 없이 단정한 기록**이 그대로 남아 있다.

---

## 1. 오늘의 범위

**계획서 항목**: resilience4j CircuitBreaker — PG API 장애 격리 → CircuitBreaker 설정

**오늘이 기한인 이월 숙제**

| 숙제 | 출처 | 상태 |
|---|---|---|
| resilience4j × Boot 4 호환성 확인 | W3 회고 §5-3 | ✅ **닫힘 — §2** |
| `LedgerService.recordRefund` javadoc 정정 | W3D4 §7 → W3D5 §12 | ⬜ 미이행 |
| 테스트 케이스 코드 안 보고 설명 | W3D3 #2 → W3D5 | ⬜ **4세션 연속 미착수** |
| `UNKNOWN` 쓸이 배치 | W3D2 이월 | ⬜ 스코프 밖 — 계속 이월 (§4 에서 **줄었다**) |
| Consumer DLT·지수 백오프 | W3D2 이월 | ⬜ **가정이 아니라 실제 조건이 됐다** — §12 |
| `cleanDatabase` 를 TRUNCATE 로 | `230bed5` | ⬜ W4 안정화로 |

---

## 2. 선결 — 스모크를 먼저 박았다

`resilience4j-spring-boot3:2.2.0` 은 **이름대로 Boot 3 용 스타터**이고 이 프로젝트는 Boot 4.1.0 이다.
의존성은 W1 부터 클래스패스에 있었지만 **코드가 한 줄도 없어 지금까지 아무도 안 밟았다** — W3 회고
§5-3 이 "호환성 미확인"으로 남긴 자리다.

### ★ "빈이 뜨는가"만 보면 안 된다

자동설정이 `@ConditionalOnClass` 에서 미끄러지면 **에러가 아니라 무음**이다. 그 상태로
`@CircuitBreaker` 를 붙이면 애노테이션은 그냥 무시된 채 **테스트가 초록으로 통과한다** —
차단기가 없는데 있다고 믿게 된다. 그래서 셋을 한 번에 겨눴다:

| | 확인하는 것 | 미끄러지면 |
|---|---|---|
| ① 자동설정 | `CircuitBreakerRegistry` 빈이 뜬다 | `@Autowired` 가 **컨텍스트 로딩부터** 실패시킨다 |
| ② AOP | `@CircuitBreaker` 가 실제로 가로챈다 | 상태가 `CLOSED` 에 머물러 빨간불 |
| ③ 설정 바인딩 | 기본값(100건)이 아니라 준 값(5건)에 열린다 | 기본값으로 돌아 5건에 안 열린다 |

```
[실측] resilience4j 2.2.0 — 5회 실패 후 OPEN, 이후 호출은 CallNotPermittedException
       1건 0실패, 35.7s. `> Task :test` 가 실제로 실행됐음을 확인(UP-TO-DATE 아님)
```

**예외 타입이 바뀌는 게 증거다.** `IllegalStateException` → `CallNotPermittedException`.
같은 타입이 계속 나오면 그건 차단이 아니라 **그냥 실패**다 — 대상에 닿은 뒤 터진 것과
닿기 전에 거절된 것을 타입이 가른다.

> 마지막 줄의 *"UP-TO-DATE 아님을 확인"* 은 그날 아침에 데인 자리라 넣은 것이다(§10).

---

## 3. ★ 핵심 판단 — 개방은 타임아웃과 뜻이 다르다

W1D1 §3 이 `UNKNOWN` 을 만든 근거가 **여기선 반대로 작동한다.**

| | 무슨 일이 있었나 | 우리가 아는 것 |
|---|---|---|
| 타임아웃 | **보냈는데** 답을 못 들었다 | **정보의 부재** — PG 에 기록이 있을 수도 있다 |
| 회로 개방 | **아예 안 보냈다** | **확실한 앎** — PG 에 기록이 없다 |

타임아웃을 거절로 오인하면 이중청구가 열린다는 게 W1D1 의 논증이었다. 그런데 개방은
**우리가 안 보냈다는 걸 우리가 안다.** 불확실성이 없다.

---

## 4. ★ 그런데 그 앎을 어떻게 쓸지는 경로마다 다르다 — 오늘의 축

같은 사실("안 보냈다")이 승인과 환불에서 **정반대 처리**로 갈렸다.

### 승인 → `FAILED`

안 보냈으니 PG 에 기록이 없고 **돈이 안 움직였다**. `UNKNOWN` 으로 찍으면 있지도 않은
불확실성을 만들어 쓸이 배치에 짐만 얹는다.

**딸린 이득 하나.** W1D3 이 미결제 쓸이 배치에 요구한 *"복원 전에 PG 에 조회해서 정말 결제가
안 됐는지 확인"* 이 **이 경로만은 필요 없다** — 안 보낸 걸 우리가 알기 때문이다.
재고를 즉시 되돌려도 이중판매가 안 열리는 **유일한 실패 경로**다.

### 환불 → 예외를 내보내 재배달, 상태는 `REQUESTED` 그대로

**본인의 최초 답은 여기서도 `FAILED` 였고, 뒤집혔다.** 뒤집힌 근거가 오늘 설계 전체를 갈랐다.

`FAILED` 의 뜻은 *"PG 가 환불을 거절했다"* 이고 **예약 해제와 함께 종착지**다. 그런데 실제로는
PG 가 거절한 게 아니라 **우리가 안 물어본 것**이다. 고객에겐 이미 `201` 로 접수됐다고 답해뒀다.

> **승인은 실패해도 빚이 안 생기지만, 환불은 이미 진 빚이다.**
> 못 부른 게 빚을 없애지 않는다.

우리 회로 사정으로 환불을 조용히 종료시키면 빚이 사라진 것처럼 보인다. 금액은 되살아나니
*다시 요청하면* 되지만 — **아무도 자동으로 다시 요청하지 않는다.**

대신 예외를 소비자까지 올려보내 **오프셋을 안 밀고 재배달**시킨다. `RefundRequestedConsumer` 의
`[POISON]`·`[ORPHAN]` 과 갈리는 지점은 **재시도해서 달라지는가**다: 저 둘은 몇 번을 다시 해도 같은
결과라 넘기지만, **차단기 개방은 시한부라 기다리면 풀린다.**

### `RefundConfirmService` 결과표에 네 번째 줄이 붙었다

| | 장부 | 예약 | 상태 | 이벤트 |
|---|---|---|---|---|
| 승인 | 역분개 INSERT | 유지 | `COMPLETED` | `refund.completed` |
| 거절 | 건드리지 않음 | **해제** | `FAILED` | 없음 |
| 무응답 | 건드리지 않음 | **유지** | `UNKNOWN` | 없음 |
| **못 보냄**(W4D1) | 건드리지 않음 | **유지** | `REQUESTED` 그대로 | 없음 — **예외를 내보내 재배달** |

---

## 5. 판단 기준은 회로의 *상태*가 아니라 *예외*다

`CircuitBreaker.getState() == OPEN` 으로 분기하지 않고 `CallNotPermittedException` 을 잡는다.

**`HALF_OPEN` 이 이유다.** 거기서는 시험 호출이 **실제로 나가고**, 그게 타임아웃 나면 그건
진짜 정보의 부재라 `UNKNOWN` 이어야 한다. 상태로 분기하면 이 경우를 "안 보냈다"로 **틀리게
먹는다**. 상태 조회와 호출 사이에 상태가 바뀌는 경합도 따라온다.

`CallNotPermittedException` 은 **호출이 실제로 거절됐을 때만** 나온다. 그래서 이 예외가
"안 보냈다"의 유일하게 정확한 신호다.

---

## 6. ★ 회로를 갈아끼워지는 자리 *밖*에 뒀다

`@CircuitBreaker` 를 `StubPgClient` 에 직접 붙이면 **테스트가 그 빈을 대체하는 순간 회로도
같이 사라진다.** 그러면 갈 곳이 둘뿐이다:

| 선택 | 결과 |
|---|---|
| 테스트 구현체에 애노테이션을 복사 | 초록은 뜨지만 **프로덕션 회로가 아니라 테스트 회로를 검증**한 것 |
| 안 복사 | 회로가 없어 영영 빨간불 |

그래서 데코레이터(`ResilientPgClient implements PgClient`)를 한 겹 뒀다. 회로가 **그 겹에 산다.**
테스트는 `delegate`(= `stubPgClient`)만 갈아끼우고 **프로덕션 회로가 그대로 돈다.**

```java
@Primary @Component
public class ResilientPgClient implements PgClient {
    // @Qualifier 로 이름을 박아 받는다 — 타입으로 받으면 자기 자신도 후보가 되어 순환한다
    public ResilientPgClient(@Qualifier("stubPgClient") PgClient delegate) { ... }

    @Override @CircuitBreaker(name = "pg-approve")
    public PgResult approve(UUID merchantPaymentId, long amount) { ... }
}
```

### 덤 — 프록시 경계 문제가 사라진다

호출자(`PaymentService`·`RefundConfirmService`)가 **다른 빈**을 부르는 구조라 자기 호출이 아니고,
애노테이션이 조용히 무시될 자리가 없다. **W3D5 에서 `SettlementRunner` 를 별도 빈으로 뺀 것과
같은 이유**다 — 거기선 격리 수준이, 여기선 회로가 조용히 사라지는 함정이었다.
스모크(§2)가 *"증명 안 한 것"* 으로 남긴 항목이 설계에서 통째로 없어졌다.

### 서비스 쪽에 안 건 것도 판단이다

`PaymentService.pay` 에 걸면 회로가 PG 호출뿐 아니라 **그 메서드의 트랜잭션 작업까지** 감싼다.
두 서비스 모두 PG 호출을 tx 밖으로 빼내려고 tx 를 쪼개놨는데(W1D1 §2 함정1) 그 설계와 어긋난다.

---

## 7. 회로가 둘 — 무엇이 실패로 세어지나

### 왜 `pg-approve` / `pg-refund` 로 가르나

승인과 환불은 **실패의 뜻이 다르다**(§4). 지키려는 게 다르므로 격리 단위도 가른다 —
**승인 API 가 죽었다고 환불까지 막으면 갚아야 할 돈이 우리 회로 사정으로 밀린다.**
실제 PG 도 승인과 환불은 다른 엔드포인트라 따로 죽을 수 있다.

### 실패로 세는 건 **예외뿐**

PG **거절**(`PgResult.DECLINED` · `PgRefundResult.Declined`)은 예외가 아니라 **반환값**이라
회로를 안 흔든다. 이게 맞다 — **거절은 PG 가 멀쩡히 답한 것**이고 격리할 장애가 아니다.
거절이 회로를 열면, 부정거래가 몰린 날 우리가 스스로 결제를 막는다.

실질적으로 회로를 여는 건 `PgTimeoutException` 이다.

### 폴백을 안 뒀다

`CallNotPermittedException` 이 **호출자에게 그대로 나간다.** 여기서 "실패"를 만들어 돌려주면
승인·환불이 그걸 **PG 의 답**으로 오인한다 — §4 의 분기가 통째로 불가능해진다.
안 물어봤다는 사실 자체가 정보이고, 그걸 어떻게 읽을지는 **호출자가 정한다.**

---

## 8. 설정값과 그 근거

```yaml
resilience4j.circuitbreaker.instances.pg-approve:
  sliding-window-type: COUNT_BASED     # 트래픽 없는 시간대에 오래된 실패가 창에 남는 걸 피함
  sliding-window-size: 20
  minimum-number-of-calls: 10          # 2건 중 1건 실패로 50% 가 되면 안 된다
  failure-rate-threshold: 50
  wait-duration-in-open-state: 30s     # 짧으면 죽은 PG 를 계속 두드리고, 길면 회복을 늦게 안다
  permitted-number-of-calls-in-half-open-state: 3
  automatic-transition-from-open-to-half-open-enabled: false
```

**마지막 줄이 판단이다.** 자동 전이를 끄면 백그라운드 스케줄러 없이, 대기 시간이 지난 뒤
**들어온 다음 호출**이 `HALF_OPEN` 을 연다 — 트래픽이 없으면 열어볼 이유도 없다.

`pg-refund` 는 **같은 값으로 시작한다.** 다른 값을 줄 근거가 아직 실측에 없다 —
**나누는 것 자체가 오늘의 판단**이고, 값을 가르는 건 근거가 생기면 그때.

---

## 9. 실측 — `PgCircuitBreakerTest` 4케이스 (전체 92 → 97건)

대체하는 건 `stubPgClient`(delegate)뿐이고 `ResilientPgClient` 는 그대로 둔다(§6).
`@Primary` 라 주입되는 `PgClient` 가 **프로덕션 회로**다.

```
[실측] ① 타임아웃 10회 → 회로=OPEN, 11번째 호출은 CallNotPermittedException
                          verify(delegate, times(10)) — 11번째는 delegate 에 안 갔다
[실측] ② 회로 OPEN 중 결제 → payment.status=FAILED, PG 호출=0회
[실측] ③ 회로 OPEN 중 환불확정 → 예외 발생, pg_status=REQUESTED 예약=5,000 REFUND journal=0
[실측] ④ pg-approve=OPEN / pg-refund=CLOSED → 환불=COMPLETED, 결제=FAILED
                          verify(refund, times(1)) + verify(approve, never())
```

**`verify(never())` 가 "안 보냈다"의 직접 증거다.** 이게 없으면 §4 가 `FAILED` 로 찍을 근거가
통째로 없다 — 상태만 보면 "PG 가 거절해서 FAILED" 와 구분이 안 된다.

**④가 회로를 둘로 나눈 판단의 증거다**(§7). 하나였다면 승인 회로가 연 순간 환불도 막혔을 것이다.

②③④ 는 `transitionToOpenState()` 로 회로를 **강제** 개방한다. 유기적으로 여는 경로는 ①이 이미
증명했으므로, 나머지는 *"열렸을 때 각 경로가 무엇으로 해석하는가"* 에만 집중한다 —
매번 10건씩 실패를 쌓으면 재는 대상이 흐려진다.

> 회로는 컨텍스트에 붙어 있어 **테스트 간에 상태가 남는다**. 한 테스트가 열어둔 회로가 다음
> 테스트를 막으므로 `@BeforeEach` 에서 `reset()` 한다 — `cleanDatabase` 와 같은 이유다.

---

## 10. ⚠️ 별건 — 거짓 초록 두 겹, 그리고 절반만 든 수리

W4 진입 기준선으로 92건을 재실행하다 나온 일이다. **프로덕션 코드와 무관하다.**

### ① 거짓 초록 두 겹

| | 무슨 일 | 어떻게 드러났나 |
|---|---|---|
| 첫 겹 | `:test UP-TO-DATE` — **테스트가 아예 안 돌았는데** `BUILD SUCCESSFUL` | `cleanTest` 강제 재실행 |
| 둘째 겹 | 92건 중 1 red — `SettlementIndexPlanTest` 의 `doesNotContain("Sort")` | **클래스 단독은 green, 전체 스위트에서만 red** |

둘째 겹의 정체: `cleanDatabase` 가 `DELETE` 라 **죽은 튜플이 블록에 남고**, `seed()` 의 `ANALYZE` 가
그 부푼 블록 위에서 통계를 잡는다. 살아있는 40행이 80블록에 흩어진 것으로 보이니 `Index Scan` 의
랜덤 접근 비용이 올라가고 플래너가 `Bitmap` 으로 갈아탄다 → **인덱스 순서가 안 보존돼 `Sort` 가 붙는다.**

**이 테스트는 자기 데이터가 아니라 남이 남긴 잔해를 재고 있었다.** 그래서 클래스 단독 필터로는
수리 여부를 가릴 수 없다 — W3D5 음성대조군 D 와 같은 계열이다.

수리(`230bed5`): 삽입만 트랜잭션으로 묶고, 커밋 뒤 `VACUUM (ANALYZE) settlement` 를
`JdbcTemplate`(autocommit)으로 부른다. **`VACUUM` 은 트랜잭션 블록 안에서 못 돈다.**

```
[실측] 수리 후 1회 실행(전체 스위트, 92건 0실패)
       Index Scan using uq_settlement_date_seller (cost=0.29..71.98) (actual rows=40)
       Sort 없음, Bitmap 없음. cost 109.15 → 71.98, Execution 0.495ms → 0.033ms
```

**음성대조군은 규약대로 본인이 직접, 전체 스위트로 실행했다 — red 재현.**
W3D5 에서 클래스 단독 필터 탓에 무효가 됐던 함정을 피한 것이다.

### ⚠️⚠️ ② 그런데 그 수리는 절반만 들었다 — **Claude 오류로 남긴다**

저녁 전체 실행에서 **다시 red**. 잔해는 실제로 없어졌는데(`Heap Blocks` 80 → **40**) 플래너가
**여전히 `Bitmap` 을 골랐다.**

> **원인 진단이 불완전했고, 실행 1회 green 을 "고쳤다"로 단정해 커밋 메시지에까지 적은 게
> Claude 오류다.** 같은 대화에서 스스로 *"1회로는 증거가 약하다"* 고 적어놓고 몇 시간 뒤에 어겼다.
>
> 음성대조군이 증명한 것도 과대 해석했다 — 증명된 건 *"그 줄이 원인의 하나"* 지
> *"이제 안 흔들린다"* 가 아니었다.

남은 가설: **상관도**(`pg_stats.correlation`). `seed()` 가 셀러별로 몰아넣어 같은 날짜가 흩어지고,
`DELETE` 후 재삽입이 빈 슬롯을 재사용해 배치가 실행마다 달라진다. **미해결 이월.**

*(스위트 합계 135.0s → 95.2s 도 관측됐으나 **인과는 안 봤다** — 여기 적어두되 근거로 쓰지 않는다.)*

---

## 11. 재개 시 상태

- **회로 둘이 프로덕션에 살아 있다** — `ResilientPgClient` 가 `@Primary` 라 모든 `PgClient` 주입점이
  자동으로 회로를 탄다. 호출자 코드는 안 바뀌었다.
- **`CallNotPermittedException` 을 아는 곳은 둘뿐** — `PaymentService`(catch → `FAILED`)와
  `RefundConfirmService`(통과시킴). 새 PG 호출 경로를 만들면 **이 둘 중 어느 쪽 해석인지 정해야 한다.**
- **회로 상태를 볼 방법이 없다.** 로그와 테스트뿐이고 운영 중엔 아무도 못 본다 → **W4D2 소관**.
- 테스트에서 회로를 열려면 `circuitBreakerRegistry.circuitBreaker("pg-approve").transitionToOpenState()`,
  되돌리려면 `reset()`. `@BeforeEach` 에 이미 있다.

---

## 12. 정직한 한계 / 이월

### ★ 알고 남기는 구멍 — 백오프 없는 뜨거운 재배달

환불 경로(§4)가 예외를 내보내 재배달시키는데, **백오프가 없어 회로가 열려 있는 동안 재배달이
뜨겁게 돈다.** `RefundRequestedConsumer` javadoc 이 **W3D2 부터** "DLT·재시도 정책 없음"으로 적어둔
한계가, 오늘 **가정이 아니라 실제 조건이 됐다.**

`DefaultErrorHandler` + 지수 백오프는 **W4 중에 갚는다.** 문서에 명시하고 이월 유지 — **본인 판단.**

### 증명 안 한 것

| 항목 | 왜 |
|---|---|
| **메트릭 노출** | 회로 상태를 운영 중에 볼 수 없다 → **W4D2** |
| `HALF_OPEN` 전이 실측 | 대기 30s 라 테스트에서 안 밟았다. §5 의 논증은 **설계 근거이고 실측이 아니다** |
| `TimeLimiter`·`Retry`·`Bulkhead` | 안 씀 |
| 실제 PG | `StubPgClient` 다. 타임아웃도 우리가 던지는 예외지 진짜 네트워크 타임아웃이 아니다 |
| 동시성 하 회로 동작 | 단일 스레드로만 쟀다 |

### 무효화된 주석 정정(오늘 한 것)

- `RefundConfirmService.confirm` 의 *"예외를 던지지 않는다"* 가 **이제 거짓**이라 단서를 달았다.
- 같은 클래스 javadoc 의 세 결과 표에 **네 번째 줄**을 넣었다(§4).

### 이월 표

| 숙제 | 출처 | 기한 |
|---|---|---|
| `SettlementIndexPlanTest` red — 상관도 가설 | `230bed5` → 오늘 §10② | 미해결 |
| Consumer DLT + 지수 백오프 | W3D2 → **오늘 실제 조건이 됨** | W4 중 |
| `cleanDatabase` 를 TRUNCATE 로(92건 전부 재검증 필요) | `230bed5` | W4 안정화(D5) |
| `LedgerService.recordRefund` javadoc 정정 | W3D4 §7 | — |
| 테스트 케이스 코드 안 보고 설명 | W3D3 #2 | **4세션 연속 미착수** |
| `UNKNOWN` 쓸이 배치 | W3D2 | §4 로 **범위가 줄었다**(개방 경로는 PG 조회 불필요) |

### 참고 문서

- W1D1 §2 함정1(tx 쪼개기) · §3(`UNKNOWN` 의 근거 — §3 에서 반대로 작동)
- W1D3(쓸이 배치의 "복원 전 PG 조회" 요구 — §4 에서 한 경로가 면제됨)
- W3D2 §3(세 결과, 세 처리) · §7 이월(DLT·재시도 없음 — §12 에서 실제 조건이 됨)
- W3D5 §6(`SettlementRunner` 프록시 경계 — §6 과 같은 함정) · §11 음성대조군 D(§10 과 같은 계열)
- W3 회고 §4(초록불의 뜻) · §5-3(resilience4j 호환성 미검증 — §2 에서 닫힘)
