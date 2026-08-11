package com.settleflow.payment.service;

import com.settleflow.ledger.domain.Journal;
import com.settleflow.ledger.service.LedgerService;
import com.settleflow.order.domain.Order;
import com.settleflow.order.domain.OrderTransitionResult;
import com.settleflow.outbox.domain.EventType;
import com.settleflow.outbox.service.OutboxWriter;
import com.settleflow.payment.domain.Payment;
import com.settleflow.payment.domain.TransitionResult;
import com.settleflow.payment.event.PaymentCompletedEvent;
import com.settleflow.payment.repository.PaymentRepository;
import com.settleflow.subscription.domain.BillingCycle;
import com.settleflow.subscription.domain.SubscriptionTransitionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 전이표의 backbone(W1D4 B안). 사건 하나를 결제에 반영한다.
 *
 * <p><b>엔티티가 전이표를 소유하고(on*), 이 서비스가 재시도와 부수효과를 소유한다.</b>
 * 동기 승인(pay())과 웹훅 승인이 같은 PENDING 을 동시에 전이시켜도, 낙관적 락으로 한 쪽만
 * 성공하고 진 쪽은 재조회 → 종착지 → IGNORED 로 끝난다. 부수효과(주문 전이 등)는 한 번만 실행된다.
 *
 * <p>SETNX(웹훅 멱등키)는 fast-path 일 뿐이고, 정합성의 최종 보증은 여기다 — SETNX 를 빠져나온
 * 중복(동기+웹훅 겹침, 다른 eventId 로 온 재승인)도 이 층에서 걸린다("두 겹").
 */
@Service
public class PaymentTransitionService {

    private static final Logger log = LoggerFactory.getLogger(PaymentTransitionService.class);
    private static final int MAX_ATTEMPTS = 3;

    private final PaymentRepository paymentRepository;
    private final LedgerService ledgerService;
    private final OutboxWriter outboxWriter;
    private final TransactionTemplate txTemplate;

    public PaymentTransitionService(PaymentRepository paymentRepository,
                                    LedgerService ledgerService,
                                    OutboxWriter outboxWriter,
                                    PlatformTransactionManager txManager) {
        this.paymentRepository = paymentRepository;
        this.ledgerService = ledgerService;
        this.outboxWriter = outboxWriter;
        this.txTemplate = new TransactionTemplate(txManager);
    }

    /**
     * 승인 확정. TRANSITIONED 면 주문에 결제확정 사건을 전한다.
     * pgTransactionId 는 웹훅이 실어온다(동기 경로는 null).
     *
     * <p>주문이 이미 취소돼 있으면 CONFLICT 다 — 결제 전이는 그대로 커밋하고(승인은 사실이다)
     * 이상만 기록한다. 자세한 근거는 {@link Order#onPaymentConfirmed()}.
     */
    public TransitionResult applyApproved(UUID merchantPaymentId, String pgTransactionId) {
        return dispatch(merchantPaymentId, pgTransactionId,
                Payment::onApproved,
                payment -> {
                    // 돈은 들어왔다 — 주문 상태와 무관하게 분개는 남긴다. TRANSITIONED 1회에만 불리므로
                    // 중복 웹훅이 와도 journal 은 하나(W2D1 §4). tx2 안이라 PAID·journal 이 원자적으로 함께 커밋.
                    Journal journal = ledgerService.recordPayment(payment);
                    payment.linkJournal(journal);

                    // payment.completed 를 같은 tx 에 append(W3D1). fat 스냅샷은 방금 만든 journal 을
                    // 거울처럼 읽어 만든다 — 이벤트와 장부가 갈라질 수 없다. journal 과 함께 원자적으로 커밋.
                    outboxWriter.append(payment.getId(), EventType.PAYMENT_COMPLETED,
                            PaymentCompletedEvent.from(payment, journal));

                    if (payment.getOrder().onPaymentConfirmed() == OrderTransitionResult.CONFLICT) {
                        recordOrderAnomaly(payment);
                    }
                });
    }

