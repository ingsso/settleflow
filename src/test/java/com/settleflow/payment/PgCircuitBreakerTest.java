package com.settleflow.payment;

import com.settleflow.order.service.OrderService;
import com.settleflow.payment.domain.RefundOutcome;
import com.settleflow.payment.domain.RefundPgStatus;
import com.settleflow.payment.pg.PgClient;
import com.settleflow.payment.pg.PgRefundResult;
import com.settleflow.payment.pg.PgResult;
import com.settleflow.payment.pg.PgTimeoutException;
import com.settleflow.payment.repository.PaymentRepository;
import com.settleflow.payment.service.RefundConfirmService;
import com.settleflow.payment.service.RefundService;
import com.settleflow.product.domain.Product;
import com.settleflow.seller.domain.Seller;
import com.settleflow.support.IntegrationTestSupport;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * W4D1 실측 — PG 차단기.
 *
 * <h3>무엇을 갈아끼우는가</h3>
 * 대체하는 건 {@code stubPgClient}(<b>delegate</b>)뿐이고 {@code ResilientPgClient} 는 그대로 둔다.
 * 회로가 그 데코레이터에 살기 때문에 <b>프로덕션 회로가 실제로 돈다</b> — 만약 회로를
 * {@code StubPgClient} 에 직접 붙였다면 이 대체와 함께 회로도 사라져, 테스트 구현체에
 * 애노테이션을 복사해 초록을 만들거나(=테스트 회로를 검증) 영영 빨간불이거나 둘 중 하나다.
 *
 * <h3>확인하는 것 넷</h3>
 * <ol>
 *   <li><b>유기적 개방</b> — 타임아웃이 쌓이면 회로가 스스로 열리고, 이후 호출은 PG 에
 *       <b>닿지도 않는다</b>({@code verify(never())} 가 그 증거)</li>
 *   <li><b>승인의 해석</b> — 열린 동안의 결제는 {@code UNKNOWN} 이 아니라 {@code FAILED}.
 *       안 보낸 걸 우리가 알기 때문이다</li>
 *   <li><b>환불의 해석</b> — 열린 동안의 환불 확정은 예외가 나가고 상태가 {@code REQUESTED} 로
 *       남는다. 예약도 장부도 그대로 — <b>이미 진 빚을 우리 회로 사정으로 종료시키지 않는다</b></li>
 *   <li><b>격리</b> — 승인 회로가 열려도 환불은 나간다. 회로를 둘로 나눈 판단의 직접 증거</li>
 * </ol>
 *
 * <p>②③④ 는 {@code transitionToOpenState()} 로 회로를 <b>강제</b> 개방한다. 유기적으로 여는
 * 경로는 ①이 이미 증명했으므로, 나머지는 "열렸을 때 각 경로가 무엇으로 해석하는가"에만
 * 집중한다 — 매번 10건씩 실패를 쌓으면 재는 대상이 흐려진다.
 */
class PgCircuitBreakerTest extends IntegrationTestSupport {

    /** {@code application.yaml} 의 {@code minimum-number-of-calls} 와 같아야 한다. */
    private static final int MIN_CALLS = 10;

    /** 갈아끼우는 건 delegate 뿐 — 회로를 가진 {@code ResilientPgClient} 는 그대로 남는다. */
    @MockitoBean(name = "stubPgClient") PgClient delegate;

    /** {@code @Primary} 라 여기 주입되는 건 {@code ResilientPgClient} = 프로덕션 회로. */
    @Autowired PgClient pgClient;
    @Autowired CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired OrderService orderService;
    @Autowired PaymentRepository paymentRepository;
    @Autowired RefundService refundService;
    @Autowired RefundConfirmService refundConfirmService;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager txManager;

