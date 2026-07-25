-- =========================================
-- V6__add_outbox.sql
-- 트랜잭셔널 아웃박스(W3D1). 이벤트를 장부와 같은 트랜잭션에서 INSERT 해 dual-write 를 없앤다 —
-- 이벤트와 장부가 갈라질 수 없다(W1D3 Redis 폐기·W2D4 파생 라벨과 같은 "진실은 하나").
--
-- 폴링 릴레이(Quartz)가 미발행 행을 긁어 Kafka 로 실어나르고 published_at 을 UPDATE 한다.
-- 이 UPDATE 는 append-only 위반이 아니다 — 아웃박스는 재무 진실이 아니라 전송 큐다. 진실은
-- journal/ledger_entry 에 있고 여긴 그걸 나르는 파이프일 뿐(그래서 published_at 만 mutable).
-- =========================================

CREATE TABLE outbox (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    aggregate_id BIGINT      NOT NULL,           -- = payment.id. Kafka 메시지 키 → 같은 결제 이벤트 동일 파티션
    event_type   VARCHAR(64) NOT NULL,           -- payment.completed / refund.requested / refund.completed
    event_id     UUID        NOT NULL UNIQUE,     -- 소비자 멱등키. UNIQUE 로 발행 측 중복도 봉쇄
    payload      JSONB       NOT NULL,            -- fat 스냅샷(발행 시점의 사실 — 대사가 외부 리포트와 대조)
    occurred_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ                      -- NULL = 미발행. 릴레이가 발행 후 채운다
);

-- 릴레이는 미발행 행만 id 순서로 긁는다. 발행되면 published_at 이 차서 인덱스에서 빠지므로
-- 인덱스가 "발행 대기 큐"의 크기로 수렴한다(발행 끝난 이력이 인덱스를 부풀리지 않는다).
CREATE INDEX idx_outbox_unpublished ON outbox (id) WHERE published_at IS NULL;
