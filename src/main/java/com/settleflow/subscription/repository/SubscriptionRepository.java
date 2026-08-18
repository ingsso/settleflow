package com.settleflow.subscription.repository;

import com.settleflow.subscription.domain.Subscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

    /**
     * 정기 청구가 도래한 구독(W5D2 P1 폴링).
     *
     * <p>{@code idx_subscription_due}(V12:133) 가 이 조회를 위해 깔린 <b>부분 인덱스</b>다 —
     * {@code CANCELED} 행은 아예 담기지 않는다. Quartz 동적 트리거를 골랐다면 이 인덱스가
     * 죽은 인덱스가 됐을 것이고, 그게 D1 설계가 폴링을 이미 전제하고 있었다는 물증이다.
     *
     * <p><b>엔티티가 아니라 id 만 돌려준다.</b> 폴링과 청구는 다른 트랜잭션이라(PG 호출이
     * 그 사이에 있다) 여기서 읽은 엔티티는 어차피 detach 된다. 집는 쪽이 자기 트랜잭션에서
     * 다시 읽어야 하고, 그 재조회가 곧 {@link #claimBillingDate} 의 CAS 다.
     *
     * @param limit 한 번에 처리할 상한. 배치가 통째로 한 트랜잭션이 아니므로 안전 밸브에 가깝다
     */
    @Query(value = """
            SELECT id
              FROM subscription
             WHERE status = 'ACTIVE'
               AND next_billing_date <= :today
             ORDER BY next_billing_date, id
             LIMIT :limit
            """, nativeQuery = true)
    List<Long> findBillingDue(@Param("today") LocalDate today, @Param("limit") int limit);

    /**
     * 이 셀러에게 이미 활성 구독이 있나(W5D5).
     *
     * <p><b>이건 정합성 장치가 아니다.</b> 읽고 판단하고 쓰는 사이에 틈이 있어 동시 요청 둘이
     * 여기를 함께 통과한다. 막는 것은 {@code uq_subscription_active_seller}(V13)이고, 이 조회는
     * <b>친절한 409</b> 를 위한 것이다 — W1D2 가 <i>"조회 후 앱 판단"</i> 을 기각한 자리와 같은
     * 모양인데, 여기서는 <b>둘 다 둔다</b>: 정상 경로는 예외 없이 거절하고, 경합은 인덱스가 잡는다.
     */
    @Query(value = """
            SELECT EXISTS (SELECT 1
                             FROM subscription
                            WHERE seller_id = :sellerId
                              AND status    = 'ACTIVE')
            """, nativeQuery = true)
    boolean existsActiveBySeller(@Param("sellerId") Long sellerId);

    /**
     * 구독 시계를 <b>먼저 밀고 커밋한다</b> — P1 의 리스(W5D2 결정 8).
     *
     * <h3>왜 이게 배타성을 준다는 건가</h3>
     * 낙관적 락은 이 레이스를 못 막는다. 인스턴스 둘이 같은 구독을 집으면 <b>둘 다 PG 를
     * 부르고</b>, 진 쪽이 커밋에서 롤백돼도 <b>나간 돈은 안 돌아온다</b> — 롤백은 우리 DB 만
     * 되돌린다. W1D4 의 {@code dispatch()} 는 [판정 → 부수효과] 순서라 락이 편이었지만,
     * 폴링은 [부수효과 → 판정]이라 순서가 뒤집혀 있다.
     *
     * <p>그래서 <b>돈이 나가기 전에 커밋되는 사실</b>을 하나 만든다. {@code next_billing_date}
     * 를 다음 주기로 밀어 커밋하면, 진 인스턴스의 {@code WHERE next_billing_date = :expected}
     * 가 0 행을 잡고 그대로 물러난다. PG 호출은 승자만 한다.
     *
     * <p>compare-and-swap 이라 {@code reserveRefund}(W1D2)와 같은 성질이다 —
     * <b>판정과 실행이 한 문장</b>이고, 반환된 영향 행 수가 곧 "내가 이 구독을 집었는가"의 답이다.
     * 읽고 판단하고 쓰면 그 틈에서 둘 다 통과한다.
     *
     * <p>{@code version} 을 함께 올린다. 네이티브라 JPA {@code @Version} 을 안 타므로 직접
     * 올려서, 다른 트랜잭션이 들고 있는 낡은 엔티티가 조용히 덮어쓰지 못하게 한다.
     * {@code @PreUpdate} 도 안 타므로 {@code updated_at} 도 직접 갱신한다.
     *
     * @param expected 집기 직전에 읽은 청구일. 이 값이 아니면 남이 먼저 집은 것이다
     * @return 1 = 내가 집었다, 0 = 남이 먼저 집었거나 해지됐다
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE subscription
               SET next_billing_date = :next,
                   version           = version + 1,
                   updated_at        = now()
             WHERE id                = :id
               AND status            = 'ACTIVE'
               AND next_billing_date = :expected
            """, nativeQuery = true)
    int claimBillingDate(@Param("id") Long id,
                         @Param("expected") LocalDate expected,
                         @Param("next") LocalDate next);
}
