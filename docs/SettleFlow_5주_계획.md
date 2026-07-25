# SettleFlow — 이커머스 정산·결제 플랫폼 5주 계획

**목표**: 엔터/핀테크/이커머스 지원 시 도메인 거리를 좁히는 세 번째 포트폴리오 프로젝트
**재사용 전략**: StagePass(동시성·Saga)·쿠카티쳐스(상태머신·캐싱) 패턴을 결제 도메인에 이식 → 새로 배우는 부분 최소화
**스택**: Java, Spring Boot, PostgreSQL, Redis, Kafka, resilience4j, k6, Prometheus/Grafana

---

## Week 1 — 주문·결제 코어 + 웹훅 멱등성

| Day | 작업 | 산출물 |
|---|---|---|
| 1 | 도메인 모델링: Order, OrderItem, Payment, Product 엔티티 설계 + ERD | ERD, 엔티티 클래스 |
| 2 | ~~재고 차감 — StagePass `SET NX PX` 이식~~ → **Day 1 미검증 3건 실측 검증**으로 대체 | `RefundRaceTest`, `SchemaBehaviorTest` |
| 3 | 스키마(Flyway) + 엔티티 클래스 + 재고 차감(원자적 조건부 UPDATE) | `V1__init_schema.sql`, 엔티티 6개 |
| 4 | Mock PG 서버 + 웹훅 수신 엔드포인트 + `Idempotency-Key` 기반 Redis `SETNX` 중복 방어 | Mock PG API, `WebhookController` |
| 5 | **전체 플로우 배선**: `OrderService`(주문+재고차감을 한 진입점) → 결제 연결 + 주문 REST 엔드포인트 + `Order` 가드. (통합테스트는 W1D4 `PaymentFlowTest`로 사실상 완료) | `OrderService`, 주문 엔드포인트, README 초안 |

**주말 체크포인트**: 정상 주문 1건이 끝까지(주문→재고차감→결제→웹훅수신) 흐르는지 로컬에서 실제로 확인

> **일정 정밀화 (2026-07-15, W1D4 종료 시점)**: 결제 코어·웹훅 멱등·동시성은 W1D4에 완료. 남은 건 **배선과 문서**라 가볍다. 조각들이 아직 따로 논다 — `InventoryService`(재고차감)와 `PaymentService.pay`(결제)가 한 진입점으로 안 묶였고, 로컬 실행용 주문 엔드포인트가 없다(웹훅 엔드포인트뿐).
> - **목(7/16) = 배선**: 위 조각을 관통시켜 "돌아가는 흐름"까지만. **README·리팩터는 넣지 않는다**(끼우면 흐름 완성이 밀린다).
> - **금(7/17) = 점검**: `docker-compose up`으로 앱 띄워 실제 1건 관통(= 위 체크포인트를 금요일로 당김) + 첫 e2e에서 나오는 잔버그(트랜잭션 경계·LAZY 로딩·`application.yaml` Redis/Postgres 연결) 수정 + README.
> - **주말 = 버퍼.** 새 개념(Kafka·Ledger)은 Week 2 것이라 Day 5는 막힐 데가 적다.

### ⚠️ 계획 수정 — 재고에 Redis 락을 쓰지 않기로 함 (2026-07-14, W1D3)

원래 Day 2 항목은 *"StagePass `SET NX PX` 패턴을 좌석→재고로 이식"*이었으나, **이식이 성립하지 않는다**고 판단해 폐기했다. `InventoryLockService`는 만들지 않는다.

**근거 — 두 문제는 시간의 모양이 다르다.**

| | 좌석 선점 (StagePass) | 재고 차감 (SettleFlow) |
|---|---|---|
| 점유 시간 | **지속** — 결제창 입력 중 수 분간 물고 있어야 함 | **순간** — 요청 시점에 깎고 끝 |
| DB 트랜잭션으로 되나 | ❌ 수 분간 커넥션·행 락 점유 → 풀 고갈 | ✅ `UPDATE ... WHERE stock >= :qty` 한 줄 |
| 고객 이탈 시 | 아무도 안 풀어줌 → **자동 만료 필요**(`PX`) | 애초에 잡은 게 없음 |

즉 Redis `SET NX PX`가 풀던 건 **동시성(정합성)이 아니라 "시간"**이었다. 정합성은 원자적 조건부 UPDATE로 DB가 이미 보장한다 — **W1D2 `RefundRaceTest`에서 같은 모양의 SQL로 실측 완료**(성공 1건 / 최종 8000, 가드 제거 시 11000).

