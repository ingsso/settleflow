package com.settleflow.ledger;

import com.settleflow.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;

import static org.assertj.core.api.Assertions.*;

/**
 * V5 의 지연 제약 트리거({@code trg_journal_balanced})가 실제로 Σ차변==Σ대변 을 강제하는가.
 *
 * <p>autoCommit=false 로 <b>커밋 경계를 직접</b> 쥔다 — 지연 트리거는 커밋 시점에 검사하므로,
 * autoCommit(문장마다 커밋)으로는 지연 동작을 관찰할 수 없다. Flyway 로 마이그레이션된 진짜 스키마가
 * 필요해 {@link IntegrationTestSupport} 를 상속한다(공유 컨테이너).
 */
class LedgerBalanceConstraintTest extends IntegrationTestSupport {

    @Autowired DataSource dataSource;

    // ---------- 1. 균형 분개는 커밋된다 ----------

    @Test
    void 균형_분개는_커밋된다() throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            long sid = insertSeller(c);
            long jid = insertJournal(c);
            insertEntry(c, jid, "CASH", "DEBIT", 20_000, null);
            insertEntry(c, jid, "SELLER_PAYABLE", "CREDIT", 19_000, sid);
            insertEntry(c, jid, "FEE_REVENUE", "CREDIT", 1_000, sid);
            c.commit();   // 지연 검사 → 균형 → 통과
        }
        assertThat(entryCount()).isEqualTo(3);
    }

    // ---------- 2. 불균형은 INSERT 는 되지만 커밋에서 거부된다 ----------

    @Test
    void 불균형_분개는_INSERT는_되지만_커밋에서_거부된다() throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            long sid = insertSeller(c);
            long jid = insertJournal(c);

            // INSERT 자체는 통과한다 — 지연이라 아직 검사 안 함.
            insertEntry(c, jid, "CASH", "DEBIT", 20_000, null);
            insertEntry(c, jid, "SELLER_PAYABLE", "CREDIT", 19_000, sid);
            // 수수료 1,000 누락 → 대변 19,000 ≠ 차변 20,000

            // 터지는 곳은 INSERT 가 아니라 커밋이다 — 지연 트리거의 핵심.
            assertThatThrownBy(c::commit)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("불균형");
        }
    }

    // ---------- 3. 트랜잭션 중간의 일시적 불균형은 허용된다 ----------

    @Test
    void 트랜잭션_중간의_일시적_불균형은_허용된다() throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            long sid = insertSeller(c);
            long jid = insertJournal(c);

            insertEntry(c, jid, "CASH", "DEBIT", 20_000, null);
            // 이 순간 대변 0 — 일시적 불균형. 즉시 트리거였다면 여기서 터졌을 것.
            insertEntry(c, jid, "SELLER_PAYABLE", "CREDIT", 19_000, sid);
            insertEntry(c, jid, "FEE_REVENUE", "CREDIT", 1_000, sid);

            c.commit();   // 커밋 시점엔 균형 → 통과. 예외 없이 끝나는 것 자체가 증거.
        }
        assertThat(entryCount()).isEqualTo(3);
    }

    // ---------- helpers ----------

    private long insertSeller(Connection c) throws SQLException {
        // fee_rate 는 생략 — V4 DEFAULT 500.
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("INSERT INTO seller (name) VALUES ('셀러') RETURNING id")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private long insertJournal(Connection c) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("INSERT INTO journal (type) VALUES ('PAYMENT') RETURNING id")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private void insertEntry(Connection c, long journalId, String account, String drCr,
                             long amount, Long sellerId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO ledger_entry (journal_id, account, dr_cr, amount, seller_id) VALUES (?, ?, ?, ?, ?)")) {
            ps.setLong(1, journalId);
            ps.setString(2, account);
            ps.setString(3, drCr);
            ps.setLong(4, amount);
            if (sellerId == null) ps.setNull(5, Types.BIGINT);
            else ps.setLong(5, sellerId);
            ps.executeUpdate();
        }
    }

    private long entryCount() throws SQLException {
        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM ledger_entry")) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
