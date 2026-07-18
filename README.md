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
- [ ] Week 2 — 이중장부(Ledger) + 부분환불 상태머신
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

## 문서

설계 근거·실측 로그는 [`docs/`](docs/)에 주별 폴더로 기록. 주 무관 인덱스(5주 계획·체크리스트·기술결정)는 `docs/` 루트, daily 로그는 `docs/weekN/`:
- [`week1/W1D1_결제_도메인_설계.md`](docs/week1/W1D1_결제_도메인_설계.md) — 전이표, Order↔Payment 1:N
- [`week1/W1D3_스키마_확정.md`](docs/week1/W1D3_스키마_확정.md) — 스키마·엔티티, Redis 락 폐기 근거
- [`week1/W1D4_웹훅_멱등.md`](docs/week1/W1D4_웹훅_멱등.md) — SETNX/backbone 두 겹
- [`week1/W1D5_배선.md`](docs/week1/W1D5_배선.md) — 전체 배선, 트랜잭션 경계
