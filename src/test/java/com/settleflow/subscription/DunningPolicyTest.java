package com.settleflow.subscription;

import com.settleflow.subscription.service.DunningPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * W5D2 — 재시도 간격 규칙. 스프링 컨텍스트가 없다(정책이 순수 함수라서).
 *
 * <p>D1 의 전이표 테스트와 같은 태도로 짰다: <b>계획서의 문장을 실행 가능한 형태로 옮긴다.</b>
 * <i>"1일→3일→7일 후 3회"</i> 를 주석으로 두면 코드와 갈라지고, 여기 두면 못 갈라진다.
 */
class DunningPolicyTest {

    private final DunningPolicy policy = new DunningPolicy();

    private static final OffsetDateTime T0 =
            OffsetDateTime.of(2026, 8, 15, 10, 0, 0, 0, ZoneOffset.UTC);

    // ---------- 1. 사다리 전수 — 계획서 수치가 여기 산다 ----------

    /**
     * {@code completedAttempts} 는 <b>이번을 포함한</b> 결말 수다. 1 이 원 청구 실패다.
     */
    @ParameterizedTest(name = "{0}번째 실패 → {1}일 뒤")
    @CsvSource({
            "1, 1",   // 원 청구 실패   → 1일 뒤
            "2, 3",   // 재시도1 실패   → 3일 뒤
            "3, 7",   // 재시도2 실패   → 7일 뒤
    })
    void 실패한_시도_수마다_정해진_간격이_나온다(int completedAttempts, int expectedDays) {
        Optional<OffsetDateTime> next = policy.nextRetryAt(completedAttempts, T0);

        assertThat(next).contains(T0.plusDays(expectedDays));
    }

    @Test
    void 네_번째_실패에는_다음이_없다_그게_포기다() {
        assertThat(policy.nextRetryAt(4, T0)).isEmpty();
    }

    /**
     * ★ 한 칸 어긋나기 쉬운 자리를 고정한다.
     *
     * <p>간격 목록 길이는 3(재시도 3회)인데 실제 {@code payment} 행은 4개다 — 원 청구가
     * 시도에 포함되기 때문이다. 상한을 값으로 적었다면 3 과 4 중 하나를 골라 적어야 했고,
     * 그 순간 목록과 어긋날 수 있는 복제가 생겼다.
     */
    @Test
    void 총_시도는_네_번이고_그_중_재시도가_세_번이다() {
        int attempts = 0;
        OffsetDateTime at = T0;

        while (true) {
            attempts++;                                   // 시도 한 번 나갔고, 실패했다
            Optional<OffsetDateTime> next = policy.nextRetryAt(attempts, at);
            if (next.isEmpty()) break;
            at = next.get();
        }

        System.out.printf("[실측] 총 시도=%d (재시도=%d) 마지막 시도 시각=%s (T0+%d일)%n",
                attempts, attempts - 1, at, java.time.Duration.between(T0, at).toDays());

        assertThat(attempts).isEqualTo(4);
        assertThat(attempts - 1).isEqualTo(3);            // 계획서의 "3회"
    }

    // ---------- 2. 해석 A(백오프)를 못 박는다 ----------

    /**
     * ★ 계획서 <i>"1일→3일→7일 후"</i> 는 두 갈래로 읽혔다:
     *
     * <pre>
     *   A 직전 시도 기준(백오프)  → 첫 실패로부터 누적 1, 4, 11일   ← 고른 쪽
     *   B 첫 실패 기준(고정)      → 첫 실패로부터 누적 1, 3, 7일
     * </pre>
     *
     * 누적 궤적을 단언하면 둘이 갈린다. 이 테스트가 red 로 바뀌면 해석이 바뀐 것이다.
     */
    @Test
    void 간격은_직전_시도_기준이라_누적이_1일_4일_11일이다() {
        OffsetDateTime r1 = policy.nextRetryAt(1, T0).orElseThrow();
        OffsetDateTime r2 = policy.nextRetryAt(2, r1).orElseThrow();
        OffsetDateTime r3 = policy.nextRetryAt(3, r2).orElseThrow();

        System.out.printf("[실측] 누적 궤적 T0+%d일, +%d일, +%d일%n",
                java.time.Duration.between(T0, r1).toDays(),
                java.time.Duration.between(T0, r2).toDays(),
                java.time.Duration.between(T0, r3).toDays());

        assertThat(r1).isEqualTo(T0.plusDays(1));
        assertThat(r2).isEqualTo(T0.plusDays(4));    // B 였다면 +3
        assertThat(r3).isEqualTo(T0.plusDays(11));   // B 였다면 +7
    }

    @Test
    void 간격은_단조증가한다() {
        long g1 = java.time.Duration.between(T0, policy.nextRetryAt(1, T0).orElseThrow()).toDays();
        long g2 = java.time.Duration.between(T0, policy.nextRetryAt(2, T0).orElseThrow()).toDays();
        long g3 = java.time.Duration.between(T0, policy.nextRetryAt(3, T0).orElseThrow()).toDays();

        assertThat(g1).isLessThan(g2);
        assertThat(g2).isLessThan(g3);
    }

    // ---------- 3. 집기 직전의 백스톱 ----------

    /**
     * {@code hasRetryLeft} 는 {@code nextRetryAt} 과 <b>같은 목록</b>을 본다. 두 자리에서
     * 상한을 보지만 규칙은 하나다.
     */
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void 결말난_시도가_셋_이하면_한_번_더_걸_수_있다(int concluded) {
        assertThat(policy.hasRetryLeft(concluded)).isTrue();
    }

    @Test
    void 결말난_시도가_넷이면_더_걸_수_없다() {
        assertThat(policy.hasRetryLeft(4)).isFalse();
    }

    /**
     * ★ 0 을 받는 쪽과 안 받는 쪽이 다르다.
     *
     * <p>시도를 걸어놓고 결말 전에 죽으면 {@code PENDING} 행만 남고 결말 수가 0 일 수 있다.
     * 그때 <i>"더 걸어도 되나"</i> 는 성립하는 질문(답: 된다)이지만,
     * <i>"직전 실패로부터 얼마 뒤인가"</i> 는 직전 실패가 없어 성립하지 않는다.
     */
    @Test
    void 결말이_하나도_없으면_걸_수는_있지만_다음_시각은_못_묻는다() {
        assertThat(policy.hasRetryLeft(0)).isTrue();

        assertThatThrownBy(() -> policy.nextRetryAt(0, T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("정의되지 않는다");
    }

    @Test
    void 음수_시도는_거부한다() {
        assertThatThrownBy(() -> policy.hasRetryLeft(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