    /**
     * 구독 청구 승인 확정(W5D1). {@link #applyApproved} 의 <b>형제</b>다 — 분기문이 아니라
     * {@code dispatch} 의 부수효과 seam 에 다른 람다를 끼운다.
     *
     * <p>주문 결제의 부수효과 셋 중 둘이 여기선 다르고 하나는 없다:
     * <ul>
     *   <li>분개 → {@code recordSubscriptionCharge}(2줄, 안분 없음)</li>
     *   <li>주문 전이 → 주기 전이({@code BillingCycle#onPaymentSucceeded})</li>
     *   <li>아웃박스 → <b>없다</b>. {@code SUBSCRIPTION_CHARGED} 이벤트는 미구현이다(아래 참고)</li>
     * </ul>
     *
     * <p><b>★ 미구현</b>: 결정은 났다 — {@code PaymentCompletedEvent} 를 재사용하지 않고
     * 별도 타입을 만든다({@code sellers} 배열에 넣으면 {@code payable=0, fee=전액} 이 되어
     * <i>"판매에서 난 수수료"</i> 로 읽힌다). 발행이 빠진 동안 구독 청구는 장부에는 남지만
     * 토픽에는 안 나간다. 지금 그 비대칭의 실질 영향이 0 인 이유는 <b>{@code payment.completed}
     * 를 읽는 소비자가 애초에 없기 때문</b>이다 — 코드베이스의 유일한 {@code @KafkaListener} 는
     * {@code settlement.refund.requested} 하나다(W5D1 실측).
     */
    public TransitionResult applySubscriptionApproved(UUID merchantPaymentId, String pgTransactionId) {
        return dispatch(merchantPaymentId, pgTransactionId,
                Payment::onApproved,
                payment -> {
                    Journal journal = ledgerService.recordSubscriptionCharge(payment);
                    payment.linkJournal(journal);

                    if (payment.getBillingCycle().onPaymentSucceeded()
                            == SubscriptionTransitionResult.CONFLICT) {
                        recordCycleAnomaly(payment);
                    }
                });
    }

    /**
     * 구독 청구 시도 실패, <b>재시도가 남았다</b>(W5D1).
     *
     * <p>{@code nextRetryAt} 을 인자로 받는 이유: 간격 규칙(1일→3일→7일)은 정책이고,
     * 엔티티는 시도 횟수를 모른다({@code attempt_count} 를 두지 않은 결과). 정책은 W5D2
     * {@code DunningScheduler} 가 소유한다 — 여기는 배관이다.
     *
     * <p><b>이 메서드가 성립하는 근거가 시도 층이다.</b> {@code BillingCycle.onPaymentFailed}
     * 는 중복 통보를 걸러낼 수 없는데(상태만으로 "새 실패"와 "재통보"를 못 가름), 그 걸러내기를
     * {@code Payment::onDeclined} 가 먼저 한다 — 이미 {@code FAILED} 인 시도 행에 같은 통보가
     * 또 오면 {@code IGNORED} 라서 아래 람다가 아예 안 불린다.
     */
    public TransitionResult applySubscriptionDeclined(UUID merchantPaymentId,
                                                      String pgTransactionId,
                                                      OffsetDateTime nextRetryAt) {
        return dispatch(merchantPaymentId, pgTransactionId,
                Payment::onDeclined,
                payment -> {
                    if (payment.getBillingCycle().onPaymentFailed(nextRetryAt)
                            == SubscriptionTransitionResult.CONFLICT) {
                        recordCycleAnomaly(payment);
                    }
                });
    }

    /**
     * 구독 청구 시도 실패, <b>재시도 상한을 넘겼다</b>(W5D1). 주기를 포기한다.
     *
     * <p>{@link #applySubscriptionDeclined} 와 갈라둔 이유는 {@code BillingCycle
     * #onRetriesExhausted} 참고 — 인자 하나가 시각과 포기 여부를 겸하지 않게 한다.
     *
     * <p>구독 해지로 이어질지는 여기서 정하지 않는다(W5D2 미결 — {@code Subscription#cancel}
     * javadoc 의 "해지 사유 미구분"과 같은 자리).
     */
    public TransitionResult applySubscriptionExhausted(UUID merchantPaymentId, String pgTransactionId) {
        return dispatch(merchantPaymentId, pgTransactionId,
                Payment::onDeclined,
                payment -> {
                    if (payment.getBillingCycle().onRetriesExhausted()
                            == SubscriptionTransitionResult.CONFLICT) {
                        recordCycleAnomaly(payment);
                    }
                });
    }

