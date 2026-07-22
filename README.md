# SettleFlow

이커머스 결제·정산 플랫폼. **주문 → 재고 차감 → 결제 → 웹훅 수신**의 한 흐름을, 실패·중복·경합이 실제로 일어나는 지점마다 정합성을 어떻게 지키는지에 초점을 맞춘 프로젝트다.

주제 한 줄: **"돈과 재고는 두 번 세면 안 된다."** 웹훅은 중복으로 오고, 결제는 타임아웃 나고, 배치는 승인된 주문의 재고를 되돌리려 한다 — 이 세 자리에서 이중청구·이중판매가 나지 않도록 막는 게 이 프로젝트다.

> ⚠️ 진행 중(Week 1 완료 시점). 아래 "구현 현황" 참조.

---

## 스택

Java 21 · Spring Boot 4.1 · PostgreSQL · Redis · Flyway · JPA/Hibernate
(예정: Kafka, resilience4j, k6, Prometheus/Grafana — Week 2~4)

---

## 아키텍처 — 한 주문이 흐르는 길

```
POST /orders
   │
   ▼
OrderService.place()
   │  tx1: 주문 생성 + 재고 원자적 차감 + 커밋      ← 여기서 끊는다
   │       (UPDATE product SET stock=stock-? WHERE stock>=?)
   ├──────── 트랜잭션 경계 ────────
   │
   ▼
PaymentService.pay()
   │  tx1': PENDING 결제 INSERT + 커밋            ← PG 부르기 전에 남긴다
   │  ─────  PG 동기 호출(트랜잭션 밖) ─────
   │  tx2 : 결과 반영 (승인→PAID / 거절→FAILED / 타임아웃→UNKNOWN)
   ▼
201 Created  { orderId, orderStatus: PAID, totalAmount }

        ── 나중에, 별도 경로 ──
POST /webhooks/pg   (PG 재전송, 중복 가능)
   │  SETNX(eventId) 실패 → 200 OK (문전박대)
   │  성공 → 전이 dispatch(낙관적 락) → 200 OK
```

### 핵심 설계 결정

| 문제 | 흔한 답 | 이 프로젝트의 선택 | 근거 |
|---|---|---|---|
| 재고 동시 차감 | Redis 분산 락 | **DB 원자적 조건부 UPDATE** | 재고 점유는 "순간"이라 락이 필요 없다. Redis를 얹으면 재고의 진실이 DB/Redis로 갈라져 대사와 충돌 |
| 웹훅 중복 | 멱등키(SETNX) 하나 | **SETNX(fast-path) + 상태가드·@Version(backbone) 두 겹** | SETNX를 빠져나온 중복(동기+웹훅 겹침, 다른 eventId 재승인)은 낙관적 락이 잡는다 |
| 결제 타임아웃 | FAILED 처리 | **UNKNOWN(정보의 부재)** | 타임아웃은 "실패"가 아니다. FAILED로 찍으면 실제 승인된 건에 이중청구 |
| 미결제 재고 복원 | 타이머 TTL | **PG 조회 후 복원(쓸이 배치)** | 타이머만 믿고 복원하면 늦게 승인된 결제의 재고를 되돌려 이중판매 |
| 트랜잭션 경계 | 전체 @Transactional | **PG 호출을 tx 밖으로** | tx 안에서 PG를 부르면 호출 실패 시 PENDING 행이 롤백돼, 나중 웹훅이 매칭할 행을 잃는다 |

각 결정의 상세 근거는 [`docs/`](docs/) 참조 (설계 → 선택지·트레이드오프 → 실측 순으로 기록).

---

## 실측 결과

> **원칙: 모든 수치는 실제 실행 결과.** 추정치·설계 의도를 결과로 적지 않는다.

### 로컬 e2e 관통 (2026-07-18 직접 실행)

`./gradlew bootRun`으로 앱을 띄우고 실제 HTTP 주문 1건을 관통시켜 확인:

```
POST /orders {"items":[{"productId":1,"quantity":2}]}
 → 201 Created {"orderStatus":"PAID","totalAmount":20000}

DB 대조:
 orders   : status=PAID  total_amount=20000
 payment  : status=PAID  amount=20000
 product  : stock 5 → 3        (원자적 차감 확인)
 order_item: unit_price=10000 quantity=2  (가격 스냅샷 확인)
```

`spring-boot-docker-compose`(developmentOnly)가 `bootRun` 시 compose를 자동 기동하고, 랜덤 호스트 포트(`5432→28830`)를 앱에 자동 주입 — `application.yaml`에 연결 설정 없이 부팅 성공(13.4초).

### 이전 세션 실측 (테스트로 검증, `docs/` 기록)

