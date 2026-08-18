package com.settleflow.subscription;

import com.settleflow.seller.domain.Seller;
import com.settleflow.subscription.domain.BillingCycle;
import com.settleflow.subscription.domain.BillingCycleStatus;
import com.settleflow.subscription.domain.Subscription;
import com.settleflow.subscription.domain.SubscriptionTransitionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.stream.Stream;

import static com.settleflow.subscription.domain.BillingCycleStatus.*;
import static com.settleflow.subscription.domain.SubscriptionTransitionResult.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * W5D1 전이표 실측 — {@link BillingCycle} 의 4상태 × 3사건 = <b>12칸 전부</b>.
 *
 * <p>순수 단위 테스트다(스프링·DB 없음). 전이표는 엔티티가 소유하므로 여기서 다 재진다 —
 * 배선은 {@code PaymentTransitionService} 쪽 통합테스트의 몫이다(W5D2 이후).
 *
 * <p><b>상태를 직접 주입하지 않고 전이로 만든다.</b> 세터가 없기도 하지만, 그게 더 정확하다 —
 * "FAILED 인 주기"는 실제로 실패를 겪은 주기여야 {@code nextRetryAt} 같은 곁가지까지 진짜다.
 */
class BillingCycleTransitionTest {

    private static final OffsetDateTime RETRY_AT =
            OffsetDateTime.parse("2026-08-12T03:00:00Z");
    private static final OffsetDateTime LATER_RETRY_AT =
            OffsetDateTime.parse("2026-08-15T03:00:00Z");

    /** 주기에 닿을 수 있는 사건 셋. 전이표의 열이다. */
    private enum Event {
        성공 { SubscriptionTransitionResult applyTo(BillingCycle c) { return c.onPaymentSucceeded(); } },
        실패_재시도남음 { SubscriptionTransitionResult applyTo(BillingCycle c) { return c.onPaymentFailed(LATER_RETRY_AT); } },
        실패_상한초과 { SubscriptionTransitionResult applyTo(BillingCycle c) { return c.onRetriesExhausted(); } };

        abstract SubscriptionTransitionResult applyTo(BillingCycle cycle);
    }

    /**
     * 전이표 그대로. {@code BillingCycle#onPaymentSucceeded} javadoc 의 표와 <b>같은 모양으로
     * 유지한다</b> — 표와 코드가 갈라지는 걸 이 파일이 막는다(오늘 실제로 한 번 갈라졌다:
     * {@code ABANDONED + 상한초과} 칸이 javadoc 엔 CONFLICT, 코드엔 IGNORED 였다).
     */
    static Stream<Arguments> 전이표() {
        return Stream.of(
                //          현재         사건                    기대 결과      기대 상태
                Arguments.of(PENDING,   Event.성공,             TRANSITIONED, PAID),
                Arguments.of(PENDING,   Event.실패_재시도남음,   TRANSITIONED, FAILED),
                Arguments.of(PENDING,   Event.실패_상한초과,     TRANSITIONED, ABANDONED),

                Arguments.of(FAILED,    Event.성공,             TRANSITIONED, PAID),
                Arguments.of(FAILED,    Event.실패_재시도남음,   TRANSITIONED, FAILED),   // 자기 루프
                Arguments.of(FAILED,    Event.실패_상한초과,     TRANSITIONED, ABANDONED),

                Arguments.of(PAID,      Event.성공,             IGNORED,      PAID),     // 종착지 도달
                Arguments.of(PAID,      Event.실패_재시도남음,   CONFLICT,     PAID),
                Arguments.of(PAID,      Event.실패_상한초과,     CONFLICT,     PAID),

                Arguments.of(ABANDONED, Event.성공,             CONFLICT,     ABANDONED),
                Arguments.of(ABANDONED, Event.실패_재시도남음,   CONFLICT,     ABANDONED),
                Arguments.of(ABANDONED, Event.실패_상한초과,     IGNORED,      ABANDONED) // 종착지 도달
        );
    }

    @ParameterizedTest(name = "{0} + {1} → {2} (상태 {3})")
    @MethodSource("전이표")
    void 전이표_한_칸(BillingCycleStatus 현재, Event 사건,
                   SubscriptionTransitionResult 기대결과, BillingCycleStatus 기대상태) {
        BillingCycle cycle = cycleIn(현재);

        assertThat(사건.applyTo(cycle)).isEqualTo(기대결과);
        assertThat(cycle.getStatus()).isEqualTo(기대상태);
    }