    /**
     * 회로는 컨텍스트에 붙어 있어 테스트 간에 상태가 남는다 — 한 테스트가 열어둔 회로가
     * 다음 테스트를 막는다. 데이터({@code cleanDatabase})와 같은 이유로 매번 되돌린다.
     */
    @BeforeEach
    void resetCircuitsAndStubs() {
        circuitBreakerRegistry.circuitBreaker("pg-approve").reset();
        circuitBreakerRegistry.circuitBreaker("pg-refund").reset();
        given(delegate.approve(any(), anyLong())).willReturn(PgResult.APPROVED);
        given(delegate.refund(anyLong(), anyLong())).willReturn(new PgRefundResult.Approved("pgr-stub"));
    }

    // ---------- ① 유기적 개방 ----------

    @Test
    void 타임아웃이_쌓이면_승인_회로가_열리고_이후_호출은_PG_에_닿지_않는다() {
        given(delegate.approve(any(), anyLong())).willThrow(new PgTimeoutException("PG down"));
        CircuitBreaker breaker = circuitBreakerRegistry.circuitBreaker("pg-approve");

        for (int i = 0; i < MIN_CALLS; i++) {
            assertThatThrownBy(() -> pgClient.approve(UUID.randomUUID(), 20_000L))
                    .isInstanceOf(PgTimeoutException.class);
        }
        CircuitBreaker.State afterFailures = breaker.getState();

        // 열린 뒤의 호출은 예외 <b>종류가 바뀐다</b> — 대상에 닿기 전에 거절됐다는 뜻이다.
        assertThatThrownBy(() -> pgClient.approve(UUID.randomUUID(), 20_000L))
                .isInstanceOf(CallNotPermittedException.class);

        System.out.printf("[실측] 타임아웃 %d회 → 회로=%s, %d+1 번째 호출은 CallNotPermittedException%n",
                MIN_CALLS, afterFailures, MIN_CALLS);

        assertThat(afterFailures).isEqualTo(CircuitBreaker.State.OPEN);
        // ★ 실제로 PG 에 간 건 10번뿐이다. 11번째는 delegate 를 부르지도 않았다.
        verify(delegate, times(MIN_CALLS)).approve(any(), anyLong());
    }

    // ---------- ② 승인 — 안 보냈으니 FAILED ----------

    @Test
    void 회로가_열려_있으면_결제는_보내지도_않고_FAILED_로_확정된다() {
        long productId = seedProduct();
        circuitBreakerRegistry.circuitBreaker("pg-approve").transitionToOpenState();

        OrderService.PlacedOrder placed = orderService.place(Map.of(productId, 2));

        String status = paymentStatusOf(placed.merchantPaymentId());
        System.out.printf("[실측] 회로 OPEN 중 결제 → payment.status=%s, PG 호출=0회%n", status);

        // 안 보냈다는 게 이 케이스의 전부다 — 이게 아니면 FAILED 로 찍을 근거가 없다.
        verify(delegate, never()).approve(any(), anyLong());
        // UNKNOWN 이 아니다. 정보의 부재가 아니라 확실한 앎이라서.
        assertThat(status).isEqualTo("FAILED");
    }

    // ---------- ③ 환불 — 이미 진 빚이라 종료시키지 않는다 ----------

    @Test
    void 회로가_열려_있으면_환불_확정은_예외가_나가고_REQUESTED_로_남는다() {
        Long paymentId = paidOrder();
        long refundId = requestRefund(paymentId, 5_000L);
        circuitBreakerRegistry.circuitBreaker("pg-refund").transitionToOpenState();

        // 예외가 <b>나가야</b> 소비자가 오프셋을 안 밀고 재배달된다. 여기서 삼키면 환불이 사라진다.
        assertThatThrownBy(() -> refundConfirmService.confirm(refundId))
                .isInstanceOf(CallNotPermittedException.class);

        String pgStatus = pgStatusOf(paymentId);
        long reserved = refundedAmount(paymentId);
        long refundJournals = count("SELECT count(*) FROM journal WHERE type = 'REFUND'");

        System.out.printf("[실측] 회로 OPEN 중 환불확정 → pg_status=%s 예약=%,d REFUND journal=%d (PG 호출=0회)%n",
                pgStatus, reserved, refundJournals);

        verify(delegate, never()).refund(anyLong(), anyLong());
        // FAILED 가 아니다 — 그건 "PG 가 거절했다"는 뜻이고 예약 해제와 함께 종착지다.
        assertThat(pgStatus).isEqualTo("REQUESTED");
        assertThat(reserved).isEqualTo(5_000L);   // 예약 유지 = 빚이 남아 있다
        assertThat(refundJournals).isZero();      // 장부는 안 건드렸다
    }

