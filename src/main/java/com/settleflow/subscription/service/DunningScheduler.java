package com.settleflow.subscription.service;

import com.settleflow.subscription.repository.BillingCycleRepository;
import com.settleflow.subscription.repository.SubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Dunning 폴러 둘(W5D2). <b>무엇을 집을지</b>만 정하고, 집은 것 하나의 처리는
 * {@link SubscriptionChargeService} 가 한다.
 *
 * <h3>왜 Quartz 가 아니라 폴링인가 — 기술결정서를 뒤집었다</h3>
 * {@code docs/SettleFlow_기술결정.md:47-54} 는 <i>"@Scheduled 대신 Quartz"</i> 를 두 근거로
 * 정해뒀다. 그 중 하나(<i>"서버가 재시작해도 예약이 유지된다"</i>)는 D1 이 이미 다른 방법으로
 * 충족했다 — {@code billing_cycle.next_retry_at} 이 <b>행에 있는 사실</b>이라 재시작과 무관하다.
 * 남은 근거(동적 트리거)를 위해 Quartz 를 얹으면 <i>"다음 시도는 언제인가"</i> 의 원천이
 * <b>주기 행과 QRTZ 트리거 둘</b>이 된다. 시계가 둘이 되는 대가가 더 크다.
 *
 * <p>물증은 스키마에 있다: D1 이 깔아둔 {@code idx_subscription_due}(V12:133) 와
 * {@code idx_billing_cycle_retry_due}(V12:218) 는 <b>둘 다 폴링 조회를 위한 부분 인덱스</b>다.
 * Quartz 를 골랐다면 둘 다 죽은 인덱스가 된다. 대가로 잃은 것은 정밀도인데, 간격이
 * 1일·3일·7일이라 폴링 주기만큼의 지연은 뜻이 없다.
 *
 * <h3>폴러가 둘인 이유 — 시계가 둘이라서</h3>
 * <pre>
 *   P1  subscription.next_billing_date  (날짜, 구독 단위)  → 정기 청구 개시 + 열린 주기 회수
 *   P2  billing_cycle.next_retry_at     (시각, 주기 단위)  → 실패 주기 재시도
 * </pre>
 * 축이 다르다(날짜/시각, 구독/주기). 하나로 합치면 한 쿼리가 두 질문을 겸하게 된다.
 * <b>둘이 같은 주기를 노리지 않는다</b>: P2 는 {@code FAILED} 만, P1 의 회수는 {@code PENDING}
 * 만 본다.
 *
 * <h3>이 클래스에 배타성 장치가 없는 이유</h3>
 * 인스턴스가 여럿이면 둘 다 같은 행을 <b>읽는다</b>. 막지 않는다 — 배타성은 읽기가 아니라
 * <b>집기</b>에서 CAS 로 확보되고(리스), 진 쪽은 0 행을 잡고 조용히 물러난다.
 * ShedLock 류로 단일 실행을 강제하면 방어는 되지만 계획서 W5D5 가 재려는 <i>"동시에 여러
 * 재시도 트리거"</i> 시나리오 자체가 성립하지 않게 된다.
 */
@Component
public class DunningScheduler {

    private static final Logger log = LoggerFactory.getLogger(DunningScheduler.class);

    /**
     * 한 번에 집을 상한. 배치 전체가 한 트랜잭션이 아니므로(건마다 tx1/tx2) 정합성 장치가
     * 아니라 안전 밸브다 — 한 폴링이 PG 를 무한정 두드리지 않게 한다.
     */
    private static final int BATCH_LIMIT = 100;

    private final SubscriptionRepository subscriptionRepository;
    private final BillingCycleRepository billingCycleRepository;
    private final SubscriptionChargeService chargeService;

    public DunningScheduler(SubscriptionRepository subscriptionRepository,
                            BillingCycleRepository billingCycleRepository,
                            SubscriptionChargeService chargeService) {
        this.subscriptionRepository = subscriptionRepository;
        this.billingCycleRepository = billingCycleRepository;
        this.chargeService = chargeService;
    }

    /** P1 진입점. 시각을 인자로 받는 공개 메서드를 따로 둬서 테스트가 결정적으로 부른다. */
    @Scheduled(fixedDelayString = "${settleflow.dunning.billing-poll-ms:60000}")
    public void pollBillingDue() {
        runBillingDue(LocalDate.now());
    }

    /** P2 진입점. */
    @Scheduled(fixedDelayString = "${settleflow.dunning.retry-poll-ms:60000}")
    public void pollRetryDue() {
        runRetryDue(OffsetDateTime.now());
    }

    /**
     * 정기 청구 도래분을 처리한다.
     *
     * @return 집어서 넘긴 구독 수. 실제로 청구가 나간 수와 다를 수 있다 —
     *         CAS 에서 지거나 상한 초과로 포기될 수 있기 때문이다
     */
    public int runBillingDue(LocalDate today) {
        List<Long> due = subscriptionRepository.findBillingDue(today, BATCH_LIMIT);
        if (!due.isEmpty()) log.info("정기 청구 도래 {}건 today={}", due.size(), today);
        due.forEach(id -> chargeService.startDueCycle(id, today));
        return due.size();
    }

    /**
     * 재시도 도래분을 처리한다.
     *
     * @return 집어서 넘긴 주기 수. 위와 같은 이유로 실제 청구 수와 다를 수 있다
     */
    public int runRetryDue(OffsetDateTime now) {
        List<Long> due = billingCycleRepository.findRetryDue(now, BATCH_LIMIT);
        if (!due.isEmpty()) log.info("재시도 도래 {}건 now={}", due.size(), now);
        due.forEach(cycleId -> chargeService.retryCycle(cycleId, now));
        return due.size();
    }
}
