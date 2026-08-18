package com.settleflow.subscription.api;

import com.settleflow.subscription.domain.Subscription;
import com.settleflow.subscription.repository.SubscriptionRepository;
import com.settleflow.subscription.service.ActiveSubscriptionExistsException;
import com.settleflow.subscription.service.SubscriptionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

/**
 * 구독 입구(W5D5). 이게 생기기 전까지 <b>구독은 앱 밖에서 만들 수 없었다</b> — D1 이 엔티티를,
 * D2 가 스케줄러를 만들어놓고 생성 경로만 D3→D4→D5 로 네 번 밀렸다.
 * {@code RefundController} 가 <i>"Week 2 산출물이 앱 밖에서 도달 불가능한 상태"</i> 를 푼 것과
 * 같은 자리이고, 이번엔 그 미룸이 <b>k6 시나리오 ③ 실행을 막고 있었다.</b>
 *
 * <h3>201 은 결과가 아니라 접수증이다</h3>
 * 첫 청구는 여기서 하지 않는다({@link SubscriptionService} javadoc). 응답의
 * {@code nextBillingDate} 는 <b>오늘</b>이고, 실제 청구는 다음 P1 폴링이 집어서 한다.
 * 그래서 이 응답에는 결제 상태가 없다 — {@code POST /orders} 가 {@code orderStatus} 를
 * 실어주는 것과 다른 점이고, 그 차이가 곧 <b>청구 경로를 하나로 유지한 대가</b>다.
 *
 * <h3>HTTP 매핑</h3>
 * <pre>
 *   생성                     201  { subscriptionId, sellerId, amount, intervalDays, nextBillingDate, status }
 *   없는 셀러                404  요청이 가리키는 리소스가 없다
 *   이미 활성 구독이 있음     409  요청이 지금 성립하지 않는다(재고 부족=409 와 같은 결)
 *   └ 동시 요청의 경합       409  같은 뜻이라 같은 상태다. 다만 판정한 주체가 앱이 아니라 인덱스다
 * </pre>
 * 마지막 줄이 이 컨트롤러에서 유일하게 특이한 자리다 — <b>같은 거절을 두 곳이 낸다.</b>
 * 정상 경로는 서비스가 조회로 걸러 예외를 만들고, 경합은 커밋 시점에
 * {@code uq_subscription_active_seller}(V13)가 걸러 {@link DataIntegrityViolationException} 이
 * 된다. 호출자에게는 구분할 이유가 없어 같은 409 로 접는다.
 */
@RestController
@RequestMapping("/subscriptions")
public class SubscriptionController {

    private final SubscriptionService subscriptionService;
    private final SubscriptionRepository subscriptionRepository;

    public SubscriptionController(SubscriptionService subscriptionService,
                                  SubscriptionRepository subscriptionRepository) {
        this.subscriptionService = subscriptionService;
        this.subscriptionRepository = subscriptionRepository;
    }

    @PostMapping
    public ResponseEntity<CreateSubscriptionResponse> create(
            @Valid @RequestBody CreateSubscriptionRequest req) {

        Long id = subscriptionService.create(req.sellerId(), req.amount(), req.intervalDays());

        Subscription created = subscriptionRepository.findById(id).orElseThrow();
        return ResponseEntity.status(HttpStatus.CREATED).body(new CreateSubscriptionResponse(
                created.getId(),
                created.getSeller().getId(),
                created.getAmount(),
                created.getIntervalDays(),
                created.getNextBillingDate(),
                created.getStatus().name()
        ));
    }

    /** 없는 셀러 — 구독 판정 밖의 리소스 문제라 404({@code RefundController} 와 같은 태도). */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> sellerNotFound(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "SELLER_NOT_FOUND", "message", e.getMessage()));
    }

    /** 앱이 조회로 걸러낸 중복. */
    @ExceptionHandler(ActiveSubscriptionExistsException.class)
    public ResponseEntity<Map<String, Object>> alreadyActive(ActiveSubscriptionExistsException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "ACTIVE_SUBSCRIPTION_EXISTS", "sellerId", e.getSellerId()));
    }

    /**
     * 인덱스가 걸러낸 중복 — 위와 <b>같은 사실</b>이고 판정한 주체만 다르다.
     *
     * <p>여기로 오는 경로는 동시 요청 둘이 서비스의 사전 확인을 함께 통과한 경우뿐이다.
     * 그 틈은 앱 코드로 못 막으므로(W1D2), 막는 대신 <b>같은 답으로 접는다.</b>
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> raceLostToIndex(DataIntegrityViolationException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "ACTIVE_SUBSCRIPTION_EXISTS"));
    }

    public record CreateSubscriptionRequest(@NotNull Long sellerId,
                                            @Positive long amount,
                                            @Positive int intervalDays) {
    }

    /**
     * {@code nextBillingDate} 는 <b>오늘</b>이다. 이 응답을 받은 시점에 청구는 아직 안 나갔고,
     * 다음 P1 폴링이 집어서 보낸다 — 결과는 {@code billing_cycle} 과 {@code payment} 에 남는다.
     */
    public record CreateSubscriptionResponse(Long subscriptionId, Long sellerId,
                                             Long amount, Integer intervalDays,
                                             LocalDate nextBillingDate, String status) {
    }
}