재고에 Redis를 얹으면 아무것도 더 지키지 못하면서 **재고의 진실이 Redis와 DB 둘로 갈라진다** — 이 프로젝트의 주제(정합성·대사)와 정면 충돌.

**채택: 주문 생성 시 원자적 차감 + 미결제 주문 쓸이 배치로 복원.**
쓸이 배치는 "느린 TTL"이 아니라 **"조회할 줄 아는 TTL"**이다 — 복원 전에 PG에 조회해서 *"정말 결제 안 된 게 맞나"*를 확인해야 한다. 타이머만 믿고 복원하면 승인된 결제의 재고를 되돌려 **이중판매**가 난다(W1D1 §3: `UNKNOWN`은 실패가 아니라 정보의 부재).

**"Redis 락이 정말 필요한가"는 Week 4 k6로 측정해서 답한다.** 인기 상품 1건에 동시 부하를 걸어 DB 행 락 경합이 실제 병목인지 재보고, 병목일 때만 재고려. 추측이 아니라 수치로.

---

## Week 2 — 이중장부(Ledger) + 부분환불 상태머신

| Day | 작업 | 산출물 |
|---|---|---|
| 1 | LedgerEntry 엔티티 설계 (차변/대변, 계정과목) | 엔티티, 스키마 |
| 2 | 결제 완료 시 자동 분개(더블 엔트리) 생성 로직 | `LedgerService` |
| 3 | `sum(차변) == sum(대변)` 불변식 DB 제약 + 검증 테스트 | 제약조건, 단위테스트 |
| 4 | 환불 상태머신 구현 (`PAID → PARTIAL_REFUNDED → REFUNDED`, 쿠카티쳐스 ApprovalStatus 패턴 재사용) | `RefundStatus` enum, 가드 조건 |
| 5 | 부분환불 시 분개 역분개(reversing entry) 처리 | `RefundService`, 통합테스트 |

**주말 체크포인트**: 부분환불 3번 반복해도 장부 불변식이 깨지지 않는지 테스트로 증명 — **완료**(W2D5 §4-6: 부분환불 3회 → journal 4개, 전체합·사건단위 균형 모두 유지)

> **일정 정밀화 (2026-07-23, W2D5 종료 시점)**: Week 2 를 목요일에 마쳐 금요일이 남는다. W1 과 같이 **금 = 점검일**로 쓰고 **W3D1 은 토(7/25) 시작**.
> - **금(7/24) = 점검**. 기능 추가가 아니라 *추측을 실측으로 없애고·이월을 갚고·문서를 현재에 맞추는* 날(W1D5 §9 와 같은 성격).
>   - **A. W2 기준 로컬 관통** — W1D5 §9 의 관통은 주문→결제까지다. 그 뒤로 장부·환불·역분개가 얹혔는데 `bootRun` 관통 기록이 없다(47건 테스트로만 검증). 주문 → 장부 3줄 → 환불 → 역분개 2줄까지 실제 앱에서 재현.
>     - **선결**: 환불 진입점이 없다. 컨트롤러는 `OrderController`·`WebhookController` 뿐이고 `RefundService.requestRefund` 를 부르는 프로덕션 코드가 **하나도 없다**(테스트 전용) — Week 2 산출물이 앱 밖에서 도달 불가능한 상태다. → **`POST /payments/{id}/refunds` 를 만든다.** W1D5 가 *"로컬 실행용 주문 엔드포인트가 없다"* 를 문제로 보고 `POST /orders` 를 만든 것과 같은 자리. 딸려오는 설계 판단: `RefundResult` 4가지를 **HTTP 상태로 어떻게 매핑하나**(W2D4 에서 *"친절한 사전 안내는 UX 계층의 일"* 이라 미뤄둔 것을 실제로 처리하는 자리).
>   - **B. `compose.yaml` 에 Kafka 추가** — 의존성(`spring-boot-starter-kafka`·`testcontainers-kafka`)은 이미 있고 **로컬 브로커만 없다**. 미리 빼두지 않으면 W3D1 설계 도중에 인프라를 만지게 된다.
>   - **C. 주간 회고 2주치** — Week 1·2 둘 다 공백(사용법 3번, 2주 연속 미이행). Week 2 내용이 머릿속에 있는 지금이 제일 정확하다.
>   - **D.(선택) W2D4 동시성 음성대조군 실패 모드 기록** — 이월 1건이자 **red-light 규약(체크리스트 §작업 규약)의 첫 적용 기회**. 제거 지점이 이미 정해져 있다(`addRefundedAmount` 의 `AND refunded_amount + :amount <= amount`).
>   - **E.(선택) `RefundConcurrencyTest` 에 journal 수 단언 추가** — W2D5 §7 이월. ACCEPTED 4건이면 REFUND journal 도 4개여야 하는데 안 보고 있다.
>   - A~C 반나절, D·E 까지 하루.
> - **토(7/25) = W3D1** 이벤트 스키마 설계. B 를 금요일에 끝내두면 이 날은 설계에만 쓸 수 있다.

