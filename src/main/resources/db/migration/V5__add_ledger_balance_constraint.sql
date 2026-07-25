-- =========================================
-- V5__add_ledger_balance_constraint.sql
-- 불변식 Σ차변 == Σ대변 (사건 단위)을 DB 가 강제한다.
--
-- CHECK 로는 안 된다 — CHECK 는 자기 행 하나만 본다(W1D1 §1). 이 불변식은 한 journal 의
-- 여러 줄을 합쳐야 나오므로 트리거가 필요하다. 게다가 한 journal 의 3줄은 각각 따로 INSERT 되니,
-- INSERT 마다 즉시 검사하면 첫 줄(현금만)에서 불균형으로 오판한다.
--   → CONSTRAINT TRIGGER ... DEFERRABLE INITIALLY DEFERRED: 검사를 커밋 시점으로 미룬다.
--     트랜잭션 중간의 일시적 불균형은 허용하고, 커밋 순간 모든 줄이 모인 뒤 균형만 보장한다.
--
-- append-only(W2D1)라 INSERT 에만 건다. 균형은 차변 +, 대변 − 로 합쳐 0 인지로 판정한다.
-- =========================================

CREATE FUNCTION assert_journal_balanced() RETURNS TRIGGER AS $$
DECLARE
    imbalance BIGINT;
BEGIN
    SELECT coalesce(sum(CASE WHEN dr_cr = 'DEBIT' THEN amount ELSE -amount END), 0)
      INTO imbalance
      FROM ledger_entry
     WHERE journal_id = NEW.journal_id;

    IF imbalance <> 0 THEN
        -- check_violation(23514) → Spring 이 DataIntegrityViolationException 으로 번역(SQLState class 23).
        RAISE EXCEPTION 'journal % 불균형: 차변-대변=%', NEW.journal_id, imbalance
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NULL;   -- AFTER 트리거라 반환값은 무시된다
END;
$$ LANGUAGE plpgsql;

-- CONSTRAINT TRIGGER 는 FOR EACH ROW 만 허용된다. 한 journal 의 줄 수만큼 (커밋 시점에) 중복 실행되지만
-- 판정은 매번 같아 무해하다(정확성 우선).
CREATE CONSTRAINT TRIGGER trg_journal_balanced
    AFTER INSERT ON ledger_entry
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION assert_journal_balanced();