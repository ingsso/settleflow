package com.settleflow;

import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 검증 대상: Postgres READ COMMITTED에서 조건부 UPDATE가
 *          동시 환불 레이스(lost update)를 막는가.
 *
 * 이 가정이 참이어야 §1 A안(refunded_amount 비정규화 + 원자적 조건부 UPDATE)이 성립.
 * 통과하면 W2D3 "불변식 DB 제약 검증 테스트"로 재사용한다.
 *
 * 방법 2(수동 JDBC): Spring 컨텍스트 없이 순수 JUnit + Testcontainers.
 *   - DriverManager로 커넥션을 직접 열어 "커넥션 풀 1개" 함정을 회피.
 *   - setAutoCommit(false)로 트랜잭션 경계를 우리가 직접 통제.
 */
@Testcontainers
class RefundRaceTest {

    // 테스트가 자기 전용 Postgres를 띄운다 (시작 시 기동, 끝나면 자동 폐기)
    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    // 컨테이너에 새 커넥션 하나 여는 헬퍼 (스레드마다 각자 부르면 독립 트랜잭션)
    private Connection newConn() throws SQLException {
        Connection c = DriverManager.getConnection(
                pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        c.setAutoCommit(false);   // 수동 커밋 = 우리가 트랜잭션 경계를 통제
        return c;
    }

    // 매 테스트 전: 깨끗한 테이블 + 잔액 세팅
    @BeforeEach
    void setUp() throws SQLException {
        try (Connection c = newConn(); Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS payment");
            // TODO(1): payment 테이블 정의.
            //   필요한 컬럼은? (§1 A안: 무엇과 무엇을 비교하죠?)
            //   CHECK 제약도 여기 얹을지 결정.
            st.execute("""
                CREATE TABLE payment (
                    id              BIGINT PRIMARY KEY,
                    amount          BIGINT NOT NULL,
                    refunded_amount BIGINT NOT NULL DEFAULT 0
                )
                """);
            // TODO(1): 시나리오 잔액 심기.
            //   §1의 예시(결제 10,000 / 기환불 5,000)를 그대로 쓸지 정하세요.
            st.execute("INSERT INTO payment (id, amount, refunded_amount) VALUES (1, 10000, 5000)");
            c.commit();
        }
    }

    @Test
    void 동시_환불_두건이_한도를_넘으면_하나만_성공한다() throws Exception {
        long refundAmt = 3000;   // 3000 + 3000 = 6000, 기존 5000과 합치면 11000 > 10000

        CyclicBarrier gate = new CyclicBarrier(2);       // 2명 모이면 동시 출발
        ExecutorService pool = Executors.newFixedThreadPool(2);

        // 한 스레드가 할 일: 배리어에서 대기 → 조건부 UPDATE → 영향 행 수 반환
        Callable<Integer> attempt = () -> {
            try (Connection c = newConn();
                 // 1단계: SQL은 빈칸(?)만 뚫린 텍스트. setLong은 여기 없다.
                 PreparedStatement ps = c.prepareStatement("""
                         UPDATE payment
                            SET refunded_amount = refunded_amount + ?
                         WHERE id = ?
                            AND refunded_amount + ? <= amount
                         """)) {
                // 2단계: 빈칸을 채운다. prepareStatement '다음' 별도 줄에서.
                ps.setLong(1, refundAmt);   // 첫 번째 ? = 더할 금액
                ps.setLong(2, 1L);          // 두 번째 ? = 결제 id (§BeforeEach에서 심은 id=1)
                ps.setLong(3, refundAmt);   // 세 번째 ? = 한도 체크용 금액
                gate.await();               // 다른 스레드도 여기 올 때까지 대기 → 동시 발사
                int affected = ps.executeUpdate();
                c.commit();
                return affected;            // 1 = 성공, 0 = 거부
            }
        };

        Future<Integer> f1 = pool.submit(attempt);
        Future<Integer> f2 = pool.submit(attempt);

        int a1 = f1.get();
        int a2 = f2.get();
        pool.shutdown();

        // TODO(3): 두 가지를 다 검증
        // (1) 성공 카운트: a1 + a2 == ?   (힌트: 하나만 통과해야 함)
        // (2) 최종 값:     SELECT refunded_amount → ?   (힌트: 11000이면 실패)
        assertThat(a1 + a2).isEqualTo(1);

        // 최종 값을 DB에 '물어봐서' 꺼내온다 (SQL 문자열은 질문일 뿐, 실행해서 답을 읽어야 값이 됨)
        long finalRefunded;
        try (Connection c = newConn();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT refunded_amount FROM payment WHERE id = 1")) {
            rs.next();
            finalRefunded = rs.getLong(1);
        }
        assertThat(finalRefunded).isEqualTo(8000);
    }

    // TODO(4) 음성 대조군: WHERE 한도 조건을 '뺀' UPDATE로 돌리면 11000이 써지는가.
    //   위 테스트가 초록불인 게 "가드 덕분"임을 증명하려면, 가드를 빼면 깨져야 한다.
    //   @BeforeEach가 매 테스트 전에 테이블을 다시 5000으로 세팅해주므로 준비는 자동.
    @Test
    void 가드를_빼면_동시_환불이_한도를_넘어_이중환불된다() throws Exception {
        long refundAmt = 3000;

        CyclicBarrier gate = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        Callable<Integer> attempt = () -> {
            try (Connection c = newConn();
                 // FILL ①: 위 UPDATE에서 'AND refunded_amount + ? <= amount' 한 줄만 뺀다.
                 //   → 빈칸(?)이 3개에서 몇 개로 줄어드는지 세어볼 것.
                 PreparedStatement ps = c.prepareStatement("""
                         UPDATE payment
                            SET refunded_amount = refunded_amount + ?
                          WHERE id = ?
                         """)) {
                // FILL ②: 위에서 센 빈칸 수만큼 setLong. (몇 줄이어야 하나?)
                ps.setLong(1, refundAmt);
                ps.setLong(2, 1L);

                gate.await();
                int affected = ps.executeUpdate();
                c.commit();
                return affected;
            }
        };

        Future<Integer> f1 = pool.submit(attempt);
        Future<Integer> f2 = pool.submit(attempt);

        int a1 = f1.get();
        int a2 = f2.get();
        pool.shutdown();

        long finalRefunded;
        try (Connection c = newConn();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT refunded_amount FROM payment WHERE id = 1")) {
            rs.next();
            finalRefunded = rs.getLong(1);
        }

        // FILL ③: 가드가 없으니 둘 다 성공하고 한도를 넘어야 한다.
        //   성공 카운트와 최종 값은 각각 얼마여야 하나?
        assertThat(a1 + a2).isEqualTo(2);
        assertThat(finalRefunded).isEqualTo(11000);
    }
}
