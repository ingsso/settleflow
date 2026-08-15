package com.settleflow.subscription.repository;

import com.settleflow.subscription.domain.BillingCycle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;

public interface BillingCycleRepository extends JpaRepository<BillingCycle, Long> {

    /**
     * 지금 다시 걸 실패 주기(W5D2 P2 폴링). {@code idx_billing_cycle_retry_due}(V12:218) 가
     * 이 조회를 위한 부분 인덱스다.
     *
     * <p><b>{@code status = 'FAILED'} 를 따로 안 쓴다.</b> {@code chk_billing_cycle_retry_shape}
     * 가 <i>{@code next_retry_at IS NOT NULL} ⟺ {@code FAILED}</i> 를 스키마에서 강제하므로
     * NULL 아님이 곧 상태 조건이다. 그 CHECK 가 사라지면 여기에 상태 조건을 다시 넣어야 한다
     * ({@code reserveRefund} 가 <i>"PAID 를 떠나는 전이가 생기면"</i> 을 적어둔 것과 같은 결).
     *
     * <p>{@code PENDING} 주기는 여기 안 잡힌다 — 부분 인덱스가 담지도 않는다. 첫 시도가
     * {@code UNKNOWN} 으로 끝난 주기가 그 상태로 남고, 그 회수는 P2 가 아니라
     * <b>P1(구독 시계)</b> 이 한다(결정 4). 유실은 아니지만 최대 한 주기만큼 늦는다.
     */
    @Query(value = """
            SELECT id
              FROM billing_cycle
             WHERE next_retry_at <= :now
             ORDER BY next_retry_at, id
             LIMIT :limit
            """, nativeQuery = true)
    List<Long> findRetryDue(@Param("now") OffsetDateTime now, @Param("limit") int limit);

    /**
     * 주기 시계를 <b>먼저 밀고 커밋한다</b> — P2 의 리스(W5D2 결정 8).
     * 근거는 {@code SubscriptionRepository.claimBillingDate} 와 같다.
     *
     * <h3>리스가 {@code next_retry_at} 한 칸에 얹히는데 두 뜻을 겸하는 게 아닌가</h3>
     * 아니다. 이 칸을 읽는 쪽(위 폴링 쿼리)이 던지는 질문은 하나다 — <b>"이 시각까지는 아무도
     * 손대지 마라"</b>. 정책이 정한 3일 뒤도, 리스로 밀어둔 5분 뒤도 그 문장의 두 사례일 뿐이라
     * 읽는 쪽이 <b>서로 다른 행동을 할 필요가 없다</b>. 다른 행동이 필요했다면 그건 D1 이
     * 하루 종일 걷어낸 "한 칸이 둘을 겸함"이고 갈랐어야 한다.
     *
     * <p>{@code chk_billing_cycle_retry_shape} 도 리스 중에 그대로 성립한다 —
     * 상태는 {@code FAILED} 이고 값은 NULL 이 아니다.
     *
     * <p>크래시로 리스만 남으면 그 주기는 리스 만료 후 자연히 다시 잡힌다. 정책 간격이
     * 리스 값에 덮이지만 손실이 아니다 — 그 간격은 이미 만료돼 <b>재시도 시점에 도달했기
     * 때문에</b> 집힌 것이다.
     *
     * @param now        폴링 기준 시각. 읽을 때와 같은 값이어야 CAS 가 성립한다
     * @param leaseUntil 이 시각까지 남이 못 집는다 = 청구 한 번에 걸릴 최대 시간
     * @return 1 = 내가 집었다, 0 = 남이 먼저 집었거나 그 사이 종착지로 갔다
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE billing_cycle
               SET next_retry_at = :leaseUntil,
                   version       = version + 1,
                   updated_at    = now()
             WHERE id            = :id
               AND status        = 'FAILED'
               AND next_retry_at <= :now
            """, nativeQuery = true)
    int claimForRetry(@Param("id") Long id,
                      @Param("now") OffsetDateTime now,
                      @Param("leaseUntil") OffsetDateTime leaseUntil);

    /**
     * 아직 결말이 안 난 {@code PENDING} 주기들. P1 이 회수 대상으로 긁는다(결정 4·5).
     *
     * <p>{@code FAILED} 는 여기 포함하지 않는다 — 그건 자기 시계({@code next_retry_at})가 있고
     * P2 가 집는다. <b>둘이 같은 주기를 노리지 않게 갈라둔 게 이 조건이다.</b>
     */
    @Query(value = """
            SELECT id
              FROM billing_cycle
             WHERE subscription_id = :subscriptionId
               AND status = 'PENDING'
             ORDER BY cycle_no
            """, nativeQuery = true)
    List<Long> findOpenPendingCycleIds(@Param("subscriptionId") Long subscriptionId);

    /**
     * 다음 주기 번호. 첫 주기는 1 이다.
     *
     * <p><b>여기서 뽑은 값이 유일함을 보증하지 않는다.</b> 보증은
     * {@code uq_billing_cycle_subscription_no} 가 한다(W1D2: <i>"정합성은 언제나 그 한 문장이
     * 지킨다"</i>). 이 조회는 P1 의 CAS 승자 안에서만 불리므로 정상 경로에서 충돌하지 않고,
     * 그 전제가 깨지면 DB 가 거절한다.
     */
    @Query(value = """
            SELECT COALESCE(MAX(cycle_no), 0) + 1
              FROM billing_cycle
             WHERE subscription_id = :subscriptionId
            """, nativeQuery = true)
    int nextCycleNo(@Param("subscriptionId") Long subscriptionId);
}
