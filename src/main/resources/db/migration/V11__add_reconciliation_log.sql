-- =========================================
-- V11__add_reconciliation_log.sql
-- 불일치 자동 플래그(W3D5 ①). 대사 결과를 기록해서 "며칠째 안 메워지는지"를 셀 수 있게 한다.
--
-- ── 왜 저장하는가 ─────────────────────────────────────────────────────────
-- W3D3 §1 의 기준(복제냐 별개의 사실이냐)을 대사 결과에 대면 애매하다. 같은 데이터를 다시
-- 대사하면 같은 답이 나오니 파생 같기도 하다. 갈라주는 건 시간이다 — 원천이 바뀌면 답도
-- 바뀐다(배치 재실행이 불일치를 메운다). 그래서 값어치 있는 건 "지금 어긋나 있다"가 아니라
-- **"며칠째 안 메워지고 있다"** 이고, 그건 관측을 쌓아야만 나온다.
--
-- 특히 W3D5 ② 이후 MISSING_IN_SETTLEMENT 는 대체로 일시적이다(다음 배치가 줍는다).
-- 한 번 떴다는 사실보다 안 사라진다는 사실이 신호다.
--
-- ── 상태를 안 쓰고 관측을 쌓는다 ──────────────────────────────────────────
-- 불일치 1건당 1행을 두고 resolved_at 을 UPDATE 하는 모양(A)도 가능했다. 안 골랐다 —
-- W2D1 §5 가 이상 기록까지 append-only 로 못박았다("지우개가 아니라 빨간 펜").
-- 여기선 대사 1회 × 불일치 1건마다 행이 하나 쌓이고, 해소는 "다음 관측에 안 나타남"이다.
--
-- ── 그래서 실행 기록 테이블이 필요하다 ────────────────────────────────────
-- "안 나타남"은 그것만으로 두 가지를 뜻한다:
--     ① 대사가 돌았는데 깨끗했다   ② 대사가 그 날짜를 아예 안 봤다
-- 가르지 못하면 "미해소 불일치가 있는 날짜"를 만들 수 없고, 그게 대사의 날짜 출처 중
-- 하나라 시스템이 성립하지 않는다. reconciliation_run 이 "이 날짜를 이때 봤다"를 남겨
-- 부재에 뜻을 준다. (W3D4 의 ONLY_IN_INTERNAL 이 "아직 안 온 것"과 "영영 안 올 것"을
-- 못 가른 것과 정확히 같은 모호함이다 — 거기선 못 풀었고 여기선 기록으로 푼다.)
-- =========================================

-- 대사를 한 번 돈 기록. 불일치가 0 건이어도 행이 남는다 — 그게 이 테이블의 존재 이유다.
CREATE TABLE reconciliation_run (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    settlement_date   DATE NOT NULL,
    observed_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- 관측 개수의 사본이다. discrepancy_observation 을 세면 나오는 값이라 엄밀히는 파생인데,
    -- "깨끗한 run"이 압도적 다수라 매번 조인해서 0 을 확인하는 걸 피하려고 둔다.
    -- 같은 트랜잭션에서만 써지고 이후 안 바뀐다(append-only).
    discrepancy_count INT NOT NULL
);

-- 날짜별 최신 run 을 찾는 게 주된 접근이다("이 날짜의 마지막 대사에 불일치가 있었나").
CREATE INDEX idx_reconciliation_run_date ON reconciliation_run (settlement_date, id DESC);

-- 불일치 관측 1건. run 에 매달린다 — "언제 본 것인가" 없이는 뜻이 없기 때문이다.
CREATE TABLE discrepancy_observation (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_id        BIGINT NOT NULL REFERENCES reconciliation_run(id),
    seller_id     BIGINT NOT NULL REFERENCES seller(id),

    type          TEXT   NOT NULL,

    -- 한쪽에만 있는 불일치가 있으므로 둘 다 nullable 이다(MISSING_IN_*).
    -- difference 는 Discrepancy 의 부호 규약 그대로: settled − reconstructed,
    -- 양수면 정산이 더 크다 = 과지급 방향(W3D3 §8 ③ 이 아무도 신고 안 한다고 관찰한 쪽).
    settled       BIGINT,
    reconstructed BIGINT,
    difference    BIGINT NOT NULL
);

CREATE INDEX idx_discrepancy_observation_run ON discrepancy_observation (run_id);
