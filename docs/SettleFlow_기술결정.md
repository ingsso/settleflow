# SettleFlow — 기술 스택 결정사항

새 채팅 시작할 때 이 파일과 `SettleFlow_5주_계획.md`, `SettleFlow_체크리스트.md`를 같이 올리세요.

---

## 프로젝트 초기 설정 (Spring Initializr)

| 항목 | 결정 |
|---|---|
| Project | Gradle - Groovy (기존 StagePass/쿠카티쳐스와 동일) |
| Language | Java |
| Spring Boot | **4.1.0** |
| Java | **21 (LTS)** |

### 버전 선택 이유
- StagePass는 Spring Boot 3.3.0 사용했으나, 3.x 라인은 2026.06.30부로 전체 EOL (보안 패치 중단)
- 현재 지원되는 건 4.0 / 4.1.0뿐 → 신규 포트폴리오 프로젝트는 **4.1.0**으로 시작
- Spring Framework 7 기반이라 Jackson 3, Hibernate 7, JUnit 4 완전 제거 등 3.x 대비 breaking change 있음 → Week 1에 디버깅 여유시간 확보할 것

---

## Spring Initializr 의존성 목록

| 카테고리 | 의존성 | 용도 |
|---|---|---|
| Web | Spring Web | REST API |
| SQL | Spring Data JPA | Order/Payment/Ledger 엔티티 |
| SQL | PostgreSQL Driver | DB |
| SQL | Flyway Migration | 스키마 버전 관리, LedgerEntry DB 제약조건 관리 |
| NoSQL | Spring Data Redis (Access+Driver) | Idempotency-Key SETNX, 헤더 매핑 쿨다운 패턴 재사용 |
| Messaging | Spring for Apache Kafka | Saga 이벤트 (payment.completed 등) |
| Ops | Spring Boot Actuator | 헬스체크, 메트릭 |
| Ops | Prometheus | Grafana 연동 |
| Testing | Testcontainers | PostgreSQL/Kafka/Redis 통합테스트 |
| Validation | Validation | 요청 DTO 검증 |
| Developer Tools | Lombok | 보일러플레이트 축소 |
| Developer Tools | Docker Compose Support | 로컬 인프라 자동 기동 |

## Initializr에 없어서 build.gradle에 직접 추가

```groovy
// CircuitBreaker — StagePass에서 쓰던 패턴 재사용
// Spring Boot 4용 공식 스타터 아직 없어서 spring-boot3 아티팩트 그대로 사용, 호환성 재확인 필요 (Week 4)
implementation 'io.github.resilience4j:resilience4j-spring-boot3:2.2.0'

// Quartz — 도입 근거는 W5D2 에서 폐기됐고, 실제 사용처는 W3D1 아웃박스 릴레이다(아래 참고)
implementation 'org.springframework.boot:spring-boot-starter-quartz'
```

### ~~Quartz 채택 이유 (vs `@Scheduled`)~~ — **W5D2 에서 폐기**

원래 근거는 이랬다:

- `@Scheduled`는 고정 주기(fixedDelay/cron)에는 적합하지만, "결제 실패 시점 기준 1일 후 → 3일 후 → 7일 후"처럼 **이벤트마다 다른 시점에 트리거**해야 하는 동적 스케줄에는 부적합
- Quartz는 Job/Trigger를 DB(`QRTZ_*` 테이블)에 영속화해서, 서버 재시작해도 예약된 재시도가 유지됨
- Week 5 DunningScheduler 설계 시: 결제 실패 이벤트 수신 → Quartz Job 동적 등록 → Job 실행 시 재시도

**폐기 근거(W5D2)**: 둘째 항목은 W5D1 이 **다른 방법으로 이미 충족했다** — `billing_cycle.next_retry_at` 이 *행에 있는 사실*이라 재시작과 무관하다. 그 상태에서 Quartz 트리거를 얹으면 *"다음 시도는 언제인가"* 의 원천이 **주기 행과 QRTZ 트리거 둘**이 되고, 시계가 둘이 되는 대가가 동적 트리거의 이득보다 크다.

물증은 스키마에 있다: W5D1 이 깔아둔 `idx_subscription_due`(V12:133)와 `idx_billing_cycle_retry_due`(V12:218)는 **둘 다 폴링 조회를 위한 부분 인덱스**다. Quartz 를 골랐으면 둘 다 죽은 인덱스가 된다.

폴링으로 잃는 건 정밀도인데 간격이 1일·3일·7일이라 뜻이 없고, 대신 **트리거 발화 1회 보증**을 잃는다 — 그건 리스(CAS)로 갚았다. → [W5D2](week5/W5D2_재시도_스케줄러.md)

> **의존성은 남는다.** Quartz 는 W3D1 아웃박스 릴레이가 쓰고 있다(`OutboxRelayConfig`·`OutboxRelayJob`). 위 근거가 거짓이 된 것이지 라이브러리가 놀고 있는 게 아니다.

---

## 새 채팅에서 시작할 때 이렇게 말하기

> "SettleFlow 프로젝트 시작할게. 첨부한 계획/체크리스트/기술결정 파일대로 Week 1 Day 1부터 진행할 건데, Spring Boot 4.1.0 + Gradle + 위 의존성 목록 기준으로 도와줘."
