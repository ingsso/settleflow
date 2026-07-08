# SettleFlow — 이커머스 정산·결제 플랫폼 5주 계획

**목표**: 엔터/핀테크/이커머스 지원 시 도메인 거리를 좁히는 세 번째 포트폴리오 프로젝트
**재사용 전략**: StagePass(동시성·Saga)·쿠카티쳐스(상태머신·캐싱) 패턴을 결제 도메인에 이식 → 새로 배우는 부분 최소화
**스택**: Java, Spring Boot, PostgreSQL, Redis, Kafka, resilience4j, k6, Prometheus/Grafana

---

## Week 1 — 주문·결제 코어 + 웹훅 멱등성

| Day | 작업 | 산출물 |
|---|---|---|
| 1 | 도메인 모델링: Order, OrderItem, Payment, Product 엔티티 설계 + ERD | ERD, 엔티티 클래스 |
| 2 | 재고 차감 로직 — StagePass `SET NX PX` 패턴을 좌석→재고로 이식 | `InventoryLockService` |
| 3 | Mock PG 서버 구축 (또는 토스페이먼츠 샌드박스 연동) | Mock PG API |
| 4 | 웹훅 수신 엔드포인트 + `Idempotency-Key` 기반 Redis `SETNX` 중복 방어 | `WebhookController`, 통합테스트 |
| 5 | 주문→결제 전체 플로우 통합테스트 (Testcontainers) | 테스트 5건 이상, README 초안 |

**주말 체크포인트**: 정상 주문 1건이 끝까지(주문→재고차감→결제→웹훅수신) 흐르는지 로컬에서 실제로 확인

---

## Week 2 — 이중장부(Ledger) + 부분환불 상태머신

| Day | 작업 | 산출물 |
|---|---|---|
| 1 | LedgerEntry 엔티티 설계 (차변/대변, 계정과목) | 엔티티, 스키마 |
| 2 | 결제 완료 시 자동 분개(더블 엔트리) 생성 로직 | `LedgerService` |
| 3 | `sum(차변) == sum(대변)` 불변식 DB 제약 + 검증 테스트 | 제약조건, 단위테스트 |
| 4 | 환불 상태머신 구현 (`PAID → PARTIAL_REFUNDED → REFUNDED`, 쿠카티쳐스 ApprovalStatus 패턴 재사용) | `RefundStatus` enum, 가드 조건 |
| 5 | 부분환불 시 분개 역분개(reversing entry) 처리 | `RefundService`, 통합테스트 |

**주말 체크포인트**: 부분환불 3번 반복해도 장부 불변식이 깨지지 않는지 테스트로 증명

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
