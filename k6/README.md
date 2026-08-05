# k6 부하 시나리오 (W4D3 설계 / W4D4 실행)

> 계획서 Week 4: *"k6 시나리오 설계 — 웹훅 중복 폭주(동일 Idempotency-Key로 N회 동시 요청)"*

## 왜 시나리오가 둘인가

계획서 문장 하나로는 **두 가지를 동시에 못 잰다**. 부하의 모양이 서로 배타적이기 때문이다.

| 부하 형태 | Redis 키 | 앱이 죽었을 때 고아 키 |
|---|---|---|
| **같은** eventId × N | **1개** | 최대 1개 — 구멍 크기가 안 나온다 |
| **고유** eventId × N | N개 | 죽는 순간 in-flight 였던 만큼 |

같은 키로 폭주시키면 정합성은 보이지만 구멍 크기가 안 보이고, 고유 키로만 쏘면 계획서가
요구한 "중복 폭주"가 빠진다. **그래서 나눴다.**

| | ① `webhook-duplicate-burst.js` | ② `webhook-crash-orphan.js` |
|---|---|---|
| 부하 | 같은 eventId × N VU | 고유 eventId, 도중에 **앱 강제 종료** |
| 겨누는 것 | 정합성 — N번 와도 **장부는 1줄** | 구멍 크기 — 고아 키가 몇 건 |
| 근거 | 계획서 Week 4 표 | `WebhookController` javadoc 이 인정한 구멍 |
| 이미 있는 증명과의 관계 | `PaymentFlowTest` 케이스 3(20개)의 **확대** | **새 명제** |

## 헤드라인을 `journal` 로 잡은 이유

우리 웹훅은 **중복에도 200 을 준다**(`WebhookController` javadoc: non-2xx 를 주면 PG 가 몇 시간
재전송한다). 그래서 StagePass 의 *"1,000 VU → 409 997건"* 같은 **HTTP 기반 수치가 나올 수 없다** —
응답만 봐서는 방어가 일했는지 아예 안 보인다.

명제는 DB 에 있다. 셋 다 재지만 헤드라인은 `journal` 이다:

| 세는 것 | 증명하는 것 |
|---|---|
| `payment.version` | 전이가 1회 (`PaymentFlowTest` 가 이미 쓰는 축 = 확대 확인) |
| **`journal` 행 수** | **부수효과가 1회** — "중복 청구가 장부에 안 남았다" |
| `outbox` 행 수 | 이벤트 발행이 1회 |

## eventId 에 merchantPaymentId 를 넣는다

고아 키를 세려면 **키에서 결제로 가는 길**이 있어야 한다. 성공한 웹훅의 키도 24시간 남으므로
(설계상 TTL) 키 개수만으로는 고아를 못 센다 — **고아 = 키는 있는데 그 결제가 아직 UNKNOWN** 이다.

```
Redis  webhook:dedup:k6-orphan-<mpid>   →  mpid 를 잘라낸다
DB     payment(merchant_payment_id).status 가 PAID 가 아니면  →  고아
```

**이게 프로덕션 동작을 안 바꾼다.** `eventId` 는 앱에게 **불투명한 문자열**이고 Redis 키로만
쓰인다 — 아무도 파싱하지 않는다. 실제 PG 는 자기 규칙대로 만들지만 무엇이 들어있든 코드
경로가 같다. **관측을 위해 재는 대상을 바꾸지 않는 자리**라, W4D1 에서 회로를 데코레이터로
뺀 것과 같은 계열의 판단이다.

## 셋업 — 왜 10,222 원인가

웹훅이 **실제로 전이시킬 대상**이 있어야 부하에 뜻이 있다. `StubPgClient` 의 매직 금액
(`amount % 1000 == 222` → `PgTimeoutException`)을 써서 결제를 `UNKNOWN` 으로 만든다.
그 뒤 웹훅이 `UNKNOWN → PAID` 를 실제로 밟는다(`PaymentFlowTest` 케이스 2와 같은 경로).

```
[실측 2026-08-05] POST /orders {productId:3, quantity:1}
    → HTTP 201, orderStatus=CREATED, totalAmount=10222, payment.status=UNKNOWN, version=1
[실측 2026-08-05] POST /webhooks/pg (eventId=k6-probe-<mpid>)
    → HTTP 200, payment PAID, version 1→2, journal 1건, Redis 키 생성
```

**`version` 은 2 다** — `PaymentFlowTest` 케이스 3 의 `version=1` 은 `PENDING → PAID` 경로라
출발점이 다르다. 이래서 헤드라인이 `journal` 인 쪽이 낫다(경로에 안 흔들린다).

## ★ 설계 중에 드러난 것 — 차단기가 시나리오를 깨뜨린다

첫 배선 확인(앱을 **안 죽이고** 돌린 대조군)에서 **0건이 나와야 할 고아가 44건** 나왔다.
HTTP 는 전부 200, 실패 0건이었다. 원인이 둘이었고 **둘 다 이쪽 설계 오류**였다.

```
[실측] pg-approve OPEN = 1
[실측] payment id  7~15 (9건)  → PAID     ← 회로가 닫혀 있던 동안
[실측] payment id 16~59 (44건) → FAILED   ← 회로가 열린 뒤
[실측] "PG 차단기 개방" 로그 = 44건        ← FAILED 수와 정확히 일치
```

