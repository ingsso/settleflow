package com.settleflow.subscription;

import com.settleflow.payment.pg.PgClient;
import com.settleflow.payment.pg.PgResult;
import com.settleflow.seller.domain.Seller;
import com.settleflow.subscription.domain.Subscription;
import com.settleflow.subscription.service.DunningScheduler;
import com.settleflow.support.IntegrationTestSupport;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * W5D3 — 계획서 D3 는 <i>"Week 1 의 Idempotency-Key 구조를 재시도에도 적용, 중복 청구 방지"</i> 를
 * 요구한다. <b>그 전제가 틀렸음을 실측으로 세우는 게 이 클래스다.</b>
 *
 * <p>두 문장을 나란히 세운다:
 *
 * <pre>
 *   문장1  키는 정상이다 — 같은 시도를 몇 번 통보해도 부수효과는 1회
 *   문장2  그런데 돈은 두 번 나간다 — 같은 주기에 시도가 둘이면 PG 호출도 둘
 * </pre>
 *
 * 키가 지키는 건 <i>같은 시도의 재전송</i>이고, 이중 청구는 <i>같은 주기의 두 시도</i>에서
 * 난다 — 그 둘은 {@code merchantPaymentId} 가 서로 달라 PG 에게 <b>정당한 별개 청구</b>다.
 *
 * <h3>★ W5D4 에 결론을 한 칸 좁혔다</h3>
 * W5D3 은 여기서 <i>"멱등키는 중복 청구를 막는 물건이 아니다"</i> 로 끝냈는데 그건 넓었다.
 * 정확히는 <b>"우리가 재전송해야 할 자리에서 새 시도를 만들었다"</b> 다 — 결말이 안 난 시도는
 * <i>새 청구가 필요하다</i>가 아니라 <i>답을 아직 못 들었다</i> 이고, 같은 키로 다시 보내는 건
 * 재전송이라 키가 정확히 그걸 겨눈다. W5D3 §7 이 <i>"주기 단위 키를 주면 시도 층이 무너진다"</i>
 * 고 적은 건 <b>세 번째 선택지(재전송)를 못 본 것</b>이었다.
 *
 * <h3>여기 있던 크래시 테스트는 옮겼다</h3>
 * {@code 크래시로_리스만_남으면_같은_주기에_PG_청구가_두_번_나간다} 가 W5D3 의 반증 본체였고,
 * <b>[실측] PG approve 3회 · 서로 다른 키 3개</b> 를 냈다. W5D4 가 그 창을 닫으면서 같은
 * 시나리오가 <b>키 2개</b>를 내므로, 고친 뒤의 단언은 {@code SubscriptionResendTest} 에 있다.
 * 버그를 단언하는 테스트를 남겨둘 수는 없어 옮겼고, 어제 수치는 W5D3 문서 §7 에 남아 있다.
 *
 * <h3>★ 계기를 바꿨다 — 어제 수치는 우리 DB 를 쟀다</h3>
 * W5D2 의 <i>"동시 폴링 10개 → payment 행 2개"</i> 는 <b>우리 기록</b>이지 PG 가 아니다.
 * 그 대리 지표가 성립하는 근거는 "한 시도 = 한 PG 호출"인데 그건 검증 대상 코드 자신의
 * 성질이라, 재는 자와 재이는 것이 경로를 공유한다(W4 회고 §4-2).
 *
 * <p>그래서 여기선 {@code stubPgClient} delegate 를 갈아끼우고 <b>Mockito 로 PG 호출 횟수를
 * 직접 센다</b>(W4D1 PgCircuitBreakerTest 가 세운 패턴). 회로를 가진 {@code ResilientPgClient}
 * 는 그대로 남으므로 프로덕션 경로를 통과한다.
 */
class SubscriptionIdempotencyTest extends IntegrationTestSupport {

    /** 갈아끼우는 건 delegate 뿐 — 이게 곧 "PG 에 실제로 몇 번 갔나"의 계기다. */
    @MockitoBean(name = "stubPgClient") PgClient delegate;

    @Autowired DunningScheduler scheduler;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager txManager;

