# Week 2 Day 3 — Σ차변==Σ대변 불변식을 DB 제약으로

**날짜**: 2026-07-21
**산출물**: `V5__add_ledger_balance_constraint.sql`(지연 제약 트리거), `LedgerBalanceConstraintTest`(3케이스), `LedgerSellerConstraintTest` 회귀 수정. 전체 **30건 0실패**.
**방식**: 개념(불변식을 어디서 강제하나)은 본인이 초안 → 정확한 도구·문법은 유도/조립.

> **증거 원칙**: 아래 수치는 전부 실제 실행 결과. 미검증은 §4.

---

## 0. 왜 DB 제약인가 — 앱 가드로는 부족

W2D2 의 `Journal.assertBalanced()` 는 **앱 계층** 가드다 — `LedgerService` 를 거치는 코드만 지킨다. 생 SQL, 미래의 다른 서비스, 배치가 `ledger_entry` 에 직접 INSERT 하면 아무도 안 막는다. 장부가 정산의 진실원본인 이 프로젝트에서, 불변식은 **어떤 코드도 못 우회하는 마지막 선**이 있어야 한다 → DB.

---

## 1. 왜 CHECK 가 아니라 트리거이고, 왜 지연인가

**CHECK 는 안 된다.** CHECK 는 자기 행 하나만 본다(W1D1 §1). `Σ차변==Σ대변` 은 한 journal 의 **여러 줄을 합쳐야** 나오는 값이라 CHECK 로 표현 불가. W2D1 의 `chk_ledger_seller_presence`(자기 행 두 컬럼 → CHECK 가능)와 정확히 반대편.

여러 행을 조회할 수 있는 건 **트리거**다. 그런데 한 journal 의 3줄은 각각 따로 INSERT 된다 —

**즉시 트리거는 안 된다.** `AFTER INSERT` 가 매 줄마다 즉시 검사하면, 첫 줄(현금 20,000만)에서 대변 0 → 불균형으로 **오판**한다. 검사는 3줄이 다 모인 **커밋 시점**에 몰아 해야 한다.

→ **`CONSTRAINT TRIGGER ... DEFERRABLE INITIALLY DEFERRED`.** "제약 트리거"이고 "지연"이라 트랜잭션이 끝나는 순간 검사한다. 일반 트리거와의 결정적 차이가 이 지연이다.

### 확정

```sql
CREATE FUNCTION assert_journal_balanced() RETURNS TRIGGER ...
  -- NEW.journal_id 의 줄들을 sum(차변 +, 대변 −). 균형이면 상쇄되어 0.
  -- 0 아니면 RAISE EXCEPTION ... ERRCODE 'check_violation'(→ Spring DataIntegrityViolationException)

CREATE CONSTRAINT TRIGGER trg_journal_balanced
    AFTER INSERT ON ledger_entry           -- append-only(W2D1) → INSERT 만
    DEFERRABLE INITIALLY DEFERRED          -- 커밋 시점 검사
    FOR EACH ROW                           -- CONSTRAINT TRIGGER 는 FOR EACH ROW 만 허용
    EXECUTE FUNCTION assert_journal_balanced();
```

- **INSERT 에만** — `ledger_entry` 는 지우개가 아니라 빨간 펜(W2D1, W1D1 §5). UPDATE/DELETE 가 없으니 INSERT 만 보면 된다.
- **차변 +, 대변 −, 합 0** — 본인이 도출한 판정식.

---

## 2. 앱 가드 vs DB 가드 — 중복이 아니라 이중방어

| | `assertBalanced()` (W2D2, 앱) | `trg_journal_balanced` (W2D3, DB) |
|---|---|---|
| 언제 | 서비스에서 조립 직후 | 커밋 시점 |
| 무엇을 | 프로그래밍 오류를 빠르게·친절한 메시지로 | **어떤 경로도** 못 우회하는 최종 보증 |
| 범위 | `LedgerService` 통과 코드만 | 생 SQL·미래 서비스·배치 포함 전부 |

층이 다르다. 앱 가드는 조기 발견용, DB 가드는 최종 방어선.

---

## 3. 실측 (전부 실행 결과)

`LedgerBalanceConstraintTest` — autoCommit=false 로 커밋 경계를 직접 쥠(지연 동작 관찰용):

| 케이스 | 관측 |
|---|---|
| 균형 3줄 → 커밋 | 통과, 3행 |
| 불균형(수수료 누락) | INSERT 는 통과, **커밋에서 거부**(`SQLException` "불균형") — 실패가 INSERT 가 아닌 **커밋** 시점 |
| 트랜잭션 중간 불균형(현금만) | 허용 — 즉시 트리거였다면 여기서 터졌을 자리 |

- **음성대조군**(수동): 트리거를 `DROP` 하고 불균형 케이스 재실행 → 이제 그냥 커밋됨(빨간불) → 트리거가 유일한 방어선임을 확인 후 원복.
- **예상 못한 소득 — V5 가 W2D2 테스트를 잡았다.** 회귀에서 `LedgerSellerConstraintTest.올바른_조합` 이 깨졌다. 그 "정상 조합"이 사실 **불균형 journal**(20,000 vs 19,000)을 넣고 있었고 — W2D2 앱 계층은 통과시켰지만 V5 DB 제약이 잡았다. **앱 가드와 DB 가드가 서로 다른 걸 잡는다**는 산 증거. "정상 삽입은 이제 균형이어야 한다"에 맞춰 균형 3줄 + 한 트랜잭션으로 수정.
- 전체 **30건 0실패**(트리거 복원 상태).

---

## 4. 정직한 한계 / 이월

- **트리거는 INSERT 만 본다.** append-only 가 전제 — 누군가 `ledger_entry` 를 UPDATE/DELETE 하면 불변식이 뚫린다. 지금은 append-only 를 규율로만 지키고 DB 가 UPDATE/DELETE 를 막진 않는다(별도 가드 미도입).
- **`FOR EACH ROW` 중복 실행** — 한 journal 의 줄 수만큼 커밋 시점에 같은 검사를 반복한다. 판정이 매번 같아 무해하나 비효율(정확성 우선).
- **반올림의 실제 반올림 경로는 여전히 미검증**(W2D2 §5 이월) — 소수점 .5 가 나는 케이스를 아직 안 걸었다.
- **부분환불 역분개** — Day 5. 오늘 불변식은 결제 분개 기준.

---

### 참고 문서
- W1D1 §1(CHECK 는 자기 행만 — 여기서 정면으로 만남), §5(append-only)
- W2D1(모델), W2D2(`assertBalanced` 앱 가드 — 오늘의 DB 가드와 짝)

> **규칙**: 스키마를 바꾸면 같은 커밋에서 문서를 갱신한다.
