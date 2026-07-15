package com.settleflow.payment.pg;

/**
 * PG 동기 호출이 시간 안에 답하지 않았다.
 *
 * 실패가 아니다 — PG 가 승인을 끝냈는데 응답만 못 온 것일 수 있다.
 * 이 예외를 받은 쪽은 결제를 FAILED 가 아니라 UNKNOWN 으로 두고,
 * 나중에 웹훅 또는 조회로 진실을 채운다(W1D1 §3, 전이표 PENDING+타임아웃 → UNKNOWN).
 */
public class PgTimeoutException extends RuntimeException {

    public PgTimeoutException(String message) {
        super(message);
    }
}