---

## Week 3 — 정산 배치 + 대사(Reconciliation)

| Day | 작업 | 산출물 |
|---|---|---|
| 1 | Kafka 이벤트 설계: `payment.completed`/`refund.requested`/`refund.completed` (StagePass Saga 재사용) | 이벤트 스키마, Producer |
| 2 | Consumer + 보상 트랜잭션 (환불 실패 시 롤백) | Consumer, 보상 로직 |
| 3 | 야간 정산 배치 스케줄러 — 셀러별 일 매출 집계 | `SettlementBatchScheduler` |
| 4 | PG사 정산 리포트 Mock(CSV) 생성 + 대사 로직 (내부 장부 vs 외부 리포트 비교) | `ReconciliationService` |
| 5 | 불일치 건 자동 플래그 + 배치 중 신규 주문 유입 동시성 처리(스냅샷 기준 조회) | 동시성 테스트 |

**주말 체크포인트**: 의도적으로 불일치 데이터 3건 심어두고, 대사 배치가 정확히 3건을 잡아내는지 확인

---

## Week 4 — 관측성 + 부하테스트

| Day | 작업 | 산출물 |
|---|---|---|
| 1 | resilience4j CircuitBreaker — PG API 장애 격리 (StagePass 설정 재사용) | CircuitBreaker 설정 |
| 2 | Prometheus + Grafana — 정산 배치 소요시간, 대사 불일치 건수 대시보드 | Grafana 대시보드 |
| 3 | k6 시나리오 설계 — 웹훅 중복 폭주(동일 Idempotency-Key로 N회 동시 요청) | k6 스크립트 |
| 4 | k6 실행 + 결과 분석 (예: "1,000건 중복 웹훅 → 실제 처리 1건" 형태로 수치화) | 부하테스트 결과 리포트 |
| 5 | 전체 회귀 테스트 + 버그 픽스 | 안정화된 코드베이스 |

**주말 체크포인트**: StagePass의 "1,000 VU → 409 997건"과 대응되는 결과 수치 확보

---

## Week 5 — 정기결제(구독) 재시도 로직 (Dunning)

| Day | 작업 | 산출물 |
|---|---|---|
| 1 | Subscription 엔티티 + 상태머신(`ACTIVE → PAST_DUE → CANCELED`) | 엔티티, 상태머신 |
| 2 | 결제 실패 시 재시도 스케줄 설계 (1일→3일→7일 후 3회) | `DunningScheduler` |
| 3 | 재시도-멱등성 연동 (Week 1의 Idempotency-Key 구조를 재시도에도 적용, 중복 청구 방지) | 통합테스트 |
| 4 | CircuitBreaker 연동 — PG 장애(OPEN 상태) 시 재시도 스킵, 다음 주기로 연기 | 연동 로직 |
| 5 | k6로 동시 재시도 레이스 컨디션 시나리오 부하테스트 + 최종 README/포트폴리오 문서 정리 | 최종 산출물 |

**주말 체크포인트**: 동시에 여러 재시도 트리거가 발생해도 중복 청구 0건인지 수치로 증명

---

## 최종 산출물 체크리스트

- [ ] 코드 리포지토리 (README, 아키텍처 다이어그램 포함)
- [ ] k6 부하테스트 결과 2종 (웹훅 중복 방어, 재시도 레이스 컨디션)
- [ ] Grafana 대시보드 스크린샷
- [ ] STAR 형식 문제-해결 정리 4건 (웹훅 멱등성 / 이중장부 / 정산 대사 / 정기결제 재시도)
- [ ] 기존 detail.md·competency PDF 포맷에 맞춘 3번째 프로젝트 섹션 초안

## 진행 중 주의사항 (지금까지의 원칙 유지)

- 모든 수치·주장은 **실제 실행 결과로만** 기록 (추정치·설계 의도를 "결과"로 포장하지 않기)
- AI 도구(Claude Code/Antigravity 등) 활용 시 **커밋 트레일러·세션 기록**을 처음부터 남겨두기 — 나중에 역분석하려고 하면 이번처럼 근거 찾기가 어려움
- 매주 "이번 주에 실제로 검증한 것 vs 아직 못한 것"을 구분해서 기록해두면, 5주 후 서류 작성이 훨씬 수월함
