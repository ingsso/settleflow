package com.settleflow.payment.service;

import com.settleflow.ledger.domain.Journal;
import com.settleflow.ledger.service.LedgerService;
import com.settleflow.outbox.domain.EventType;
import com.settleflow.outbox.service.OutboxWriter;
import com.settleflow.payment.domain.Payment;
import com.settleflow.payment.domain.Refund;
import com.settleflow.payment.domain.RefundPgStatus;
import com.settleflow.payment.event.RefundCompletedEvent;
import com.settleflow.payment.pg.PgClient;
import com.settleflow.payment.pg.PgRefundResult;
import com.settleflow.payment.pg.PgTimeoutException;
import com.settleflow.payment.repository.PaymentRepository;
import com.settleflow.payment.repository.RefundRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 환불 Saga 의 두 번째 다리(W3D2). {@code refund.requested} 를 소비해 실제 PG 환불을 부르고,
 * 그 결과를 장부·예약·이벤트에 반영한다.
 *
 * <h3>트랜잭션 모양 — PaymentService 와 같다</h3>
 * <pre>
 *   tx0: refund 를 읽어 값만 꺼낸다 (이미 확정된 건이면 여기서 끝)
 *   ----- 트랜잭션 경계 -----
 *   PG 환불 동기 호출 (느리고 터질 수 있음)
 *   ----- 트랜잭션 경계 -----
 *   tx1: 결과 반영 (조건부 UPDATE + 역분개 + 이벤트)
 * </pre>
 * PG 호출을 tx 안에서 하면 느린 네트워크 호출이 DB 커넥션과 행 락을 붙잡는다. {@link PaymentService}
 * 가 승인 경로에서 이미 세운 규칙(W1D1 §2 함정1)을 그대로 따른다.
 *
 * <h3>세 결과, 세 처리</h3>
 * <table border="1">
 *   <tr><th>PG</th><th>장부</th><th>예약</th><th>상태</th><th>이벤트</th></tr>
 *   <tr><td>승인</td><td>역분개 INSERT</td><td>유지</td><td>COMPLETED</td><td>refund.completed</td></tr>
 *   <tr><td>거절</td><td>건드리지 않음</td><td><b>해제</b></td><td>FAILED</td><td>없음</td></tr>
 *   <tr><td>무응답</td><td>건드리지 않음</td><td><b>유지</b></td><td>UNKNOWN</td><td>없음</td></tr>
 * </table>
 *
 * <p><b>거절에 "보상 분개"가 없는 게 핵심이다.</b> 역분개를 PG 확인 뒤로 미뤘기 때문에 취소할
 * 장부 기록이 애초에 없다 — 안 적으면 지울 일도 없다. 되돌릴 건 예약뿐이고, 그건 재무 진실이
 * 아니라 메커니즘이라 append-only 규율과 부딪히지 않는다(W2D4 의 진실 vs 메커니즘 구분).
 *
 * <p><b>무응답에 예약을 풀지 않는 게 두 번째 핵심이다.</b> W1D5 에서 쓸이 배치가 재고를 성급히
 * 복원해 이중판매 구멍이 열렸고, 결론은 <i>복원은 시간의 문제가 아니라 사실 확인의 문제</i>였다.
 * 여기서 예약을 풀면 PG 는 이미 환불했는데 잔액만 되살아나 <b>이중환불</b>이 열린다. 같은 구멍이다.
 *
 * <h3>멱등</h3>
 * 아웃박스는 at-least-once 라 같은 이벤트가 두 번 온다(W3D1 §7). 방어는 두 겹이다 —
 * tx0 의 상태 확인이 fast-path(PG 를 두 번 부르지 않는다), 그리고 tx1 의 조건부 UPDATE 가
 * 최종 보증이다({@code WHERE pg_status = 'REQUESTED'} → 영향 행 0 = 이미 처리됨).
 * W1D4 에서 SETNX 가 fast-path 이고 낙관적 락이 최종 보증이었던 것과 같은 두 겹 구조.
 */
@Service
public class RefundConfirmService {

    private static final Logger log = LoggerFactory.getLogger(RefundConfirmService.class);

