package com.settleflow.payment.api;

import com.settleflow.payment.domain.Refund;
import com.settleflow.payment.domain.RefundOutcome;
import com.settleflow.payment.service.RefundService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 환불 입구(W2 점검, A). Week 2 산출물(가드·역분개)이 여기 생기기 전까지 <b>앱 밖에서 도달 불가능</b>했다 —
 * {@code RefundService.requestRefund} 를 부르는 프로덕션 코드가 테스트뿐이었다. W1D5 가 로컬 실행용
 * {@code POST /orders} 를 만든 것과 같은 자리.
 *
 * <h3>{@link RefundOutcome} → HTTP 매핑</h3>
 * <pre>
 *   Accepted                    201  { refundId, amount, refundStatus, remainingRefundable }
 *   Rejected(INVALID_AMOUNT)    400  (실제론 @Positive 가 먼저 끊음 — 아래 분기는 백스톱)
 *   Rejected(NOT_REFUNDABLE)    409  영영 안 됨 — 결제의 현재 상태와 요청이 충돌(OrderController 재고부족=409 와 같은 결)
 *   Rejected(EXCEEDS_REMAINING) 409  잔액 초과 — reserved_refund_amount 라는 현재 자원 상태에 걸린 거절
 *   Rejected(PAYMENT_UNKNOWN)   503  정보의 부재(W1D1 §3) — "충돌"이 아니라 상류(PG) 진실 미확정. Retry-After 로
 *                                    "잠시 후 다시"를 전달한다. 재시도 성격이 정반대라 위 409 들과 상태로 구분한다.
 * </pre>
 *
 * <p><b>200 이 아니라 상황별 상태.</b> 웹훅은 PG 재전송을 멈추려 항상 200 이지만(W1D1 §4), 여기 호출자는
 * <b>사람</b>이라 거부는 거부라고 알려줘야 한다({@link OrderController} 와 같은 판단).
 */
@RestController
@RequestMapping("/payments/{id}/refunds")
public class RefundController {

    /** PAYMENT_UNKNOWN 재시도 안내(초). 상류 상태가 확정될 시간을 클라에 준다. */
    private static final String RETRY_AFTER_SECONDS = "5";

    private final RefundService refundService;

    public RefundController(RefundService refundService) {
        this.refundService = refundService;
    }

    @PostMapping
    public ResponseEntity<?> requestRefund(@PathVariable Long id,
                                           @Valid @RequestBody RefundRequest req) {
        RefundOutcome outcome = refundService.requestRefund(id, req.amount());

        return switch (outcome) {
            case RefundOutcome.Accepted a -> {
                Refund r = a.refund();
                // 두 값은 서비스가 자기 tx 안에서 계산해 넘긴다 — 컨트롤러가 리포지토리를 만지지
                // 않게(그리고 tx 밖 조회가 생기지 않게). 왜 하나가 아니라 둘인지는 Accepted javadoc.
                yield ResponseEntity.status(HttpStatus.CREATED).body(new RefundResponse(
                        r.getId(),
                        r.getAmount(),
                        a.refundStatus().name(),
                        a.remainingRefundable()));
            }
            case RefundOutcome.Rejected rej -> switch (rej.reason()) {
                case INVALID_AMOUNT -> error(HttpStatus.BAD_REQUEST, "INVALID_AMOUNT");
                case NOT_REFUNDABLE -> error(HttpStatus.CONFLICT, "NOT_REFUNDABLE");
                case EXCEEDS_REMAINING -> error(HttpStatus.CONFLICT, "EXCEEDS_REMAINING");
                case PAYMENT_UNKNOWN -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
                        .body(Map.of("error", "PAYMENT_UNKNOWN"));
            };
        };
    }

    /** 존재하지 않는 결제 id — 환불 판정 밖의 리소스 문제라 404. (RefundService 가 던지는 유일한 예외.) */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> paymentNotFound(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "PAYMENT_NOT_FOUND", "message", e.getMessage()));
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String code) {
        return ResponseEntity.status(status).body(Map.of("error", code));
    }

    /** {@code @Positive} 가 0·음수를 서비스 도달 전에 400 으로 끊는다 — INVALID_AMOUNT 의 친절한 앞단. */
    public record RefundRequest(@Positive long amount) {
    }

    /**
     * 접수증이다 — 결과가 아니다(W3D2 부터 환불은 Saga 라 여기선 아직 아무것도 확정되지 않았다).
     *
     * <p><b>두 값이 서로 다른 축을 본다</b>(W3D4 결정 3-다). 20,000 결제에 20,000 을 요청한 직후:
     * <pre>
     *   refundStatus        = "NOT_REFUNDED"   확정 기준 — PG 를 아직 안 불렀다
     *   remainingRefundable = 0                예약 기준 — 이미 다 잡혔다
     * </pre>
     * 모순처럼 읽히지만 둘 다 참이고, 이 조합이 <b>바로 다음 요청이 왜 409(EXCEEDS_REMAINING)인지</b>를
     * 응답만 보고 알 수 있게 한다. 라벨 하나만 주면 "환불 없음"을 보고도 거부당한다.
     *
     * <p>{@code remainingRefundable} 은 <b>안내지 보증이 아니다</b> — 읽은 뒤 다음 요청 사이에 다른
     * 환불이 끼어들 수 있다. 정합성은 언제나 {@code reserveRefund} 한 문장이 지킨다(W1D2).
     */
    public record RefundResponse(Long refundId, Long amount,
                                 String refundStatus, long remainingRefundable) {
    }
}
