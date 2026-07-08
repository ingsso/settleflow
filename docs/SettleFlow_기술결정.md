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

// Week 5 정기결제(Dunning) 재시도 스케줄용 — @Scheduled 대신 Quartz로 결정
implementation 'org.springframework.boot:spring-boot-starter-quartz'
```

### Quartz 채택 이유 (vs `@Scheduled`)
- `@Scheduled`는 고정 주기(fixedDelay/cron)에는 적합하지만, "결제 실패 시점 기준 1일 후 → 3일 후 → 7일 후"처럼 **이벤트마다 다른 시점에 트리거**해야 하는 동적 스케줄에는 부적합
- Quartz는 Job/Trigger를 DB(`QRTZ_*` 테이블)에 영속화해서, 서버 재시작해도 예약된 재시도가 유지됨 — 결제 재시도처럼 유실되면 안 되는 스케줄에 적합
- Week 5 DunningScheduler 설계 시: 결제 실패 이벤트 수신 → Quartz Job 동적 등록(재시도 시점 계산해서 트리거 설정) → Job 실행 시 재시도 + Idempotency-Key로 중복 방지 + CircuitBreaker OPEN이면 스킵하고 다음 트리거로 재예약

---

## 새 채팅에서 시작할 때 이렇게 말하기

> "SettleFlow 프로젝트 시작할게. 첨부한 계획/체크리스트/기술결정 파일대로 Week 1 Day 1부터 진행할 건데, Spring Boot 4.1.0 + Gradle + 위 의존성 목록 기준으로 도와줘."
