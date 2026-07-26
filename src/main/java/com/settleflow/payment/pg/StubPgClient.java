package com.settleflow.payment.pg;

import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 인프로세스 Mock PG(W1D4). 별도 프로세스 없이 세 결과를 결정론적으로 낸다.
 *
 * <p><b>매직 금액</b>으로 결과를 고른다 — 실제 PG 샌드박스(토스 테스트 카드, Stripe 테스트 금액)의
 * 관행이다. 다만 그 규칙은 <b>이 클래스 안에만</b> 산다. {@code amount % 1000} 만 보고,
 * PaymentService·도메인은 이 금액 코드를 절대 알지 못한다 — 알게 하면 매직 금액이 온 코드에 샌다.
 *
 * <ul>
 *   <li>{@code % 1000 == 111} → 거절(DECLINED)
 *   <li>{@code % 1000 == 222} → 타임아웃(PgTimeoutException)
 *   <li>그 외 → 승인(APPROVED)
 * </ul>
 *
 * <p>환불도 <b>같은 매직 금액 규칙</b>을 쓴다(W3D2). 규칙을 하나로 두면 테스트가 "111 로 끝나는
 * 금액 = 거절"만 기억하면 되고, 승인/환불에서 서로 다른 코드를 외울 필요가 없다.
 *
 * <p>동기 응답은 여기까지다. 타임아웃 케이스의 "나중에 오는 웹훅"(UNKNOWN → PAID/FAILED)은
 * 이 스텁이 아니라 실제 HTTP 웹훅 경로로 배달된다 — 그래야 WebhookController + Redis SETNX 를
 * 진짜로 태운다(테스트가 그 엔드포인트에 POST).
 */
@Component
public class StubPgClient implements PgClient {

    static final long MAGIC_DECLINE = 111;
    static final long MAGIC_TIMEOUT = 222;

    @Override
    public PgResult approve(UUID merchantPaymentId, long amount) {
        long code = amount % 1000;
        if (code == MAGIC_TIMEOUT) {
            throw new PgTimeoutException("stub PG timeout for amount=" + amount);
        }
        if (code == MAGIC_DECLINE) {
            return PgResult.DECLINED;
        }
        return PgResult.APPROVED;
    }

    /**
     * 환불 스텁(W3D2). 승인과 같은 매직 금액 규칙 — {@code amount} 는 여기서도 <b>환불 금액</b>이다.
     * 20,000 결제에 5,111 환불을 요청하면 거절, 5,222 면 타임아웃이 난다.
     *
     * <p>환불번호는 {@code refundId} 로 결정론적으로 만든다. 랜덤이면 테스트가 값을 단언할 수 없고,
     * 대사(W3D4)가 "같은 환불엔 같은 번호"를 기대할 수 있어야 한다.
     */
    @Override
    public PgRefundResult refund(long refundId, long amount) {
        long code = amount % 1000;
        if (code == MAGIC_TIMEOUT) {
            throw new PgTimeoutException("stub PG refund timeout for amount=" + amount);
        }
        if (code == MAGIC_DECLINE) {
            return new PgRefundResult.Declined();
        }
        return new PgRefundResult.Approved("pgr-" + refundId);
    }
}
