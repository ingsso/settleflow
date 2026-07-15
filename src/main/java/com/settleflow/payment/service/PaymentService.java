package com.settleflow.payment.service;

import com.settleflow.order.domain.Order;
import com.settleflow.order.repository.OrderRepository;
import com.settleflow.payment.domain.Payment;
import com.settleflow.payment.pg.PgClient;
import com.settleflow.payment.pg.PgResult;
import com.settleflow.payment.pg.PgTimeoutException;
import com.settleflow.payment.repository.PaymentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/**
 * 결제 시작. PG 호출을 <b>트랜잭션 밖</b>에서 한다(W1D1 §2 함정1).
 *
 * <pre>
 *   tx1: PENDING 행 INSERT + 커밋   ← 여기서 끊고 커밋해야 한다
 *   ----- 트랜잭션 경계 -----
 *   PG 동기 호출(느리고 터질 수 있음)
 *   ----- 트랜잭션 경계 -----
 *   tx2: 결과 반영 (PaymentTransitionService)
 * </pre>
 *
 * PG 호출을 tx 안에서 하면, 호출이 터졌을 때 롤백되어 PENDING 행이 사라진다 —
 * 그러면 나중에 도착하는 웹훅이 매칭할 행이 없다. PENDING 은 PG 를 부르기 <b>전에</b> 남긴다.
 */
@Service
public class PaymentService {

    private final PgClient pgClient;
    private final PaymentRepository paymentRepository;
    private final OrderRepository orderRepository;
    private final PaymentTransitionService transitionService;
    private final TransactionTemplate txTemplate;

    public PaymentService(PgClient pgClient,
                          PaymentRepository paymentRepository,
                          OrderRepository orderRepository,
                          PaymentTransitionService transitionService,
                          PlatformTransactionManager txManager) {
        this.pgClient = pgClient;
        this.paymentRepository = paymentRepository;
        this.orderRepository = orderRepository;
        this.transitionService = transitionService;
        this.txTemplate = new TransactionTemplate(txManager);
    }

    /**
     * 주문을 결제한다.
     *
     * @return 이 결제의 merchant_payment_id — 호출부/테스트가 이후 웹훅을 매칭하는 데 쓴다
     */
    public UUID pay(Long orderId) {
        // --- tx1: PG 호출 전에 PENDING 을 커밋한다 ---
        Pending pending = txTemplate.execute(tx -> {
            Order order = orderRepository.findById(orderId)
                    .orElseThrow(() -> new IllegalArgumentException("no order " + orderId));
            Payment payment = new Payment(order, order.getTotalAmount());
            paymentRepository.save(payment);
            return new Pending(payment.getMerchantPaymentId(), payment.getAmount());
        });

        // --- 트랜잭션 밖에서 PG 호출 ---
        try {
            PgResult result = pgClient.approve(pending.merchantPaymentId(), pending.amount());
            // --- tx2: 결과 반영(각자 자기 트랜잭션 + 낙관적 락) ---
            if (result == PgResult.APPROVED) {
                transitionService.applyApproved(pending.merchantPaymentId(), null);
            } else {
                transitionService.applyDeclined(pending.merchantPaymentId(), null);
            }
        } catch (PgTimeoutException e) {
            // 실패가 아니라 정보의 부재 → UNKNOWN. 진실은 나중에 웹훅/조회로 채운다.
            transitionService.applyTimedOut(pending.merchantPaymentId());
        }

        return pending.merchantPaymentId();
    }

    /** tx1 밖으로 들고 나갈 값. PG 호출은 tx 밖이라 엔티티가 아니라 값만 꺼낸다. */
    private record Pending(UUID merchantPaymentId, long amount) {
    }
}
