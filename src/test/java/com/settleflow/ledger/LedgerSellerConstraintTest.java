package com.settleflow.ledger;

import com.settleflow.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.*;

/**
 * V4 의 chk_ledger_seller_presence 가 실제로 막는지 확인한다.
 *
 * <p>"CASH 면 셀러 없고, SELLER_PAYABLE·FEE_REVENUE 면 반드시 있다" — 판정에 자기 행의
 * 두 컬럼만 필요해서 CHECK 로 표현된다(W1D1 §1 의 뒤집힌 면).
 *
 * <p>SchemaBehaviorTest 와 달리 임시 테이블을 만들지 않는다. Flyway 로 마이그레이션된
 * <b>진짜 스키마</b>에 INSERT 를 날려야 V4 자체가 검증된다 — 개념이 아니라 우리 DDL.
 *
 * <p>엔티티(LedgerEntry)에 seller 매핑이 아직 없어서 JdbcTemplate 로 생 SQL 을 쓴다.
 */
class LedgerSellerConstraintTest extends IntegrationTestSupport {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager txManager;

    private Long journalId;
    private Long sellerId;

    @BeforeEach
    void 픽스처() {
        // ledger_entry.journal_id 는 NOT NULL FK 라 journal 이 먼저 있어야 한다.
        journalId = jdbc.queryForObject(
                "INSERT INTO journal (type) VALUES ('PAYMENT') RETURNING id", Long.class);

        // fee_rate 를 안 넣는다 — V4 의 DEFAULT 500 이 받아주는지도 여기서 같이 드러난다.
        sellerId = jdbc.queryForObject(
                "INSERT INTO seller (name) VALUES ('테스트셀러') RETURNING id", Long.class);
    }

    /** 줄 하나를 넣는다. 실패를 기대하는 쪽에선 이 호출이 터져야 한다. */
    private void insertEntry(String account, String drCr, long amount, Long sellerId) {
        jdbc.update("""
                INSERT INTO ledger_entry (journal_id, account, dr_cr, amount, seller_id)
                VALUES (?, ?, ?, ?, ?)
                """, journalId, account, drCr, amount, sellerId);
    }

    // ── 거부 ①: CASH 줄에 셀러가 붙으면 안 된다 ──
    @Test
    void CASH_줄에_셀러가_있으면_거부된다() {
        assertThatThrownBy(() ->
                insertEntry("CASH", "DEBIT", 20_000L, /* seller */ sellerId)
        )
        // FILL ①: 어떤 예외 타입인가?
        //   힌트: JdbcTemplate 은 SQLException 을 스프링 예외로 번역한다. CHECK/FK/UNIQUE/NOT NULL 을
        //   싸잡는 그 '우산 예외'가 뭐였는지 W1D3 에 적어뒀다.
        .isInstanceOf(/* FILL ① */ DataIntegrityViolationException.class)
        // FILL ②: 우산 예외라서 타입만 보면 가짜 통과가 남는다 — NOT NULL 위반으로 터져도 초록불이다.
        //   어느 제약이 막았는지 이름까지 못 박을 것. (V4 에 쓴 그 이름)
        .hasMessageContaining(/* FILL ② */ "chk_ledger_seller_presence");
    }

    // ── 거부 ②: SELLER_PAYABLE 줄에 셀러가 없으면 안 된다 ──
    @Test
    void SELLER_PAYABLE_줄에_셀러가_없으면_거부된다() {
        // FILL ③: 위와 같은 모양으로, 이번엔 seller 를 null 로 넣어서 거부되는지.
        //   방향을 둘 다 봐야 하는 이유: 한쪽만 보면 CHECK 의 OR 절반이 죽어 있어도 초록불이 난다.
        assertThatThrownBy(() ->
                insertEntry("SELLER_PAYABLE", "CREDIT", 20_000L, null)
        )
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_ledger_seller_presence");
    }

    // ── 통과: 올바른 조합은 들어가야 한다 ──
    @Test
    void 올바른_조합은_통과한다() {
        //   이 케이스가 없으면 안 되는 이유:
        //   CHECK 를 잘못 써서 '무조건 거부'가 돼 있어도 위 두 테스트는 통과한다.
        //   거부 테스트만 있으면 "막는다"는 알아도 "지나가야 할 게 지나가나"는 모른다. 음성대조군의 거울상.
        //
        //   V5 부터: "정상 삽입"은 presence 뿐 아니라 균형(Σ차변==Σ대변)도 만족해야 한다.
        //   그래서 (1) 균형 잡힌 3줄을 넣고, (2) 지연 검사가 커밋에 몰아 돌도록 한 트랜잭션으로 묶는다
        //   (autocommit 이면 CASH 한 줄만 커밋되는 순간 불균형으로 거부됨).
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            insertEntry("CASH", "DEBIT", 20_000L, null);
            insertEntry("SELLER_PAYABLE", "CREDIT", 19_000L, sellerId);
            insertEntry("FEE_REVENUE", "CREDIT", 1_000L, sellerId);
        });

        Long rows = jdbc.queryForObject("SELECT count(*) FROM ledger_entry WHERE journal_id = ?", Long.class, journalId);

        assertThat(rows).isEqualTo(3);
    }
}