| 시나리오 | 결과 | 출처 |
|---|---|---|
| 재고 3, 동시 10명 주문 | 성공 3건 / 초과판매 0 | W1D3 |
| 같은 eventId 웹훅 20회 | 실제 처리 1건 | W1D4 |
| 다른 eventId 웹훅 20회 | 호출 20 / @Version=1 (backbone이 잡음) | W1D4 |
| 멱등 가드 제거(음성대조군) | TRANSITIONED 20 (빨간불 확인) | W1D4 |
| 환불 레이스(조건부 UPDATE) | 최종 잔액 정확 / 가드 제거 시 오염 | W1D2 |

전체 테스트 스위트: **19건 통과** (failures=0, errors=0, skipped=0 — 2026-07-18 `./gradlew test --rerun-tasks` 재실행 확인).

---

## 로컬 실행

**요구사항**: JDK 21, Docker (Desktop 실행 중)

```bash
./gradlew bootRun
```

`bootRun`이 `compose.yaml`(postgres + redis)을 자동 기동하고 Flyway가 스키마를 만든다. 별도로 `docker compose up`을 칠 필요 없음.

**주문을 넣으려면 상품이 먼저 있어야 한다** (seed 데이터 없음):

```bash
docker exec -e PGPASSWORD=secret settleflow-postgres-1 psql -U myuser -d mydatabase -c \
  "INSERT INTO seller (name) VALUES ('셀러');
   INSERT INTO product (seller_id, name, price, stock) VALUES (1, '상품', 10000, 5);"

curl -X POST http://localhost:8080/orders \
  -H 'Content-Type: application/json' \
  -d '{"items":[{"productId":1,"quantity":2}]}'
```

> 결제 결과는 **총액**으로 결정된다(Mock PG). `총액 % 1000 == 111`→거절, `== 222`→타임아웃, 그 외→승인.
> 위 예시는 20000 → 승인(PAID).

**테스트**:
```bash
./gradlew test    # Testcontainers가 일회용 postgres/redis를 띄운다(Docker 필요)
```

---

## 구현 현황

- [x] **Week 1 — 주문·결제 코어 + 웹훅 멱등성**
  - 스키마(Flyway 6테이블), 엔티티, 재고 원자적 차감
  - Mock PG + 웹훅 수신 + SETNX 멱등 + 낙관적 락 backbone
  - `OrderService` 배선 + `POST /orders` + 로컬 e2e 관통 확인
- [ ] **Week 2 — 이중장부(Ledger) + 부분환불** *(D1~D4 완료, D5 진행 예정)*
  - `journal`+`ledger_entry` 모델(append-only) + 셀러별 자동 분개
  - `Σ차변==Σ대변` 을 앱 가드 + **지연 제약 트리거**로 이중 방어
  - 환불 요청 가드 + `refunded_amount` 파생 라벨(저장하지 않음)
  - 남은 것: 부분환불 **역분개**(reversing entry)
- [ ] Week 3 — 정산 배치 + 대사(Reconciliation)
- [ ] Week 4 — 관측성 + 부하테스트(k6)
- [ ] Week 5 — 정기결제 재시도(Dunning)

---

## STAR — 웹훅 멱등성

**S(상황)**: PG 웹훅은 네트워크 재전송으로 **같은 사건이 여러 번** 도착한다. 결제 승인 웹훅이 20번 오면, 순진하게 처리하면 주문이 20번 PAID로 전이되고 후속 부수효과(정산·장부)가 20번 실행돼 이중청구가 난다.

**T(과제)**: 몇 번 도착하든 **정확히 한 번만** 처리하되, "이미 처리했다"를 에러가 아니라 정상으로 응답해 PG 재전송을 멈춰야 한다.

**A(행동)**: 방어를 **두 겹**으로 뒀다.
1. **fast-path** — Redis `SETNX(eventId)`를 전이보다 **먼저**. 두 번째 재전송은 PENDING을 읽어보기도 전에 200 OK로 문전박대(lost update 원천 차단).
2. **backbone** — SETNX를 빠져나온 중복(동기 승인+웹훅 겹침, 다른 eventId로 온 재승인)은 상태 가드 + JPA `@Version` 낙관적 락이 막는다. 진 쪽은 재조회 → 이미 종착지 → `IGNORED`.

성공 후 크래시로 200을 못 돌려줘도 재전송이 SETNX 실패로 멈추고(일은 커밋됨), 커밋 **전** 크래시로 키만 남으면 TTL 만료 + 쓸이 배치가 회수한다.

**R(결과)**: 같은 eventId 웹훅 **20회 → 실제 처리 1회**. 멱등 가드를 제거한 음성대조군에서는 `TRANSITIONED 20`이 관측돼(빨간불), 가드가 실제로 막고 있음을 반증으로 확인. *(W1D4 실측)*

---

## STAR — 이중장부 불변식

**S(상황)**: 셀러 여럿이 입점한 이커머스에서 20,000원 결제 1건은 장부에서 **현금 20,000 차변 / 셀러 미지급금 19,000 + 수수료 1,000 대변**으로 쪼개진다. 이 장부가 정산의 진실원본이라, 한 줄이라도 어긋나면 셀러에게 줄 돈이 틀리고 Week 3 대사가 비교할 **기준 자체**가 무너진다.