    private final PgClient pgClient;
    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final LedgerService ledgerService;
    private final OutboxWriter outboxWriter;
    private final TransactionTemplate txTemplate;

    public RefundConfirmService(PgClient pgClient,
                                PaymentRepository paymentRepository,
                                RefundRepository refundRepository,
                                LedgerService ledgerService,
                                OutboxWriter outboxWriter,
                                PlatformTransactionManager txManager) {
        this.pgClient = pgClient;
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
        this.ledgerService = ledgerService;
        this.outboxWriter = outboxWriter;
        this.txTemplate = new TransactionTemplate(txManager);
    }

    /**
     * 환불 1건을 PG 에 확인시키고 결과를 반영한다.
     *
     * <p>예외를 던지지 않는다 — 소비자가 이걸 받아 커밋 여부를 정하는데, PG 거절은 <b>정상적인
     * 사업 결과</b>이지 소비 실패가 아니다. 실패로 취급하면 Kafka 가 같은 이벤트를 무한 재배달한다.
     *
     * @return 반영된 최종 상태. 이미 처리된 이벤트였으면 그 건의 현재 상태를 그대로 돌려준다.
     */
    public RefundPgStatus confirm(long refundId) {
        // --- tx0: PG 를 부르기 전에 이미 확정된 건인지 본다(fast-path 멱등) ---
        Pending pending = txTemplate.execute(tx -> {
            Refund refund = refundRepository.findById(refundId)
                    .orElseThrow(() -> new IllegalArgumentException("no refund for id=" + refundId));
            return new Pending(refund.getPayment().getId(), refund.getAmount(), refund.getPgStatus());
        });

        if (pending.status() != RefundPgStatus.REQUESTED) {
            // 중복 배달. PG 를 다시 부르면 돈이 두 번 나갈 수 있으므로 여기서 끊는 게 중요하다.
            log.debug("이미 확정된 환불 refundId={} status={} — 건너뜀", refundId, pending.status());
            return pending.status();
        }

        try {
            // --- 트랜잭션 밖에서 PG 호출 ---
            PgRefundResult result;
            try {
                result = pgClient.refund(refundId, pending.amount());
            } catch (PgTimeoutException e) {
                // 실패가 아니라 정보의 부재(W1D1 §3). 예약도 장부도 그대로 두고 보류한다.
                return applyUnknown(refundId, pending);
            }

            // --- tx1: 결과 반영 ---
            return switch (result) {
                case PgRefundResult.Approved approved -> applyCompleted(refundId, pending, approved.pgRefundId());
                case PgRefundResult.Declined ignored -> applyDeclined(refundId, pending);
            };
        } catch (ConcurrentRefundConfirmException e) {
            // tx1 이 롤백됐다(방금 만든 역분개 포함 — 고아 journal 이 남지 않는다). 다른 처리가
            // 이미 확정시켰다는 뜻이라 소비 실패가 아니다. 재조회한 확정 상태를 돌려준다.
            log.debug("환불 확정 경쟁에서 밀림 refundId={} — 확정된 상태를 재조회", refundId);
            return currentStatus(refundId);
        }
    }

    private RefundPgStatus currentStatus(long refundId) {
        return txTemplate.execute(tx ->
                refundRepository.findById(refundId).orElseThrow().getPgStatus());
    }