    @LocalServerPort int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private static final long AMOUNT = 30_000L;
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 15);

    /**
     * <b>{@code will…given} 형태만 쓴다.</b> {@code given(mock.method())} 은 스텁을 걸면서
     * 그 메서드를 <b>실제로 호출</b>하는데, 직전에 예외를 던지도록 스텁돼 있으면
     * <b>스텁을 다시 거는 줄에서 터진다</b>. 이 테스트는 중간에 "크래시"를 심었다가 걷어내므로
     * 그 함정에 정확히 걸린다(실제로 걸렸다).
     */
    @BeforeEach
    void stubApproves() {
        willReturn(PgResult.APPROVED).given(delegate).approve(any(), anyLong());
    }

    // ---------- 문장1 — 키는 제 일을 한다 ----------

    /**
     * 같은 시도({@code merchantPaymentId})에 승인 통보가 20번 와도 부수효과는 1회다.
     *
     * <p><b>{@code eventId} 를 매번 다르게 보낸다</b> — Redis SETNX 를 일부러 빠져나가게 해서,
     * 막는 게 fast-path 가 아니라 <b>전이표</b>임을 보이려는 것이다(W1D4 음성대조군 A 와 같은 수법).
     * SETNX 는 효율이고 정합성은 {@code payment} 의 종착지 판정이 지킨다.
     */
    @Test
    void 같은_시도를_스무_번_통보해도_분개는_하나다() {
        createSubscription();
        scheduler.runBillingDue(TODAY);

        String mpid = str("SELECT merchant_payment_id::text FROM payment");

        for (int i = 0; i < 20; i++) {
            postWebhook("evt-" + UUID.randomUUID(), "APPROVED", mpid, "pg-tx-1");
        }

        long journals = num("SELECT count(*) FROM journal");
        long entries = num("SELECT count(*) FROM ledger_entry");
        long cash = num("SELECT coalesce(sum(amount),0) FROM ledger_entry WHERE account = 'CASH'");

        System.out.printf("[실측] 문장1 — 같은 시도 20회 통보 → journal=%d 분개줄=%d 현금=%d%n",
                journals, entries, cash);

        assertThat(journals).isEqualTo(1);
        assertThat(entries).isEqualTo(2);
        assertThat(cash).isEqualTo(AMOUNT);   // 장부에 돈은 한 번만 적혔다
    }

    // ---------- 문장2 — 같은 주기에 시도가 둘이면 키도 둘 ----------
    //
    // W5D3 의 크래시 시나리오(PG approve 3회 · 서로 다른 키 3개)는 W5D4 가 그 창을 닫으면서
    // SubscriptionResendTest 로 옮겼다. 고친 뒤 같은 시나리오는 키 2개를 낸다.
    // 아래 테스트는 크래시가 없어도 성립하는 쪽 — "실패가 확정된 시도 뒤의 재시도는 새 키다" —
    // 만 남긴다. 그건 W5D4 이후에도 참이고, 참이어야 한다(그게 시도 층이다).

    /**
     * 위 시나리오의 결론을 한 줄로 못 박는다: <b>같은 주기인데 키가 다르다.</b>
     * 멱등키가 이중 청구를 막으려면 같은 주기의 두 시도가 같은 키를 가져야 하는데,
     * 그러면 "시도 1회당 정확히 한 번"이라는 전이표의 전제가 깨진다 —
     * 두 시도가 한 행으로 뭉쳐 W5D1 이 시도 층을 만든 이유가 사라진다.
     */
    @Test
    void 같은_주기의_두_시도는_서로_다른_키를_갖는다() {
        createSubscription();
        willReturn(PgResult.DECLINED).given(delegate).approve(any(), anyLong());

        scheduler.runBillingDue(TODAY);
        OffsetDateTime t0 = OffsetDateTime.now();
        scheduler.runRetryDue(t0.plusDays(1).plusMinutes(1));

        long cycles = num("SELECT count(*) FROM billing_cycle");
        long attempts = num("SELECT count(*) FROM payment");
        long distinctKeys = num("SELECT count(DISTINCT merchant_payment_id) FROM payment");

        System.out.printf("[실측] 주기=%d · 시도=%d · 서로 다른 멱등키=%d%n",
                cycles, attempts, distinctKeys);

        assertThat(cycles).isEqualTo(1);
        assertThat(attempts).isEqualTo(2);
        assertThat(distinctKeys).isEqualTo(2);   // 한 주기, 두 키
    }

    // ---------- helpers ----------

    private void createSubscription() {
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            Seller seller = new Seller("구독셀러", 500);
            em.persist(seller);
            em.persist(new Subscription(seller, AMOUNT, 30, TODAY));
            em.flush();
        });
    }

    private void postWebhook(String eventId, String type, String mpid, String pgTx) {
        String json = String.format(
                "{\"eventId\":\"%s\",\"eventType\":\"%s\",\"merchantPaymentId\":\"%s\","
                        + "\"pgTransactionId\":\"%s\",\"amount\":%d}",
                eventId, type, mpid, pgTx, AMOUNT);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/webhooks/pg"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            assertThat(res.statusCode()).isEqualTo(200);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String str(String sql) {
        Object v = em.createNativeQuery(sql).getSingleResult();
        return v == null ? null : v.toString();
    }

    private long num(String sql) {
        Object v = em.createNativeQuery(sql).getSingleResult();
        return v == null ? 0 : ((Number) v).longValue();
    }
}