**T(과제)**: `Σ차변 == Σ대변` 불변식을 **어떤 경로로도** 못 깨게 한다. 서비스 코드뿐 아니라 생 SQL·배치·미래의 다른 서비스가 `ledger_entry` 에 직접 INSERT 해도 지켜져야 한다.

**A(행동)**: 가드를 **두 층**에 뒀는데, 둘은 중복이 아니라 서로 다른 걸 잡는다.

1. **모델** — `journal`(사건) + `ledger_entry`(줄). 원인(payment/refund)이 `journal_id` 를 FK 로 가리키고, 장부는 **append-only**(지우개가 아니라 빨간 펜).
2. **앱 가드** — 서비스가 요율·반올림으로 쪼개고 `Journal.forPayment` 가 조립하며 `assertBalanced()`. **수수료만 반올림하고 미지급금은 빼서 구해** 두 줄의 합이 정의상 subtotal — 균형이 *구조적으로* 성립한다.
3. **DB 가드** — `CHECK` 로는 불가능하다. CHECK 는 자기 행 하나만 보는데 이 불변식은 **여러 줄을 합쳐야** 나온다. 즉시 트리거도 안 된다 — 3줄이 각각 INSERT 되므로 첫 줄(현금 20,000만)에서 대변 0 으로 **오판**한다. → `CONSTRAINT TRIGGER ... DEFERRABLE INITIALLY DEFERRED` 로 검사를 **커밋 시점**까지 미룬다.
4. **배선** — 분개는 승인 전이가 실제로 일어난 `TRANSITIONED` **1회에만** 실행(Week 1 의 멱등 backbone 재사용) → 중복 웹훅이 와도 이중계상이 없다.

**R(결과)** — 전부 실행 결과:

- 단일 셀러 결제 → `journal=1, entries=3`, 현금 **20,000** = 미지급금 **19,000** + 수수료 **1,000**, 차변==대변
- 다중 셀러(N=2) → `entries=5`(2N+1), 균형 유지
- **중복 승인 20회 → journal 1개** (이중계상 방어)
- 불균형 journal → INSERT 는 통과하고 **커밋에서 거부**. 실패 지점이 INSERT 가 아니라 커밋이라는 게 지연 트리거가 실제로 지연됐다는 증거
- **음성대조군 3건**: `assertBalanced` 에 불균형 입력 → 예외(영구 테스트로 고정) / 트리거 `DROP` → 불균형이 **그냥 커밋됨**(빨간불) / 멱등 가드 제거 → journal 이 **~21개**로 폭증
- **예상 못한 소득 — 두 가드가 서로 다른 걸 잡는다는 산 증거**: DB 제약을 넣자 기존 테스트의 *"올바른 조합"* fixture 가 깨졌다. 그 fixture 가 사실 **불균형**(20,000 vs 19,000)이었고 — **앱 가드는 통과시켰는데 DB 가드가 잡았다.** 이중방어가 관념이 아님이 사고로 증명된 자리.

*(W2D1~D3 실측. 현재 스위트 40건 0실패. 부분환불 역분개는 W2D5 — 이 불변식이 환불 방향으로도 성립하는지는 그때 추가된다.)*

---

## 문서

설계 근거·실측 로그는 [`docs/`](docs/)에 주별 폴더로 기록. 주 무관 인덱스(5주 계획·체크리스트·기술결정)는 `docs/` 루트, daily 로그는 `docs/weekN/`:
- [`week1/W1D1_결제_도메인_설계.md`](docs/week1/W1D1_결제_도메인_설계.md) — 전이표, Order↔Payment 1:N
- [`week1/W1D3_스키마_확정.md`](docs/week1/W1D3_스키마_확정.md) — 스키마·엔티티, Redis 락 폐기 근거
- [`week1/W1D4_웹훅_멱등.md`](docs/week1/W1D4_웹훅_멱등.md) — SETNX/backbone 두 겹
- [`week1/W1D5_배선.md`](docs/week1/W1D5_배선.md) — 전체 배선, 트랜잭션 경계
- [`week2/W2D1_이중장부_설계.md`](docs/week2/W2D1_이중장부_설계.md) — journal+ledger_entry 모델, append-only
- [`week2/W2D2_자동분개_셀러별장부.md`](docs/week2/W2D2_자동분개_셀러별장부.md) — 셀러별 분개, 반올림으로 균형을 구조화
- [`week2/W2D3_불변식_DB제약.md`](docs/week2/W2D3_불변식_DB제약.md) — CHECK 로 안 되는 이유, 지연 제약 트리거
- [`week2/W2D4_환불_가드_파생라벨.md`](docs/week2/W2D4_환불_가드_파생라벨.md) — 파생 vs 저장, 가드 분업, affected rows 를 판정으로
