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
import com.settleflow.subscription.service.DunningPolicy;
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
    private final DunningPolicy dunningPolicy;
    private final TransactionTemplate txTemplate;

    public PaymentTransitionService(PaymentRepository paymentRepository,
                                    LedgerService ledgerService,
                                    OutboxWriter outboxWriter,
                                    DunningPolicy dunningPolicy,
                                    PlatformTransactionManager txManager) {
        this.paymentRepository = paymentRepository;
        this.ledgerService = ledgerService;
        this.outboxWriter = outboxWriter;
        this.dunningPolicy = dunningPolicy;
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
        return dispatch(merchantPaymentId, pgTransactionId, Payment::onApproved, this::orderApproved);
    }

    /** {@link #applyApproved} 의 부수효과. 형제와 갈라 두고 웹훅 라우팅이 골라 쓴다(W5D3). */
    private void orderApproved(Payment payment) {
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
        return dispatch(merchantPaymentId, pgTransactionId, Payment::onApproved, this::subscriptionApproved);
    }

    /** {@link #applySubscriptionApproved} 의 부수효과. 웹훅 라우팅이 골라 쓴다(W5D3). */
    private void subscriptionApproved(Payment payment) {
        Journal journal = ledgerService.recordSubscriptionCharge(payment);
        payment.linkJournal(journal);

        if (payment.getBillingCycle().onPaymentSucceeded() == SubscriptionTransitionResult.CONFLICT) {
            recordCycleAnomaly(payment);
        }
    }

    /**
     * 구독 청구 시도가 <b>실패로 확정됐다</b>(W5D1 → W5D3 통합). 재시도를 예약하거나 주기를 포기한다.
     *
     * <h3>결말 규칙이 여기 하나뿐이다</h3>
     * W5D1 엔 {@code applySubscriptionDeclined(nextRetryAt)} 과 {@code applySubscriptionExhausted()}
     * 둘이 있었고, 둘 중 무엇을 부를지는 <b>호출부</b>가 정했다. 호출부가 폴러 하나일 땐 문제가
     * 없었는데 W5D3 에서 <b>웹훅</b>이 두 번째 호출부가 되면서 갈렸다 — 웹훅은 간격 규칙도
     * 시도 수도 모른다. 규칙을 양쪽에 복제하는 대신 <b>결말을 여기로 내렸다.</b>
     *
     * <p>{@code onPaymentFailed} / {@code onRetriesExhausted} 라는 <b>이름의 분리는 그대로</b>다
     * (W5D1 이 인자 하나가 시각과 포기 여부를 겸하지 않게 하려고 가른 것) — 갈린 자리가
     * 서비스 진입점에서 엔티티 호출로 한 칸 내려갔을 뿐이다.
     *
     * <p><b>이 메서드가 성립하는 근거는 여전히 시도 층이다.</b> {@code BillingCycle.onPaymentFailed}
     * 는 중복 통보를 걸러낼 수 없는데(상태만으로 "새 실패"와 "재통보"를 못 가름), 그 걸러내기를
     * {@code Payment::onDeclined} 가 먼저 한다 — 이미 {@code FAILED} 인 시도 행에 같은 통보가
     * 또 오면 {@code IGNORED} 라 부수효과가 아예 안 불린다.
     *
     * @param failedAt 이 시도가 실패한 시각. 백오프의 기준이다({@link DunningPolicy})
     */
    public TransitionResult applySubscriptionFailure(UUID merchantPaymentId,
                                                     String pgTransactionId,
                                                     OffsetDateTime failedAt) {
        return dispatch(merchantPaymentId, pgTransactionId,
                Payment::onDeclined,
                payment -> subscriptionFailed(payment, failedAt));
    }

    /**
     * 실패 결말 — 트랜잭션 안에서, 이 시도 행을 손에 쥔 채 정한다.
     *
     * <p>세는 시점이 <b>트랜잭션 안</b>인 게 중요하다. 밖에서 세어 시각을 인자로 받으면
     * 세기와 반영 사이에 다른 시도가 끼어들 수 있고, 낙관적 락 재시도가 돌 때 낡은 셈을
     * 그대로 다시 쓴다. 여기서 세면 재조회할 때마다 다시 센다.
     *
     * <p>{@code countConcludedAttemptsExcluding} 를 쓰는 이유는 그 메서드 javadoc 참고 —
     * 웹훅은 이미 {@code UNKNOWN} 인 시도에 도착할 수 있어서, 이번 시도를 빼고 세지 않으면
     * 한 번을 두 번 센다.
     */
    private void subscriptionFailed(Payment payment, OffsetDateTime failedAt) {
        BillingCycle cycle = payment.getBillingCycle();
        int concluded = paymentRepository
                .countConcludedAttemptsExcluding(cycle.getId(), payment.getId()) + 1;

        SubscriptionTransitionResult result = dunningPolicy.nextRetryAt(concluded, failedAt)
                .map(cycle::onPaymentFailed)
                .orElseGet(cycle::onRetriesExhausted);

        if (result == SubscriptionTransitionResult.CONFLICT) {
            recordCycleAnomaly(payment);
        }
    }

    /** 거절 확정. 주문은 CREATED 로 남는다(미결제 쓸이·취소가 나중에 처리). */
    public TransitionResult applyDeclined(UUID merchantPaymentId, String pgTransactionId) {
        return dispatch(merchantPaymentId, pgTransactionId,
                Payment::onDeclined,
                payment -> { /* 부수효과 없음 */ });
    }

    /**
     * 웹훅 승인(W5D3). <b>호출자가 결제의 종류를 모르는 유일한 경로다.</b>
     *
     * <h3>왜 형제 진입점이 아니라 여기서 고르나</h3>
     * W5D1 은 주문 결제와 구독 청구를 <i>형제 진입점</i>으로 갈랐다 — 분기문 대신. 그건
     * <b>자기가 무엇을 하는지 아는 호출자</b>를 위한 설계다. 동기 경로는 안다.
     * <b>웹훅은 정의상 모른다</b>: PG 가 우리 도메인을 알 리 없으니 바디에 종류가 실려 오지
     * 않고, 아는 건 {@code merchantPaymentId} 뿐이다.
     *
     * <p>모르는 호출자에게 알라고 요구하면 그 지식이 컨트롤러로 샌다(그리고 조회가 한 번 더
     * 는다). {@code dispatch} 는 어차피 {@code payment} 를 읽으므로, <b>읽은 자리에서 고른다.</b>
     *
     * <h3>W5D1 이 남긴 구멍이 이 자리였다</h3>
     * 어제까지 웹훅은 {@code applyApproved} 만 불렀고, 그 부수효과는
     * {@code payment.getOrder().getItems()} 를 걷는다. 구독 청구는 {@code order_id} 가 NULL 이라
     * <b>NPE 로 500 이 났다</b>. 그리고 {@code WebhookController} 는 {@code RuntimeException} 에
     * 멱등키를 지우므로 PG 가 재전송하고, 영구 결함이라 <b>매번 다시 500</b> 이 났다 —
     * 회수 실패가 아니라 무한 재전송이다. [실측 W5D3]
     */
    public TransitionResult applyApprovedFromWebhook(UUID merchantPaymentId, String pgTransactionId) {
        return dispatchSelecting(merchantPaymentId, pgTransactionId, Payment::onApproved,
                payment -> payment.isSubscriptionCharge()
                        ? this::subscriptionApproved
                        : this::orderApproved);
    }

    /**
     * 웹훅 거절(W5D3). 승인과 같은 이유로 여기서 고른다.
     *
     * <p><b>구독 쪽은 대칭이 아니다.</b> 주문 결제의 거절은 부수효과가 없지만, 구독 청구의
     * 실패는 <i>"언제 다시 걸 것인가"</i> 를 정해야 한다. 그 규칙을
     * {@link #applySubscriptionFailure} 로 내린 게 W5D3 의 결정이고, 그래서 웹훅이
     * 스케줄러 쪽 서비스를 부를 필요가 없다 — <b>결말 규칙은 한 곳</b>이고 두 경로가 같은 문을 쓴다.
     *
     * @param failedAt 웹훅이 도착한 시각을 실패 시각으로 본다. PG 가 실제로 거절한 시각은
     *                 바디에 없다 — 백오프 기준이 배달 지연만큼 밀린다는 뜻이고, 간격이
     *                 일 단위라 실질 영향이 없다고 보고 넘어간다(W5D3 이월)
     */
    public TransitionResult applyDeclinedFromWebhook(UUID merchantPaymentId, String pgTransactionId,
                                                     OffsetDateTime failedAt) {
        return dispatchSelecting(merchantPaymentId, pgTransactionId, Payment::onDeclined,
                payment -> payment.isSubscriptionCharge()
                        ? p -> subscriptionFailed(p, failedAt)
                        : p -> { /* 주문 결제의 거절은 부수효과가 없다 */ });
    }

    /** 타임아웃. PENDING → UNKNOWN. 동기 경로 전용이라 pgTransactionId 가 없다. */
    public TransitionResult applyTimedOut(UUID merchantPaymentId) {
        return dispatch(merchantPaymentId, null,
                Payment::onTimedOut,
                payment -> { /* 부수효과 없음 */ });
    }

    /** 부수효과가 호출 시점에 정해지는 경우(동기 경로 — 자기가 뭘 하는지 안다). */
    private TransitionResult dispatch(UUID merchantPaymentId,
                                      String pgTransactionId,
                                      Function<Payment, TransitionResult> transition,
                                      Consumer<Payment> onTransitioned) {
        return dispatchSelecting(merchantPaymentId, pgTransactionId, transition, payment -> onTransitioned);
    }

    /**
     * 부수효과를 <b>읽은 결제에서 고르는</b> 경우(웹훅 — 호출자가 종류를 모른다).
     *
     * <p>W5D1 이 부수효과를 {@code Consumer<Payment>} 로 파라미터화해 둔 덕에, seam 을
     * <i>값</i>에서 <i>선택자</i>로 올리는 것만으로 웹훅 라우팅이 들어왔다 —
     * 갈아끼울 자리가 설계에 이미 있었다(W4D1 §6 과 같은 성질).
     */
    private TransitionResult dispatchSelecting(UUID merchantPaymentId,
                                               String pgTransactionId,
                                               Function<Payment, TransitionResult> transition,
                                               Function<Payment, Consumer<Payment>> effectFor) {
        for (int attempt = 1; ; attempt++) {
            try {
                return txTemplate.execute(tx -> {
                    Payment payment = paymentRepository.findByMerchantPaymentId(merchantPaymentId)
                            .orElseThrow(() -> new PaymentNotFoundException(merchantPaymentId));

                    TransitionResult result = transition.apply(payment);   // 엔티티가 전이표를 판단
                    boolean linkOk = payment.linkPgTransaction(pgTransactionId);

                    if (result == TransitionResult.TRANSITIONED) {
                        effectFor.apply(payment).accept(payment);
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
