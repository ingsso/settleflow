package com.settleflow;

import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Day 1 미검증 주장 중 2건을 Postgres 실물로 확인한다.
 *   (1) 서브쿼리를 담은 CHECK 제약은 거부된다 → §1 "CHECK는 자기 행 하나만 본다"의 근거
 *   (3) unique 인덱스는 NULL을 여러 개 허용한다 → §2 함정4 pgTransactionId nullable+unique의 근거
 *
 * 동시성이 없어 RefundRaceTest보다 단순: SQL 날리고 결과(예외/건수)만 본다.
 */
@Testcontainers
class SchemaBehaviorTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    private Connection newConn() throws SQLException {
        return DriverManager.getConnection(
                pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        // 여긴 autoCommit 기본값(true) 그대로 — 한 문장씩 즉시 반영이면 충분
    }

    // ── 미검증 (1): 서브쿼리를 담은 CHECK는 거부된다 ──
    @Test
    void 서브쿼리를_담은_CHECK_제약은_거부된다() {
        // "값"이 아니라 "터지는 것"을 검증한다.
        assertThatThrownBy(() -> {
            try (Connection c = newConn(); Statement st = c.createStatement()) {
                // FILL ①: CHECK 안에 서브쿼리(SELECT ...)를 담은 CREATE TABLE을 시도.
                //   힌트: CHECK (x <= (SELECT ... )) 형태면 Postgres가 거부해야 한다.
                st.execute("""
                        CREATE TABLE bad_check (
                            id BIGINT PRIMARY KEY,
                            x  BIGINT
                            -- FILL ①: 여기에 서브쿼리를 담은 CHECK를 넣어보세요
                            CHECK (X <= (SELECT 100))
                        )
                        """);
            }
        }).hasMessageContaining("subquery")
        // FILL ②: 어떤 예외 타입이 나야 하나? (JDBC가 던지는 상위 타입은?)
        .isInstanceOf(SQLException.class);
    }

    // ── 미검증 (3): unique 인덱스는 NULL을 여러 개 허용한다 ──
    @Test
    void unique_인덱스는_NULL을_여러_행에_허용한다() throws SQLException {
        try (Connection c = newConn(); Statement st = c.createStatement()) {
            // nullable + unique 컬럼을 가진 테이블
            st.execute("""
                    CREATE TABLE pg_tx (
                        id             BIGINT PRIMARY KEY,
                        pg_transaction_id VARCHAR(64) UNIQUE   -- nullable + unique
                    )
                    """);

            // FILL ③: pg_transaction_id를 NULL로 둔 행을 '두 번' INSERT 하세요.
            //   (id는 서로 달라야 PK 충돌이 안 남)
            st.execute("INSERT INTO pg_tx (id, pg_transaction_id) VALUES (1, NULL)");
            st.execute("INSERT INTO pg_tx (id, pg_transaction_id) VALUES (2, NULL)");

            // FILL ④: 실제로 몇 건 들어갔는지 세서 검증.
            //   힌트: SELECT count(*) FROM pg_tx → 기대값은?
            long rows;
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM pg_tx")) {
                rs.next();
                rows = rs.getLong(1);
            }
            assertThat(rows).isEqualTo(2);
        }
    }
}
