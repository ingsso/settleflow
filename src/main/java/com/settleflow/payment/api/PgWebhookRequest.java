package com.settleflow.payment.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * PG 가 보내는 웹훅 바디(W1D4 설계 ①).
 *
 * @param eventId           배달 1건당 1개. 멱등키(Redis SETNX). 재전송이면 같은 값이 다시 온다.
 * @param eventType         무슨 사건인가 — onApproved/onDeclined 중 뭘 부를지 정한다.
 * @param merchantPaymentId 어느 결제인가 — 우리 측 식별자로 행을 찾는다(W1D3).
 * @param pgTransactionId   PG 발급 거래번호. 이 타이밍에 채워진다. 이미 다른 값이 있으면 모순.
 * @param amount            PG 가 본 금액. 우리 DB 금액과 다르면 대사 불일치 신호(Week 3 이상 테이블 후보).
 */
public record PgWebhookRequest(
        @NotBlank String eventId,
        @NotNull PgEventType eventType,
        @NotNull UUID merchantPaymentId,
        String pgTransactionId,
        @NotNull Long amount
) {
}