    /** 거절 확정. 주문은 CREATED 로 남는다(미결제 쓸이·취소가 나중에 처리). */
    public TransitionResult applyDeclined(UUID merchantPaymentId, String pgTransactionId) {
        return dispatch(merchantPaymentId, pgTransactionId,
                Payment::onDeclined,
                payment -> { /* 부수효과 없음 */ });
    }

    /** 타임아웃. PENDING → UNKNOWN. 동기 경로 전용이라 pgTransactionId 가 없다. */
    public TransitionResult applyTimedOut(UUID merchantPaymentId) {
        return dispatch(merchantPaymentId, null,
                Payment::onTimedOut,
                payment -> { /* 부수효과 없음 */ });
    }

    private TransitionResult dispatch(UUID merchantPaymentId,
                                      String pgTransactionId,
                                      Function<Payment, TransitionResult> transition,
                                      Consumer<Payment> onTransitioned) {
        for (int attempt = 1; ; attempt++) {
            try {
                return txTemplate.execute(tx -> {
                    Payment payment = paymentRepository.findByMerchantPaymentId(merchantPaymentId)
                            .orElseThrow(() -> new PaymentNotFoundException(merchantPaymentId));

                    TransitionResult result = transition.apply(payment);   // 엔티티가 전이표를 판단
                    boolean linkOk = payment.linkPgTransaction(pgTransactionId);

                    if (result == TransitionResult.TRANSITIONED) {
                        onTransitioned.accept(payment);
                    }
                    if (result == TransitionResult.CONFLICT || !linkOk) {
                        recordAnomaly(payment, result, linkOk);
                    }
                    return result;   // 커밋 시 @Version 검사 → 지면 아래 catch
                });
            } catch (OptimisticLockingFailureException e) {
                if (attempt >= MAX_ATTEMPTS) throw e;
                // 다음 시도는 갱신된 행을 다시 읽는다 → 이미 종착지면 on*() 가 IGNORED 를 돌려준다.
                log.debug("낙관적 락 충돌 mpid={} attempt={} — 재조회 후 재시도", merchantPaymentId, attempt);
            }
        }
    }

    /**
     * 모순 기록. 지금은 알람 로그뿐이다 — 이상 테이블(PaymentAnomaly)은 Week 3 대사 배치와 함께 온다.
     * 그때 이 로그가 append-only 테이블 insert 로 바뀐다(W1D1 §5: 지우개가 아니라 빨간 펜).
     */
    private void recordAnomaly(Payment payment, TransitionResult result, boolean linkOk) {
        log.warn("[ANOMALY] paymentId={} status={} result={} linkConflict={} — 사람이 확인해야 함",
                payment.getId(), payment.getStatus(), result, !linkOk);
    }

    /**
     * 주문 전이 모순. 취소된 주문에 결제 승인이 확정됐다 — 재고는 이미 복원돼 남에게 갔는데
     * 돈은 받은 상태다(이중판매). 결제 쪽은 정상 전이라 recordAnomaly 가 안 잡는다.
     */
    /**
     * 주기 전이 모순(W5D1). 이미 종착지({@code PAID}·{@code ABANDONED})인 주기에 결과가 왔다.
     * 대표적으로 <b>포기 처리한 뒤 마지막 시도가 늦게 성공</b>한 경우다 — 돈은 들어왔고
     * {@code payment} 행에 {@code PAID} 로 남지만, 주기는 {@code ABANDONED} 인 채다.
     * 상태를 되돌리지 않고 어긋남만 남긴다(W1D1: 지우개가 아니라 빨간 펜).
     */
    private void recordCycleAnomaly(Payment payment) {
        BillingCycle cycle = payment.getBillingCycle();
        log.warn("[ANOMALY] billingCycleId={} cycleStatus={} paymentId={} — 종착지 주기에 청구 결과 도착. 사람이 확인해야 함",
                cycle.getId(), cycle.getStatus(), payment.getId());
    }

    private void recordOrderAnomaly(Payment payment) {
        Order order = payment.getOrder();
        log.warn("[ANOMALY] orderId={} orderStatus={} paymentId={} — 취소된 주문에 결제 승인 확정. 사람이 확인해야 함",
                order.getId(), order.getStatus(), payment.getId());
    }
}
