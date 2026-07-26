package com.settleflow.payment.service;

import com.settleflow.outbox.domain.EventType;
import com.settleflow.outbox.service.OutboxWriter;
import com.settleflow.payment.domain.Payment;
import com.settleflow.payment.domain.PaymentStatus;
import com.settleflow.payment.domain.Refund;
import com.settleflow.payment.domain.RefundOutcome;
import com.settleflow.payment.domain.RefundResult;
import com.settleflow.payment.event.RefundRequestedEvent;
import com.settleflow.payment.repository.PaymentRepository;
import com.settleflow.payment.repository.RefundRepository;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 환불 요청 판정(W2D4). 요청을 받아줄지 결정하고, 받아주면 금액 누적 + {@code refund} 행을 남긴다.
 *
 * <h3>W3D2 — 여기서 장부가 빠졌다</h3>
 * W2D5 에선 역분개 journal 까지 이 트랜잭션에 있었다. 요청 = 반영이었기 때문이다. 이제 실제 PG
 * 환불을 {@link RefundConfirmService} 가 부르므로, <b>여기서는 아직 아무것도 확정되지 않았다</b> —
 * 장부에 적을 사실이 없다. 선반영해두고 실패 시 보상 분개로 지우는 길도 있었지만, 그건 장부가
 * 평시에 거짓말하고 나중에 정정하는 걸 정상 경로로 만든다(W1D1 §5 — 정정은 사람의 실수를 위한
 * 도구다). 안 적으면 지울 일도 없다.
 *
 * <p><b>대신 금액 누적은 그대로 남는다.</b> 이건 이제 "환불됨"이 아니라 <b>예약</b>이다 —
 * {@link PaymentRepository#addRefundedAmount} 의 조건부 UPDATE 가 과다환불을 막는 유일한 판정이라,
 * 이것까지 미루면 잔액을 판정할 근거가 아직 없어 동시 환불이 전부 통과한다(W1D2 실측: 8,000 이
 * 11,000). W1D3 의 재고와 같은 모양 — 주문 시 원자적으로 잡아두고, 결제가 안 되면 되돌린다.
 *
 * <p>그래서 이 tx 는 <b>금액 예약 + {@code refund} 행 + {@code refund.requested} 이벤트</b> 한 덩어리다.
 * 갈라지면 돈·이력·이벤트가 드리프트한다.
 *
 * <h3>가드 분업</h3>
 * <pre>
 *   1. PENDING 환불        → 앱 (아직 돈을 안 받았다)
 *   2. FAILED 환불         → 앱 (받은 적이 없다)
 *   3. UNKNOWN 환불        → 앱 (정보의 부재 — 별도 사유로 구분)
 *   4. 0원·음수 환불        → 앱 + DB CHECK(chk_refund_amount)
 *   5. 잔액 초과           → SQL 조건부 UPDATE + DB CHECK(chk_payment_refunded_amount)
 *   6. 전액환불 후 추가환불 → 5 에 흡수됨 (별도 가드 불필요)
 * </pre>
 *
 * <p><b>1~3 은 앱만 막고 5~6 은 SQL 만 막는다</b> — 층이 겹치는 W2D3(앱 {@code assertBalanced} +
 * DB 트리거)과 달리 여기선 <b>분업</b>이다. 앱은 결제의 <i>자격</i>을, SQL 은 <i>금액</i>을 본다.
 * 4 만 양쪽에 있고, DB CHECK 쪽은 앱을 우회한 경로용 백스톱이다.
 *
 * <p><b>앱 가드가 금액을 안 보는 이유.</b> "잔액 조회 → 충분한지 판단 → UPDATE" 는 읽기와 쓰기
 * 사이의 틈에서 동시 환불이 둘 다 통과한다(W1D2 실측: 8,000 이어야 할 값이 11,000). 그래서 금액
 * 판정은 {@link PaymentRepository#addRefundedAmount} 한 문장에 맡기고, 앱은 손대지 않는다.
 * 친절한 사전 안내가 필요하면 그건 UX 계층(컨트롤러)의 일이지 정합성 가드가 아니다.
 */
@Service
public class RefundService {

    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final OutboxWriter outboxWriter;
    private final EntityManager em;

    public RefundService(PaymentRepository paymentRepository,
                         RefundRepository refundRepository,
                         OutboxWriter outboxWriter,
                         EntityManager em) {
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
        this.outboxWriter = outboxWriter;
        this.em = em;
    }

    /**
     * 부분·전액 환불 1건을 <b>접수</b>한다. 환불이 실제로 일어났다는 뜻이 아니다(W3D2) —
     * 잔액을 예약하고, 이력을 남기고, PG 를 부를 소비자에게 이벤트를 띄운다.
     *
     * <p>셋이 <b>한 트랜잭션</b>이다 — 갈라지면 예약만 잡히고 이벤트가 없어 아무도 PG 를 안 부르거나
     * (돈이 영원히 묶인다), 이벤트만 나가 예약 없이 환불이 실행된다(과다환불).
     *
     * @return 접수되면 저장된 {@link Refund}({@code pgStatus=REQUESTED}) 를 딴
     *         {@link RefundOutcome.Accepted}, 아니면 거부 사유를 담은 {@link RefundOutcome.Rejected}.
     *         예상되는 입력이므로 예외를 던지지 않는다(존재하지 않는 결제 id 만
     *         {@link IllegalArgumentException} — 그건 프로그래밍/경로 오류지 환불 판정이 아니다).
     */
    @Transactional
    public RefundOutcome requestRefund(Long paymentId, long amount) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("no payment for id=" + paymentId));

        // --- 앱 가드: 이 결제가 환불 사건을 받을 자격이 있나 ---
        if (payment.getStatus() == PaymentStatus.UNKNOWN) {
            return new RefundOutcome.Rejected(RefundResult.PAYMENT_UNKNOWN);  // 조회하면 될 수도 있다 — 아래와 구분
        }
        if (payment.getStatus() != PaymentStatus.PAID) {
            return new RefundOutcome.Rejected(RefundResult.NOT_REFUNDABLE);   // PENDING·FAILED — 재시도해도 안 바뀐다
        }
        if (amount <= 0) {
            return new RefundOutcome.Rejected(RefundResult.INVALID_AMOUNT);
        }

        // --- SQL 가드: 판정과 실행이 한 문장. 영향 행 수가 곧 판정이다 ---
        if (paymentRepository.addRefundedAmount(paymentId, amount) == 0) {
            return new RefundOutcome.Rejected(RefundResult.EXCEEDS_REMAINING);
        }

        // 네이티브 UPDATE 는 영속성 컨텍스트를 모른다 — 새로고침해야 refundStatus() 가 최신을 본다.
        em.refresh(payment);

        // 위 UPDATE 가 1행을 갱신했다는 건 "이 환불 요청을 받아준다"는 판정이다(W3D2 부터는
        // "환불이 일어났다"가 아니다 — 그건 PG 가 정한다). 그 직후가 이력을 남길 자리.
        // 결제처럼 멱등 지점에 얹지 않는 이유는 환불엔 멱등이 없기 때문 — 같은 금액 두 번은
        // 진짜 환불 2건이라 refund 행도 2개가 정답이다(위 javadoc).
        // IDENTITY 라 save() 시점에 INSERT 가 나가고 id 가 채워진다 — 이벤트가 refundId 를 실을 수 있다.
        Refund refund = refundRepository.save(new Refund(payment, amount));

        // refund.requested 를 같은 tx 에 append. thin 이다 — 아직 장부에 적힌 사실이 없어 비출
        // 거울이 없다(RefundRequestedEvent javadoc). 셀러별 내역은 refund.completed 가 싣는다.
        outboxWriter.append(payment.getId(), EventType.REFUND_REQUESTED,
                RefundRequestedEvent.from(refund));
        return new RefundOutcome.Accepted(refund);
    }
}
