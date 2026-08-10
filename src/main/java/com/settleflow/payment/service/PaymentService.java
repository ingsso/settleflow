package com.settleflow.payment.service;

import com.settleflow.order.domain.Order;
import com.settleflow.order.repository.OrderRepository;
import com.settleflow.payment.domain.Payment;
import com.settleflow.payment.pg.PgClient;
import com.settleflow.payment.pg.PgResult;
import com.settleflow.payment.pg.PgTimeoutException;
import com.settleflow.payment.repository.PaymentRepository;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 *
 * <h3>PG 호출의 네 번째 결말(W4D1)</h3>
 * 승인·거절·무응답에 <b>"보내지 않음"</b>이 붙었다. 차단기가 열려 있으면 호출이 PG 에 닿지
 * 않고 {@code CallNotPermittedException} 으로 되돌아온다 — 무응답과 달리 <b>확실한 앎</b>이라
 * {@code UNKNOWN} 이 아니라 {@code FAILED} 다. 근거는 아래 catch 블록에 적어뒀다.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

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
        } catch (CallNotPermittedException e) {
            // 차단기가 열려 호출이 PG 에 닿지도 않았다(W4D1). 타임아웃과 <b>뜻이 다르다</b> —
            // 저건 "보냈는데 답을 못 들었다"(정보의 부재)지만 이건 "아예 안 보냈다"(확실한 앎)다.
            // PG 에 기록이 없고 돈이 안 움직였으므로 FAILED 로 확정해도 이중청구가 안 열린다.
            // UNKNOWN 으로 찍으면 있지도 않은 불확실성을 만들어 쓸이 배치에 짐만 얹는다.
            //
            // 딸린 이득: W1D3 이 미결제 쓸이 배치에 요구한 "복원 전에 PG 에 조회해서 정말 결제가
            // 안 됐는지 확인"이 이 경로만은 필요 없다 — 안 보낸 걸 우리가 안다. 재고를 즉시
            // 되돌려도 이중판매가 안 열리는 유일한 실패 경로다.
            //
            // 판단 기준이 회로의 <b>상태</b>가 아니라 이 예외인 게 중요하다. HALF_OPEN 에서는
            // 시험 호출이 실제로 나가고, 그게 타임아웃 나면 그건 진짜 정보의 부재라 아래 catch 로
            // 가야 한다. CallNotPermittedException 은 호출이 거절됐을 때만 나온다.
            log.info("PG 차단기 개방 — 요청을 보내지 않고 FAILED 확정 merchantPaymentId={}",
                    pending.merchantPaymentId());
            transitionService.applyDeclined(pending.merchantPaymentId(), null);
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
