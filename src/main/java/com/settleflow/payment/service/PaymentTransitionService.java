package com.settleflow.payment.service;

import com.settleflow.payment.domain.Payment;
import com.settleflow.payment.domain.TransitionResult;
import com.settleflow.payment.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 전이표의 backbone(W1D4 B안). 사건 하나를 결제에 반영한다.
 *
 * <p><b>엔티티가 전이표를 소유하고(on*), 이 서비스가 재시도와 부수효과를 소유한다.</b>
 * 동기 승인(pay())과 웹훅 승인이 같은 PENDING 을 동시에 전이시켜도, 낙관적 락으로 한 쪽만
 * 성공하고 진 쪽은 재조회 → 종착지 → IGNORED 로 끝난다. 부수효과(markPaid 등)는 한 번만 실행된다.
 *
 * <p>SETNX(웹훅 멱등키)는 fast-path 일 뿐이고, 정합성의 최종 보증은 여기다 — SETNX 를 빠져나온
 * 중복(동기+웹훅 겹침, 다른 eventId 로 온 재승인)도 이 층에서 걸린다("두 겹").
 */
@Service
public class PaymentTransitionService {

    private static final Logger log = LoggerFactory.getLogger(PaymentTransitionService.class);
    private static final int MAX_ATTEMPTS = 3;

    private final PaymentRepository paymentRepository;
    private final TransactionTemplate txTemplate;

    public PaymentTransitionService(PaymentRepository paymentRepository,
                                    PlatformTransactionManager txManager) {
        this.paymentRepository = paymentRepository;
        this.txTemplate = new TransactionTemplate(txManager);
    }

    /** 승인 확정. TRANSITIONED 면 주문을 PAID 로. pgTransactionId 는 웹훅이 실어온다(동기 경로는 null). */
    public TransitionResult applyApproved(UUID merchantPaymentId, String pgTransactionId) {
        return dispatch(merchantPaymentId, pgTransactionId,
                Payment::onApproved,
                payment -> payment.getOrder().markPaid());
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
}
