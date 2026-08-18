package com.settleflow.subscription.service;

import com.settleflow.seller.domain.Seller;
import com.settleflow.seller.repository.SellerRepository;
import com.settleflow.subscription.domain.Subscription;
import com.settleflow.subscription.repository.SubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * 구독 생성(W5D5). <b>다섯 주 만에 생긴 구독의 입구다</b> — D1 이 엔티티를, D2 가 스케줄러를
 * 만들었는데 <b>아무도 구독을 만들 수 없었다</b>(D3→D4→D5 네 번 이월). W1D5 가
 * {@code POST /orders} 를, W2 점검이 {@code POST /payments/{id}/refunds} 를 만든 것과 같은 자리.
 *
 * <h3>★ 첫 청구를 여기서 하지 않는다</h3>
 * {@code next_billing_date} 를 <b>오늘로 두고 끝낸다.</b> 청구는 P1 폴러가 집어서 한다.
 *
 * <p>주문({@code OrderService.place})은 반대로 생성과 동시에 PG 를 부르는데, 여기서 그걸
 * 따라 하면 <b>청구 경로가 둘</b>이 된다. 그리고 이 경로에는 리스가 없다 — W5D2 가
 * <i>"배타성은 집는 단계에서 확보한다"</i> 로 세운 것이 <b>한쪽에서만 성립하게</b> 된다.
 * W5D3 이 <i>"형제 진입점을 만들어놓고 배선을 안 했다"</i> 로 터진 자리의 거울상이다:
 * 그때는 배선을 빠뜨린 것이고, 여기서 동기 청구를 넣으면 <b>규율이 다른 것을 배선하는</b> 것이다.
 *
 * <p>대가는 응답이 <b>청구 결과를 못 준다</b>는 것이다. 그래서 이 API 의 201 은 결과가 아니라
 * <b>접수증</b>이다({@code RefundController} 의 201 과 같은 성질).
 *
 * <h3>중복 생성 — 앱은 거절하고 인덱스가 보증한다</h3>
 * 주문에는 멱등 장치를 안 뒀는데(중복 주문은 한 번의 손해) 구독은 <b>매달 반복되는 손해</b>라
 * 갈랐다. 사전 확인은 정상 경로를 예외 없이 거절하기 위한 것이고, 동시 요청 둘이 그 틈을
 * 함께 지나가면 {@code uq_subscription_active_seller}(V13)가 잡는다.
 */
@Service
public class SubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);

    private final SellerRepository sellerRepository;
    private final SubscriptionRepository subscriptionRepository;

    public SubscriptionService(SellerRepository sellerRepository,
                               SubscriptionRepository subscriptionRepository) {
        this.sellerRepository = sellerRepository;
        this.subscriptionRepository = subscriptionRepository;
    }

    /** 실제 호출부용. 첫 청구일은 <b>오늘</b>이고, 그 정책이 사는 유일한 자리가 이 한 줄이다. */
    public Long create(Long sellerId, long amount, int intervalDays) {
        return create(sellerId, amount, intervalDays, LocalDate.now());
    }

    /**
     * 날짜를 인자로 받는 진입점 — 테스트가 결정적으로 부른다
     * ({@code DunningScheduler.runBillingDue(today)} 와 같은 모양).
     *
     * @throws IllegalArgumentException          없는 셀러. 조회는 던진다
     * @throws ActiveSubscriptionExistsException 이미 활성 구독이 있다. <b>명령의 거절은 반환값이
     *                                           원칙인데 여기선 예외다</b> — 성공 반환형이
     *                                           {@code Long} 하나뿐이라 거절을 담을 자리가 없고,
     *                                           {@code RefundOutcome} 같은 sealed 를 두기엔
     *                                           거절 사유가 하나다. 사유가 둘이 되면 그때 바꾼다
     */
    @Transactional
    public Long create(Long sellerId, long amount, int intervalDays, LocalDate firstBillingDate) {
        Seller seller = sellerRepository.findById(sellerId)
                .orElseThrow(() -> new IllegalArgumentException("없는 셀러다. sellerId=" + sellerId));

        if (subscriptionRepository.existsActiveBySeller(sellerId)) {
            throw new ActiveSubscriptionExistsException(sellerId);
        }

        Subscription subscription = new Subscription(seller, amount, intervalDays, firstBillingDate);
        // saveAndFlush 다 — 인덱스 위반을 커밋이 아니라 <b>여기서</b> 받으려는 것이다.
        // 커밋에서 터지면 예외의 종류가 트랜잭션 층에 따라 달라져 HTTP 매핑이 흔들린다.
        subscriptionRepository.saveAndFlush(subscription);

        log.info("구독 생성 subscriptionId={} sellerId={} amount={} 주기={}일 첫청구일={} — 청구는 P1 폴러가 한다",
                subscription.getId(), sellerId, amount, intervalDays, firstBillingDate);
        return subscription.getId();
    }
}