    /**
     * 표에 빈칸이 없는지를 잰다. 상태나 사건이 늘면 여기서 먼저 깨져서, 새 칸을 조용히
     * 안 적고 넘어가는 걸 막는다 — 전이표의 값은 칸을 <b>빠짐없이</b> 정하는 데 있다.
     */
    @Test
    void 표에_빈칸이_없다() {
        assertEquals((long) BillingCycleStatus.values().length * Event.values().length,
                전이표().count(),
                "전이표 행 수 = 상태 수 × 사건 수 여야 한다");
    }

    // ── 상태 라벨만으로는 안 드러나는 것들 ───────────────────────────────

    @Test
    void 실패하면_재시도_시각이_박힌다() {
        BillingCycle cycle = newCycle();

        cycle.onPaymentFailed(RETRY_AT);

        assertThat(cycle.getNextRetryAt()).isEqualTo(RETRY_AT);
    }

    /**
     * <b>자기 루프의 요점.</b> 두 번째 실패는 상태를 안 바꾸지만 재시도 시각을 민다 —
     * 상태만 보면 아무 일도 안 일어난 것처럼 보이는 전이다.
     */
    @Test
    void 두번째_실패는_상태를_안_바꾸고_재시도_시각만_민다() {
        BillingCycle cycle = cycleIn(FAILED);   // RETRY_AT 로 한 번 실패한 상태

        SubscriptionTransitionResult result = cycle.onPaymentFailed(LATER_RETRY_AT);

        assertThat(result).isEqualTo(TRANSITIONED);
        assertThat(cycle.getStatus()).isEqualTo(FAILED);
        assertThat(cycle.getNextRetryAt()).isEqualTo(LATER_RETRY_AT);
    }

    /**
     * 종착지로 가면서 재시도 예약을 비운다. 안 비우면 스케줄러가 끝난 주기를 계속 집는다 —
     * {@code chk_billing_cycle_retry_shape} 가 DB 에서 거절하는 것과 같은 불변식을
     * 엔티티 쪽에서도 잰다(스키마 CHECK 는 flush 때나 걸린다).
     */
    @Test
    void 성공하면_재시도_예약이_지워진다() {
        BillingCycle cycle = cycleIn(FAILED);

        cycle.onPaymentSucceeded();

        assertThat(cycle.getNextRetryAt()).isNull();
    }

    @Test
    void 포기하면_재시도_예약이_지워진다() {
        BillingCycle cycle = cycleIn(FAILED);

        cycle.onRetriesExhausted();

        assertThat(cycle.getNextRetryAt()).isNull();
    }

    /** CONFLICT 칸은 상태만이 아니라 곁가지도 안 건드려야 한다 — 기록만 남기는 칸이다. */
    @Test
    void CONFLICT_는_아무것도_안_바꾼다() {
        BillingCycle cycle = cycleIn(PAID);

        assertThat(cycle.onPaymentFailed(LATER_RETRY_AT)).isEqualTo(CONFLICT);

        assertThat(cycle.getStatus()).isEqualTo(PAID);
        assertThat(cycle.getNextRetryAt()).isNull();   // 실패 사건이 예약을 심지 못했다
    }

    /**
     * null 을 "포기"로 쓰지 않는다. 한 인자가 시각과 포기 여부를 겸하는 순간
     * W5D1 하루 종일 걷어낸 그 모양이 된다 — 포기는 {@code onRetriesExhausted()} 다.
     */
    @Test
    void 재시도_시각_없이_실패시키면_거부한다() {
        BillingCycle cycle = newCycle();

        assertThatThrownBy(() -> cycle.onPaymentFailed(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("onRetriesExhausted");

        assertThat(cycle.getStatus()).isEqualTo(PENDING);
    }

    // ── 픽스처 ──────────────────────────────────────────────────────────

    private static BillingCycle newCycle() {
        Subscription subscription = new Subscription(
                new Seller("구독 셀러"), 10_000L, 30, LocalDate.of(2026, 8, 1));
        return new BillingCycle(subscription, 1, 10_000L,
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31));
    }

    /** 목표 상태까지 <b>실제 전이로</b> 몰고 간다. 세터로 심지 않는 이유는 클래스 javadoc 참고. */
    private static BillingCycle cycleIn(BillingCycleStatus target) {
        BillingCycle cycle = newCycle();
        switch (target) {
            case PENDING -> { /* 태어난 상태 그대로 */ }
            case PAID -> cycle.onPaymentSucceeded();
            case FAILED -> cycle.onPaymentFailed(RETRY_AT);
            case ABANDONED -> cycle.onRetriesExhausted();
        }
        assertThat(cycle.getStatus()).isEqualTo(target);   // 픽스처가 거짓말하지 않는지
        return cycle;
    }
}