    /**
     * PG 승인 → 역분개를 쓰고 COMPLETED. 예약은 그대로 둔다 — 예약이 그대로 <b>실제 환불액</b>이 된다.
     *
     * <p>역분개 저장과 상태 확정이 <b>같은 tx</b> 다. 갈라지면 장부엔 역분개가 있는데 환불은
     * REQUESTED 로 남아(V7 {@code chk_refund_journal_matches_status} 위반) 재배달 시 PG 를 또 부른다.
     */
    private RefundPgStatus applyCompleted(long refundId, Pending pending, String pgRefundId) {
        return txTemplate.execute(tx -> {
            Payment payment = paymentRepository.findById(pending.paymentId()).orElseThrow();
            Refund refund = refundRepository.findById(refundId).orElseThrow();

            // 역분개(W2D5 안분·뺄셈). 이제서야 장부에 적을 사실이 생겼다.
            Journal journal = ledgerService.recordRefund(payment, pending.amount());

            if (refundRepository.markCompleted(refundId, journal.getId(), pgRefundId) == 0) {
                // 동시에 다른 소비자가 확정시켰다. 방금 만든 journal 이 고아로 남으면 안 되므로
                // tx 통째로 롤백한다 — Kafka 가 재배달하면 tx0 의 fast-path 가 걸러낸다.
                throw new ConcurrentRefundConfirmException(refundId);
            }

            // refund.completed 를 같은 tx 에 append. fat 스냅샷은 방금 만든 journal 을 거울처럼
            // 읽는다(W3D1 §4) — 이벤트와 장부가 갈라질 수 없다.
            outboxWriter.append(pending.paymentId(), EventType.REFUND_COMPLETED,
                    RefundCompletedEvent.from(payment, refund, journal, pgRefundId));
            return RefundPgStatus.COMPLETED;
        });
    }

    /**
     * PG 거절 → 예약을 풀고 FAILED. <b>장부는 건드리지 않는다</b> — 안 적었으니 지울 것도 없다.
     * 이게 "보상 트랜잭션"의 자리인데, 역분개를 미룬 덕에 보상할 장부 기록이 없어 예약 해제만 남았다.
     */
    private RefundPgStatus applyDeclined(long refundId, Pending pending) {
        return txTemplate.execute(tx -> {
            if (refundRepository.markFailed(refundId) == 0) {
                throw new ConcurrentRefundConfirmException(refundId);
            }
            // 예약 해제. 상태 전이가 먼저 성공했으므로 이 해제는 정확히 한 번만 일어난다.
            if (paymentRepository.releaseRefundedAmount(pending.paymentId(), pending.amount()) == 0) {
                // 언더플로 — 예약한 적 없는 금액을 풀려 했다. 앱의 짝이 안 맞는다(프로그래밍 오류).
                throw new IllegalStateException(
                        "예약 해제 실패 refundId=%d paymentId=%d amount=%d"
                                .formatted(refundId, pending.paymentId(), pending.amount()));
            }
            log.info("PG 환불 거절 refundId={} — 예약 {}원 해제, 장부 미기록", refundId, pending.amount());
            return RefundPgStatus.FAILED;
        });
    }

    /**
     * PG 무응답 → UNKNOWN 으로 <b>보류</b>. 예약도 장부도 그대로다.
     *
     * <p>이건 종착지가 아니다. 진실을 채우려면 PG 에 재조회하는 쓸이 배치가 필요한데 아직 없다
     * (W3D2 이월). 그때까지 이 환불의 예약분은 잔액에서 계속 잡혀 있다 — 고객이 그만큼 재환불을
     * 못 한다. 이중환불보다 이쪽이 안전한 실패라 택했다.
     */
    private RefundPgStatus applyUnknown(long refundId, Pending pending) {
        return txTemplate.execute(tx -> {
            if (refundRepository.markUnknown(refundId) == 0) {
                throw new ConcurrentRefundConfirmException(refundId);
            }
            log.warn("[UNKNOWN] PG 환불 무응답 refundId={} paymentId={} amount={} — 예약 유지. 사람/배치가 확인해야 함",
                    refundId, pending.paymentId(), pending.amount());
            return RefundPgStatus.UNKNOWN;
        });
    }

    /** tx 밖으로 들고 나갈 값. PG 호출이 tx 밖이라 엔티티가 아니라 값만 꺼낸다({@link PaymentService} 와 같은 이유). */
    private record Pending(long paymentId, long amount, RefundPgStatus status) {
    }

    /**
     * 조건부 UPDATE 가 0 행을 받았다 — 이 이벤트를 다른 처리가 먼저 확정시켰다.
     * tx 를 롤백시켜 (방금 만든 역분개를 포함해) 아무것도 남기지 않는 게 목적이다.
     */
    static class ConcurrentRefundConfirmException extends RuntimeException {
        ConcurrentRefundConfirmException(long refundId) {
            super("환불이 이미 확정됨 refundId=" + refundId);
        }
    }
}
