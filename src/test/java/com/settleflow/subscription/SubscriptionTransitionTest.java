package com.settleflow.subscription;

import com.settleflow.seller.domain.Seller;
import com.settleflow.subscription.domain.Subscription;
import com.settleflow.subscription.domain.SubscriptionHealth;
import com.settleflow.subscription.domain.SubscriptionStatus;
import com.settleflow.subscription.domain.SubscriptionTransitionResult;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * W5D1 — {@link Subscription} 계약 축과 파생 라벨({@link SubscriptionHealth}).
 *
 * <p>계약 축은 {@code ACTIVE → CANCELED} 하나뿐이라 전이표가 두 칸이다. <b>칸이 적은 게
 * 성과다</b> — 되돌아오는 축(연체)을 {@code billing_cycle} 로 내린 결과이고,
 * 그래서 CONFLICT 칸이 아예 생기지 않는다.
 */
class SubscriptionTransitionTest {

    private static final LocalDate FIRST_BILLING = LocalDate.of(2026, 8, 1);

    // ── 계약 축: 2칸 ────────────────────────────────────────────────────

    @Test
    void ACTIVE_에_해지가_오면_전이한다() {
        Subscription subscription = newSubscription();

        assertThat(subscription.cancel()).isEqualTo(SubscriptionTransitionResult.TRANSITIONED);

        assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.CANCELED);
        assertThat(subscription.getCanceledAt()).isNotNull();
        // chk_subscription_canceled_shape 가 DB 에서 요구하는 모양을 엔티티도 지키는지
        assertThat(subscription.getNextBillingDate()).isNull();
    }

    @Test
    void CANCELED_에_해지가_또_오면_무시한다() {
        Subscription subscription = newSubscription();
        subscription.cancel();
        var 처음_해지시각 = subscription.getCanceledAt();

        assertThat(subscription.cancel()).isEqualTo(SubscriptionTransitionResult.IGNORED);

        assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.CANCELED);
        // 멱등이란 "또 불려도 안전"이 아니라 "또 불려도 안 바뀜"이다 — 해지 시각이 밀리면 안 된다
        assertThat(subscription.getCanceledAt()).isEqualTo(처음_해지시각);
    }

    // ── 파생 라벨: 저장하지 않는 축 ──────────────────────────────────────

    @Test
    void 미납_주기가_없으면_CURRENT() {
        assertThat(newSubscription().health(0)).isEqualTo(SubscriptionHealth.CURRENT);
    }

    @Test
    void 미납_주기가_있으면_PAST_DUE() {
        assertThat(newSubscription().health(1)).isEqualTo(SubscriptionHealth.PAST_DUE);
    }

    /**
     * 정의역 밖은 조용한 거짓말 대신 예외다. 해지된 구독은 미납 주기가 0 이라 태연히
     * {@code CURRENT} 를 돌려줄 텐데, 사실이 아니라 <b>질문이 성립하지 않는</b> 것이다
     * ({@code Payment.refundStatus} 가 PAID 밖에서 던지는 것과 같은 구조).
     */
    @Test
    void 해지된_구독에_연체를_물으면_던진다() {
        Subscription subscription = newSubscription();
        subscription.cancel();

        assertThatThrownBy(() -> subscription.health(0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ACTIVE");
    }

    @Test
    void 미납_주기_수가_음수면_던진다() {
        assertThatThrownBy(() -> SubscriptionHealth.of(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── 청구일 이동 ─────────────────────────────────────────────────────

    @Test
    void 청구일을_옮긴다() {
        Subscription subscription = newSubscription();
        LocalDate 다음 = FIRST_BILLING.plusDays(30);

        subscription.moveBillingDateTo(다음);

        assertThat(subscription.getNextBillingDate()).isEqualTo(다음);
    }

    @Test
    void 해지된_구독의_청구일은_못_옮긴다() {
        Subscription subscription = newSubscription();
        subscription.cancel();

        assertThatThrownBy(() -> subscription.moveBillingDateTo(FIRST_BILLING.plusDays(30)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(subscription.getNextBillingDate()).isNull();
    }

    private static Subscription newSubscription() {
        return new Subscription(new Seller("구독 셀러"), 10_000L, 30, FIRST_BILLING);
    }
}
