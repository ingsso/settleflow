package com.settleflow.settlement;

import com.settleflow.support.IntegrationTestSupport;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W3D3 이월 숙제 — <b>인덱스 순서 주장을 `EXPLAIN` 으로 실측한다</b>(W3D3 → D4 → D5, 3회 이월).
 *
 * <h3>검증하려는 주장(W3D3 §5 마지막 문단)</h3>
 * <i>"복합 인덱스는 leftmost prefix 만 커버한다. 배치의 {@code ON CONFLICT} 는 키를 다 주므로
 * 순서와 무관하니, 판단 기준은 <b>읽는 쪽</b>이다 — W3D4 대사가 '특정 날짜의 전 셀러'를 훑으므로
 * 날짜가 선두. 셀러 단독 조회는 {@code idx_settlement_seller_id} 로 따로 메웠다."</i>
 *
 * <p>그 주장이 옳다면 관측 가능한 게 셋이다:
 * <ol>
 *   <li><b>날짜 단독</b>({@code findBySettlementDateOrderBySellerId}) → {@code uq_settlement_date_seller}
 *       를 탄다. 그리고 <b>{@code Sort} 노드가 없어야 한다</b> — 인덱스가 이미
 *       {@code (date, seller_id)} 순이라 {@code ORDER BY seller_id} 가 공짜다.
 *       <b>이게 컬럼 순서를 그렇게 고른 값어치의 직접 증거다</b></li>
 *   <li><b>날짜+셀러</b>({@code findBySettlementDateAndSellerId}) → 같은 인덱스, 1행</li>
 *   <li><b>셀러 단독</b> → 그 UNIQUE 를 <b>못 타고</b> {@code idx_settlement_seller_id} 를 탄다.
 *       그 인덱스를 없애면 {@code Seq Scan} 으로 떨어진다(= UNIQUE 가 대신 못 해준다)</li>
 * </ol>
 *
 * <h3>왜 데이터를 많이 심는가</h3>
 * 몇 행짜리 테이블에는 플래너가 <b>항상 {@code Seq Scan}</b> 을 고른다 — 인덱스를 타는 게 더
 * 비싸기 때문이다. 그 상태의 `EXPLAIN` 은 인덱스에 대해 아무것도 말해주지 않는다.
 * 그래서 {@link #DAYS}×{@link #SELLERS} 행을 심고 {@code VACUUM (ANALYZE)} 로 통계를 갱신한 뒤에
 * 잰다 — {@code VACUUM} 이 왜 같이 필요한지는 {@link #seed()} 에 적어뒀다.
 *
 * <p><b>이 클래스는 정확성을 검증하지 않는다.</b> 플랜은 데이터 분포·PG 버전·설정에 따라 바뀔 수
 * 있으므로 단언은 최소로 두고(인덱스 이름과 {@code Sort} 유무), 판단 근거는 출력된 플랜 전문에 있다.
 */
class SettlementIndexPlanTest extends IntegrationTestSupport {

    /** 플래너가 인덱스를 고를 만큼은 되고 테스트가 느려지지 않을 만큼은 작게. */
    private static final int DAYS = 300;
    private static final int SELLERS = 40;

    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager txManager;
    /** {@code VACUUM} 전용 — 트랜잭션 밖에서 실행해야 하므로 {@code em} 이 아니라 이쪽을 쓴다({@link #seed()}). */
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void 인덱스_순서_주장을_EXPLAIN_으로_실측한다() {
        seed();
        long sellerId = anySellerId();

        String dateOnly = explain("""
                SELECT * FROM settlement
                 WHERE settlement_date = DATE '2026-03-01'
                 ORDER BY seller_id
                """);
        String dateAndSeller = explain("""
                SELECT * FROM settlement
                 WHERE settlement_date = DATE '2026-03-01' AND seller_id = %d
                """.formatted(sellerId));
        String sellerOnly = explain("""
                SELECT * FROM settlement WHERE seller_id = %d
                """.formatted(sellerId));

        System.out.printf("""
                [실측] EXPLAIN — settlement %d행 (%d일 × %d셀러)

                ── ① 날짜 단독 (W3D4 대사가 타는 경로) ─────────────────
                %s
                ── ② 날짜+셀러 ────────────────────────────────────────
                %s
                ── ③ 셀러 단독 ────────────────────────────────────────
                %s
                """, DAYS * SELLERS, DAYS, SELLERS, dateOnly, dateAndSeller, sellerOnly);

        // ① 날짜 선두가 값을 하는 자리 — 인덱스를 타고, ORDER BY 가 공짜다
        assertThat(dateOnly).contains("uq_settlement_date_seller");
        assertThat(dateOnly).doesNotContain("Sort");

        // ② 키를 다 주면 같은 인덱스
        assertThat(dateAndSeller).contains("uq_settlement_date_seller");

        // ③ leftmost prefix 가 아니라 UNIQUE 를 못 탄다 → 따로 판 인덱스가 받는다
        assertThat(sellerOnly).contains("idx_settlement_seller_id");
        assertThat(sellerOnly).doesNotContain("uq_settlement_date_seller");
    }

    /**
     * {@code idx_settlement_seller_id} 를 지우면 셀러 단독 조회가 어디로 떨어지는가.
     *
     * <p>W3D3 이 <i>"UNIQUE 가 날짜 선두라 셀러 단독 조회를 못 탄다"</i> 고 단정한 부분의 직접 검증이다.
     * 못 탄다면 대안이 없으므로 {@code Seq Scan} 이어야 한다.
     *
     * <p><b>DDL 을 트랜잭션 안에서 하고 롤백한다.</b> Postgres 는 DDL 도 트랜잭션이라 스키마가
     * 실제로 바뀌지 않는다 — 플랜만 보고 되돌린다.
     */
    @Test
    void 셀러_인덱스를_지우면_UNIQUE_가_대신_해주지_못한다() {
        seed();
        long sellerId = anySellerId();

        String[] plan = new String[1];
        new TransactionTemplate(txManager).execute(status -> {
            em.createNativeQuery("DROP INDEX idx_settlement_seller_id").executeUpdate();
            em.createNativeQuery("ANALYZE settlement").executeUpdate();
            plan[0] = explainInCurrentTx(
                    "SELECT * FROM settlement WHERE seller_id = " + sellerId);
            status.setRollbackOnly();          // 스키마를 되돌린다
            return null;
        });

        System.out.printf("""
                [실측] EXPLAIN — idx_settlement_seller_id 제거 후 셀러 단독 조회
                %s
                """, plan[0]);

        assertThat(plan[0]).contains("Seq Scan");
        assertThat(plan[0]).doesNotContain("uq_settlement_date_seller");

        // 롤백됐으므로 인덱스가 돌아와 있어야 한다
        assertThat(explain("SELECT * FROM settlement WHERE seller_id = " + sellerId))
                .contains("idx_settlement_seller_id");
    }

    // ---------- helpers ----------

    /**
     * 실제로 존재하는 셀러 id 하나.
     *
     * <p>리터럴 {@code 7} 을 쓰면 안 된다 — {@code cleanDatabase} 는 행만 지우고 IDENTITY 시퀀스는
     * 안 되돌리므로 클래스·실행마다 id 범위가 달라진다. 처음엔 그렇게 썼다가 {@code rows=0} 인
     * 플랜을 얻었다: <b>플랜 모양은 통계 기반이라 유효했지만 그 경로를 실제로 밟진 않았다.</b>
     * 오늘 문서의 주제와 같은 함정이라 여기 적어둔다.
     *
     * <p>바인드 파라미터가 아니라 리터럴로 박아 넣는 것도 의도다 — {@code EXPLAIN} 에 파라미터를
     * 주면 플래너가 값 분포를 모른 채 <b>generic plan</b> 을 만들 수 있어 실제 실행과 달라진다.
     */
    private long anySellerId() {
        return new TransactionTemplate(txManager).execute(s ->
                ((Number) em.createNativeQuery("SELECT min(id) FROM seller").getSingleResult())
                        .longValue());
    }

    /**
     * {@link #DAYS}×{@link #SELLERS} 행. 셀러는 FK 때문에 실제로 있어야 한다.
     *
     * <p>{@code ANALYZE} 를 빼먹으면 플래너가 빈 테이블 통계를 들고 판단해서 이 실측이 통째로
     * 무의미해진다 — 심는 것보다 이쪽이 중요하다.
     *
     * <p><b>그런데 {@code ANALYZE} 만으로는 절반이었다(2026-08-03 발각).</b> 부모의
     * {@code cleanDatabase} 는 {@code DELETE} 라 죽은 튜플이 블록에 남고, {@code ANALYZE} 는 그
     * 부푼 블록 위에서 통계를 잡는다. 그러면 살아있는 40행이 80블록에 흩어진 것으로 보여
     * {@code Index Scan} 의 랜덤 접근 비용이 올라가고, 플래너가 {@code Bitmap Heap Scan} 으로
     * 갈아탄다 — <b>비트맵은 인덱스 순서를 보존하지 않으므로 {@code ORDER BY} 에 {@code Sort} 가
     * 붙는다.</b> 그게 이 클래스가 노리는 바로 그 단언을 깼다.
     *
     * <p>증상은 <b>앞에 무엇이 돌았느냐</b>에 달려 있었다 — 클래스 단독 실행은 초록, 전체 스위트에서만
     * 빨강. 즉 이 테스트는 자기 데이터가 아니라 <b>남이 남긴 잔해</b>를 재고 있었다.
     * {@code VACUUM} 으로 잔해를 먼저 걷어낸 뒤 통계를 잡는다.
     *
     * <p><b>트랜잭션 밖에서 돌려야 한다</b> — {@code VACUUM} 은 트랜잭션 블록 안에서 실행할 수 없다.
     * 그래서 삽입만 {@code TransactionTemplate} 으로 묶고, {@code VACUUM (ANALYZE)} 는 커밋 뒤에
     * {@code JdbcTemplate}(autocommit 커넥션)으로 따로 부른다. {@code ANALYZE} 를 여기 합쳤으므로
     * 트랜잭션 안의 {@code ANALYZE} 는 없앴다 — 두 번 할 이유가 없다.
     */
    private void seed() {
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            em.createNativeQuery("""
                    INSERT INTO seller (name, fee_rate)
                    SELECT 'S' || i, 500 FROM generate_series(1, :sellers) i
                    """).setParameter("sellers", SELLERS).executeUpdate();

            em.createNativeQuery("""
                    INSERT INTO settlement (seller_id, settlement_date, net_amount)
                    SELECT s.id,
                           DATE '2026-01-01' + d,
                           (random() * 1000000)::bigint
                      FROM seller s, generate_series(0, :days - 1) d
                    """).setParameter("days", DAYS).executeUpdate();
        });

        // 삽입 트랜잭션이 커밋된 뒤에 — 그래야 방금 넣은 행이 통계에 잡힌다.
        jdbcTemplate.execute("VACUUM (ANALYZE) settlement");
    }

    private String explain(String sql) {
        return new TransactionTemplate(txManager).execute(s -> explainInCurrentTx(sql));
    }

    @SuppressWarnings("unchecked")
    private String explainInCurrentTx(String sql) {
        List<Object> rows = em.createNativeQuery("EXPLAIN ANALYZE " + sql).getResultList();
        return String.join("\n", rows.stream().map(String::valueOf).toList());
    }
}