    // ---------- ④ 격리 — 회로를 둘로 나눈 이유 ----------

    @Test
    void 승인_회로가_열려도_환불_회로는_닫혀_있다() {
        Long paymentId = paidOrder();
        long refundId = requestRefund(paymentId, 5_000L);
        long productId = seedProduct();

        // 픽스처(paidOrder)가 이미 approve 를 정상 호출했다 — 카운터를 <b>회로를 여는 시점에</b>
        // 맞춰야 "연 뒤로 한 번도 안 갔다"를 잴 수 있다. 처음엔 이걸 빼먹어 never() 가
        // 픽스처의 호출에 걸렸다(W1D4 에서 카운터를 blast 뒤에 설치해 헛돌았던 것과 같은 자리).
        clearInvocations(delegate);
        circuitBreakerRegistry.circuitBreaker("pg-approve").transitionToOpenState();

        // 갚는 쪽은 그대로 나간다 — 승인 장애가 이미 진 빚의 이행을 막지 않는다.
        RefundPgStatus refundStatus = refundConfirmService.confirm(refundId);
        // 같은 시점에 파는 쪽은 막힌다.
        OrderService.PlacedOrder placed = orderService.place(Map.of(productId, 2));
        String paymentStatus = paymentStatusOf(placed.merchantPaymentId());

        System.out.printf("[실측] pg-approve=OPEN / pg-refund=%s → 환불=%s, 결제=%s%n",
                circuitBreakerRegistry.circuitBreaker("pg-refund").getState(),
                refundStatus, paymentStatus);

        assertThat(refundStatus).isEqualTo(RefundPgStatus.COMPLETED);
        assertThat(paymentStatus).isEqualTo("FAILED");
        verify(delegate, times(1)).refund(anyLong(), anyLong());
        verify(delegate, never()).approve(any(), anyLong());
    }

    // ---------- helpers ----------

    private long requestRefund(Long paymentId, long amount) {
        RefundOutcome outcome = refundService.requestRefund(paymentId, amount);
        return ((RefundOutcome.Accepted) outcome).refund().getId();
    }

    private String paymentStatusOf(UUID merchantPaymentId) {
        return (String) em.createNativeQuery(
                        "SELECT status FROM payment WHERE merchant_payment_id = :mid")
                .setParameter("mid", merchantPaymentId)
                .getSingleResult();
    }

    private String pgStatusOf(Long paymentId) {
        return (String) em.createNativeQuery(
                "SELECT pg_status FROM refund WHERE payment_id = " + paymentId).getSingleResult();
    }

    private long refundedAmount(Long paymentId) {
        return ((Number) em.createNativeQuery(
                        "SELECT reserved_refund_amount FROM payment WHERE id = " + paymentId)
                .getSingleResult()).longValue();
    }

    private long count(String sql) {
        return ((Number) em.createNativeQuery(sql).getSingleResult()).longValue();
    }

    private long seedProduct() {
        return new TransactionTemplate(txManager).execute(s -> {
            Seller seller = new Seller("셀러A", 500);
            em.persist(seller);
            Product product = new Product(seller, "상품", 10_000L, 5);
            em.persist(product);
            em.flush();
            return product.getId();
        });
    }

    /** 상품 1개(10,000)×2 → 20,000 PAID 결제. 회로가 닫혀 있을 때만 부를 것. */
    private Long paidOrder() {
        OrderService.PlacedOrder placed = orderService.place(Map.of(seedProduct(), 2));
        return paymentRepository.findByMerchantPaymentId(placed.merchantPaymentId())
                .orElseThrow().getId();
    }
}