**① 모든 주문에 타임아웃 매직 금액을 썼다.** PG 호출이 100% 실패라 `pg-approve` 가 10건 만에
열리고(`minimum-number-of-calls: 10`, `failure-rate-threshold: 50`), 그 뒤로는 호출이 PG 에 닿지도
않고 바로 `FAILED` 가 된다 — **셋업 경로가 처음 10건에서만 성립했다.** W4D1 이 만든 동작이
그대로 일한 것이고, 단건 검증을 부하로 확대하면서 그 사이에 회로가 있다는 걸 안 넣었다.

→ **수리: 타임아웃을 30% 로 섞어 회로를 닫아둔다.** 회로를 끄지 않는 게 판단이다 —
그건 프로덕션 동작의 일부이고, 끄고 잰 수치는 *"우리 시스템이 이렇다"* 가 아니게 된다.
비율은 난수가 아니라 전역 이터레이션 번호로 고정한다(난수면 20건 창에서 우연히 임계를
넘길 확률이 약 5% 라 실행마다 회로가 열렸다 말았다 한다).

**② 고아 정의가 틀렸다.** `status != 'PAID'` 로 셌는데 **`FAILED` 는 정당한 종착지**다 —
회로가 열려 안 보냈거나 PG 가 거절한 결제이고, 거기 웹훅이 와도 `onApproved` 가 CONFLICT 로
거부하는 게 설계대로다(*"종착지를 안 떠난다"*). 유실이 아니다.

→ **수리: 고아 = `status = 'UNKNOWN'`.** 웹훅이 옮겨줬어야 하는데 안 옮겨진 것만.

### 대조군이 일했다

이 대조군은 **배선 확인용**이었다. 안 돌리고 D4 에서 곧장 앱을 죽였으면, 정상 44건이 섞인
수치를 "고아"로 보고했을 것이다. 수리 후 재실행:

```
[실측] 혼합 부하 100건(타임아웃 30 / 승인 70), 앱을 죽이지 않음
       키 100 / PAID 100 / FAILED 0 / 고아 0 / 결제 행 없음 0
```

**0건이 나와야 할 자리에서 0건이 나왔다** — 이제 D4 의 kill 실행에서 나오는 숫자는
kill 때문이라고 말할 수 있다.

> 곁가지로 잡힌 것: `management.health.circuitbreakers.enabled: true` 를 W4D2 에 켰는데
> `/actuator/health` 에 `circuitBreakers` 가 **안 나온다.** resilience4j 는 인스턴스별
> `register-health-indicator: true` 를 따로 요구한다. W4D2 §5·§9 와 같은 계열
> ("설정을 썼다 ≠ 동작한다")이고, 오늘은 회로 상태를 **행동으로**(승인 주문이 PAID 가 되는지)
> 확인해 우회했다. → 이월.

## 실행

```bash
# 0) 앱과 컨테이너가 떠 있어야 한다
docker compose -p settleflow start
./gradlew bootRun

# 1) 부하용 마스터 데이터 시딩 — 여러 번 돌려도 안전. 상품 id 두 개를 찍어준다
#    (10,222 = 타임아웃, 10,000 = 승인). 아래 스크립트 기본값과 다르면 env 로 넘길 것.
docker exec -i settleflow-postgres-1 psql -U myuser -d mydatabase < k6/seed.sql

# 2) 시나리오 ① 중복 폭주
bash k6/verify.sh baseline              # ← 먼저. journal 증가분을 재려면 기준선이 필요하다
k6 run k6/webhook-duplicate-burst.js
bash k6/verify.sh duplicate <출력에 찍힌 mpid>

# 3) 시나리오 ② 결함 주입
bash k6/verify.sh reset-orphan          # 이전 회차의 k6-orphan 키 정리
k6 run k6/webhook-crash-orphan.js       # 도중에 앱을 강제 종료할 것 (아래)
bash k6/verify.sh orphan
```

### ⚠️ 강제 종료여야 한다

```powershell
# 부하가 도는 중에 (약 30초 지점)
Stop-Process -Id <bootRun JVM PID> -Force
```

**graceful shutdown 이면 구멍이 안 열린다** — in-flight 요청이 끝날 때까지 기다려주면 ③
(`redis.delete`)이 정상적으로 돌기 때문이다. Spring Boot 기본값은 `server.shutdown=immediate`
라 SIGTERM 으로도 열리지만, 이 실험은 그 설정에 의존하지 않도록 **SIGKILL** 을 쓴다.

> 여기서 파생되는 질문 하나: **`server.shutdown=graceful` 을 켜면 이 구멍이 얼마나 좁아지는가.**
> D4 에서 같은 시나리오를 graceful 로 한 번 더 돌리면 대조군이 된다. (미실행 — D4 판단)

## 이 수치가 뜻하는 것 / 뜻하지 않는 것

- 고아 키 수는 **kill 순간의 in-flight 요청 수에 비례**한다. rate 를 올리면 커진다.
  그러므로 이 숫자는 *"이 시스템의 고유한 값"* 이 아니라 **"이만큼의 동시성에서 이만큼 샌다"** 이다.
- 시나리오 ①은 **완전 동시가 아니다.** `PaymentFlowTest` 는 `CyclicBarrier` 로 20개를 진짜
  동시에 발사하지만, k6 에는 배리어가 없어 "1,000개를 최대한 빨리"에 가깝다.
  **둘은 다른 성질의 검증**이다 — 테스트는 좁은 창의 경합을, k6 는 규모를 본다.
- 부하는 로컬 단일 머신이다. 클라이언트·서버·DB·Redis 가 CPU 를 나눠 쓰므로
  **처리량 수치는 서버 용량이 아니라 이 노트북의 값**이다.
