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
 *       <b>이게 컬럼 순서를 그렇게 고른 값어치의 직접 증거다</b>.
 *       <p><b>단 전제가 하나 붙는다</b> — {@code Sort} 가 붙느냐는 플래너가 순서를 보존하는
 *       {@code Index Scan} 을 고르느냐에 달렸고, 그 선택은 인덱스 컬럼 순서가 아니라 <b>힙의
 *       물리적 배치</b>({@code pg_stats.correlation})가 정한다. 즉 이 단언은 <i>인덱스 설계가
 *       옳은가</i>와 <i>데이터가 마침 뭉쳐 있는가</i>를 한 칸에서 재고 있었고, 후자가 실행마다
 *       뒤집혀 4회 이월된 빨간불의 원인이 됐다(W4D5, {@link #seed()} 참조). 그래서 전제를
 *       {@code Sort} 단언 앞에서 따로 단언한다 — 한 칸이 둘을 겸하면 갈라진다(W3D4
 *       {@code refunded_amount} 가드↔라벨, W3D5 {@code created_at} 귀속↔진행과 같은 모양)</li>
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

        // ★ 전제를 먼저 단언한다 — 아래 Sort 단언이 성립하기 위한 조건이고, 이 클래스가 주장하는
        //   "인덱스 컬럼 순서의 값어치"와는 다른 것이다(W4D5). 이게 깨지면 인덱스 설계가 틀린 게
        //   아니라 seed 의 물리적 배치가 프로덕션과 달라진 것이다 — 4회 이월된 red 가 그 경우였다.
        assertThat(correlationOf("settlement_date"))
                .as("""
                    전제: settlement 의 물리적 배치가 날짜순이어야 한다(프로덕션 배치가 하루치씩 \
                    넣으므로). 이 값이 낮으면 같은 날짜의 행들이 여러 블록에 흩어져 플래너가 \
                    Bitmap Heap Scan 을 고르고, 비트맵은 인덱스 순서를 보존하지 않으므로 아래 \
                    Sort 단언이 인덱스 설계와 무관하게 깨진다. seed() 의 ORDER BY 를 확인할 것""")
                .isGreaterThan(0.9);

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
     *
     * <h3>★ 그런데 그 수리도 절반이었다(2026-08-10, W4D5 에서 닫음)</h3>
     * 잔해는 실제로 없어졌는데({@code Heap Blocks} 80 → 40) 플래너는 여전히 {@code Bitmap} 을
     * 골랐다. 원인은 잔해가 아니라 <b>이 {@code INSERT} 의 물리적 삽입 순서가 실행마다 뒤집힌다</b>는
     * 것이었다. 같은 커밋을 두 번 돌려 직접 쟀다:
     *
     * <pre>
     * [실측] green 실행 — corr(settlement_date)=1.0000, corr(seller_id)=0.0283
     *                     ctid 앞 12행: 날짜 고정·셀러 증가  = 날짜-major
     *                     '2026-03-01' 40행이 1블록에        → Index Scan (Sort 없음)
     * [실측] red   실행 — corr(settlement_date)=0.0283, corr(seller_id)=1.0000
     *                     ctid 앞 12행: 셀러 고정·날짜 증가  = 셀러-major
     *                     '2026-03-01' 40행이 40블록에 1행씩 → Bitmap Heap Scan (+ Sort)
     * [실측] 두 실행의 행 수(12,000)·힙 블록 수(225)·시작 블록(112) 은 <b>같았다</b>.
     * </pre>
     *
     * {@code FROM seller s, generate_series(...) d} 는 크로스 조인이라 <b>어느 쪽이 중첩 루프의
     * 바깥이 되는지가 비용 추정에 달려 있고, 두 플랜의 비용이 거의 붙어 있다.</b> 바깥이 뒤집히면
     * 물리 순서가 뒤집히고, 그러면 어느 컬럼이 뭉치는지가 뒤집힌다.
     *
     * <p><b>무엇이 뒤집는지는 못 찾았다</b> — 일회용 컨테이너로 가설 6개를 반증했다(조인 순서 명시 /
     * {@code DELETE} 뒤 재삽입 / 바인드 파라미터 / {@code seller} 통계 유무 /
     * {@code DELETE}+{@code VACUUM} 뒤 재삽입 / generic plan). 전부 날짜-major 를 냈고 red 를
     * 재현하지 못했다.
     *
     * <p>그래서 <b>원인을 없애는 대신 순서를 못박는다</b> — {@code ORDER BY settlement_date, seller_id}.
     * 이건 원하는 답이 나오게 데이터를 배치하는 게 아니다: 프로덕션 배치는
     * {@code upsertDailyNetBySeller(date, ...)} 로 <b>하루치씩</b> 넣으므로({@code CAST(:date AS date)}
     * 한 값 + {@code GROUP BY seller_id}) 실제 힙은 날짜순으로 쌓인다. <b>셀러-major 는 프로덕션에
     * 존재하지 않는 배치였고, seed 가 그것을 절반의 확률로 만들고 있었다.</b> 순서를 명시하는 건
     * 없는 조건을 만드는 게 아니라 있는 조건을 재현하는 것이다.
     *
     * <p>그리고 못박은 것에 기대지 않고 <b>단언한다</b> — 원인을 못 찾았으므로 전제를 가정할 수 없다
     * ({@link #인덱스_순서_주장을_EXPLAIN_으로_실측한다()} 의 첫 단언).
     */
    private void seed() {
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            em.createNativeQuery("""
                    INSERT INTO seller (name, fee_rate)
                    SELECT 'S' || i, 500 FROM generate_series(1, :sellers) i
                    """).setParameter("sellers", SELLERS).executeUpdate();

            // ORDER BY 는 성능이 아니라 물리적 배치를 위한 것이다 — 위 javadoc 참조.
            // 이걸 빼면 중첩 루프의 바깥이 실행마다 뒤집혀 테스트가 깜빡인다.
            em.createNativeQuery("""
                    INSERT INTO settlement (seller_id, settlement_date, net_amount)
                    SELECT s.id,
                           DATE '2026-01-01' + d,
                           (random() * 1000000)::bigint
                      FROM seller s, generate_series(0, :days - 1) d
                     ORDER BY DATE '2026-01-01' + d, s.id
                    """).setParameter("days", DAYS).executeUpdate();
        });

        // 삽입 트랜잭션이 커밋된 뒤에 — 그래야 방금 넣은 행이 통계에 잡힌다.
        jdbcTemplate.execute("VACUUM (ANALYZE) settlement");
    }

    /**
     * {@code pg_stats.correlation} — 그 컬럼의 <b>값 순서와 물리적 행 순서가 얼마나 일치하는가</b>.
     * 1.0 이면 완전 일치, 0 이면 무상관.
     *
     * <p>플래너는 이 값으로 {@code Index Scan} 의 랜덤 접근 비용을 추정한다. 그래서 이 값이
     * {@link #인덱스_순서_주장을_EXPLAIN_으로_실측한다()} 의 {@code Sort} 단언을 좌우한다 —
     * 왜 그런지는 그 메서드의 javadoc 에 적어뒀다.
     *
     * <p>{@link #seed()} 의 {@code VACUUM (ANALYZE)} 뒤에 불러야 값이 있다.
     */
    private double correlationOf(String column) {
        return new TransactionTemplate(txManager).execute(s ->
                ((Number) em.createNativeQuery("""
                        SELECT correlation FROM pg_stats
                         WHERE tablename = 'settlement' AND attname = :col
                        """).setParameter("col", column).getSingleResult()).doubleValue());
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
