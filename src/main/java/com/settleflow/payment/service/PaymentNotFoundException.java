package com.settleflow.payment.service;

import java.util.UUID;

/**
 * 웹훅/조회가 가리키는 merchant_payment_id 로 결제를 못 찾았다.
 * 정상 흐름에선 안 나온다 — 위조 웹훅이거나 버그다. 컨트롤러가 잡아 기록하고 200 OK 로 응답한다.
 */
public class PaymentNotFoundException extends RuntimeException {

    public PaymentNotFoundException(UUID merchantPaymentId) {
        super("no payment for merchantPaymentId=" + merchantPaymentId);
    }
}
