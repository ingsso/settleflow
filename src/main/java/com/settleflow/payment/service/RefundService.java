package com.settleflow.payment.service;

import com.settleflow.ledger.domain.Journal;
import com.settleflow.ledger.service.LedgerService;
import com.settleflow.payment.domain.Payment;
import com.settleflow.payment.domain.PaymentStatus;
import com.settleflow.payment.domain.Refund;
import com.settleflow.payment.domain.RefundResult;
import com.settleflow.payment.repository.PaymentRepository;
import com.settleflow.payment.repository.RefundRepository;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 환불 요청 판정(W2D4). 요청을 받아줄지 결정하고, 받아주면 금액 누적 + {@code refund} 행을 남긴다.
 *
 * <p><b>W2D5 부터 장부까지 같은 트랜잭션에서</b> 움직인다: 금액 누적(UPDATE) + {@code refund} 행
 * INSERT + 역분개 journal INSERT 가 한 덩어리다. 갈라지면 돈·이력·장부가 드리프트한다.
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
    private final LedgerService ledgerService;
    private final EntityManager em;

    public RefundService(PaymentRepository paymentRepository,
                         RefundRepository refundRepository,
                         LedgerService ledgerService,
                         EntityManager em) {
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
        this.ledgerService = ledgerService;
        this.em = em;
    }

    /**
     * 부분·전액 환불 1건을 반영한다.
     *
     * <p>금액 누적(UPDATE)과 {@code refund} 행 INSERT 가 <b>한 트랜잭션</b>이다 — 갈라지면
     * {@code payment.refunded_amount} 와 환불 이력이 드리프트한다({@link Refund} 클래스 주석).
     *
     * @return {@link RefundResult#ACCEPTED} 또는 거부 사유. 예상되는 입력이므로 예외를 던지지 않는다.
     */
    @Transactional
    public RefundResult requestRefund(Long paymentId, long amount) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("no payment for id=" + paymentId));

        // --- 앱 가드: 이 결제가 환불 사건을 받을 자격이 있나 ---
        if (payment.getStatus() == PaymentStatus.UNKNOWN) {
            return RefundResult.PAYMENT_UNKNOWN;   // 조회하면 될 수도 있다 — 아래와 구분
        }
        if (payment.getStatus() != PaymentStatus.PAID) {
            return RefundResult.NOT_REFUNDABLE;    // PENDING·FAILED — 재시도해도 안 바뀐다
        }
        if (amount <= 0) {
            return RefundResult.INVALID_AMOUNT;
        }

        // --- SQL 가드: 판정과 실행이 한 문장. 영향 행 수가 곧 판정이다 ---
        if (paymentRepository.addRefundedAmount(paymentId, amount) == 0) {
            return RefundResult.EXCEEDS_REMAINING;
        }

        // 네이티브 UPDATE 는 영속성 컨텍스트를 모른다 — 새로고침해야 refundStatus() 가 최신을 본다.
        em.refresh(payment);

        // 역분개(W2D5). 위 UPDATE 가 1행을 갱신했다는 건 "이 환불이 실제로 일어났다"는 판정이고,
        // 그 직후가 journal 이 붙을 자리다. 결제처럼 멱등 지점에 얹지 않는 이유는 환불엔 멱등이
        // 없기 때문 — 같은 금액 두 번은 진짜 환불 2건이라 journal 도 2개가 정답이다(위 javadoc).
        Journal journal = ledgerService.recordRefund(payment, amount);
        refundRepository.save(new Refund(payment, amount, journal));
        return RefundResult.ACCEPTED;
    }
}
