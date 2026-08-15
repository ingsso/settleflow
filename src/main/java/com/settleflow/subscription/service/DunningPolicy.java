package com.settleflow.subscription.service;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 재시도 간격 규칙(W5D2). 계획서의 <i>"1일→3일→7일 후 3회"</i> 가 사는 유일한 자리다.
 *
 * <h3>상한이라는 값이 없다</h3>
 * {@code maxAttempts = 3} 을 두지 않는다. 두는 순간 {@code [1,3,7]} 과 항상 같아야 하는 값의
 * 복제가 되고, 그건 W1D3(재고 Redis)·W2D4(환불 상태)·W5D1({@code attempt_count})에서 세 번
 * 기각한 모양이다. <b>포기는 목록에 다음 칸이 없다는 사실</b>이지 따로 적는 수가 아니다.
 *
 * <p>그 덕에 <b>"3회"와 "4회"를 어디에도 안 적는다</b>. 여기는 한 칸 틀리기 쉬운 자리다:
 *
 * <pre>
 *   원 청구 실패 → +1일 → 재시도1 실패 → +3일 → 재시도2 실패 → +7일 → 재시도3 실패 → 포기
 *
 *   간격 목록 길이 = 3   (재시도 횟수)
 *   payment 행 개수 = 4   (시도 횟수 — 원 청구를 포함하므로 하나 많다)
 * </pre>
 *
 * 목록을 {@code [1,3,7,14]} 로 늘리면 상한이 저절로 따라온다.
 *
 * <h3>간격의 기준은 직전 시도다 (백오프)</h3>
 * 계획서 문장은 두 갈래로 읽혔다 — <i>직전 실패 기준</i>(누적 1·4·11일)과 <i>첫 실패 기준</i>
 * (누적 1·3·7일). 전자를 골랐다: 간격이 늘어나는 것이 백오프의 뜻이고, 후자는 고정 스케줄이라
 * 세 번째 재시도가 두 번째의 4일 뒤가 아니라 그냥 첫 실패의 7일 뒤가 된다.
 *
 * <h3>순수 함수다</h3>
 * 시계도 리포지토리도 안 든다 — 기준 시각과 시도 수를 <b>인자로</b> 받는다.
 * {@code Payment.refundStatus(long)} · {@code Subscription.health(long)} 과 같은 모양이고,
 * 그래서 {@code DunningScheduler} 없이 단위 테스트가 된다.
 */
@Component
public class DunningPolicy {

    /**
     * 재시도 간격. <b>이 목록이 규칙의 전부다</b> — 길이가 재시도 횟수를, 값이 백오프를 정한다.
     *
     * <p>설정 파일로 빼지 않았다. 빼면 테스트가 설정에 묶이고, 값이 바뀌었을 때 테스트가
     * 조용히 다른 것을 재게 된다(W4D5 가 <i>"재는 자와 재이는 것"</i> 으로 정리한 자리).
     */
    private static final List<Duration> INTERVALS =
            List.of(Duration.ofDays(1), Duration.ofDays(3), Duration.ofDays(7));

    /**
     * 다음 재시도 시각. <b>비어 있으면 포기다.</b>
     *
     * @param completedAttempts 이 주기에서 <b>결말이 난</b> 시도 수. 방금 실패한 시도를 포함한다
     *                          — 즉 "이번이 몇 번째였나". 1 이 원 청구다.
     * @param failedAt          직전 시도의 실패 시각. 백오프의 기준이다(위 참고).
     * @throws IllegalArgumentException {@code completedAttempts < 1} 일 때. 결말이 하나도 안 난
     *                                  주기에 "다음 재시도"를 묻는 건 프로그래밍 오류다 —
     *                                  조회는 던지고 명령은 반환한다(W2D4 이래의 태도).
     */
    public Optional<OffsetDateTime> nextRetryAt(int completedAttempts, OffsetDateTime failedAt) {
        requireCounted(completedAttempts);
        if (!hasRetryLeft(completedAttempts)) return Optional.empty();
        return Optional.of(failedAt.plus(INTERVALS.get(completedAttempts - 1)));
    }

    /**
     * 시도를 한 번 더 걸어도 되나. {@link #nextRetryAt} 과 <b>같은 규칙의 다른 사용</b>이다 —
     * 저쪽은 돌려받은 시각을 쓰고, 이쪽은 있고 없음만 쓴다.
     *
     * <h3>왜 이 질문이 따로 필요한가</h3>
     * 상한 판정이 서는 자리가 둘이기 때문이다:
     *
     * <pre>
     *   ① 실패 통보 직후        — 정상 경로. nextRetryAt() 이 답한다
     *   ② 시도를 걸기 직전       — 백스톱. 이 메서드가 답한다
     * </pre>
     *
     * ②가 없으면 <b>{@code UNKNOWN} 경로로 무한 재시도가 돌아온다</b>: 타임아웃은 주기에
     * 통보되지 않으므로({@code applyTimedOut} 은 {@code payment} 만 건드린다) ①에 도달을
     * 못 하고, 리스가 만료될 때마다 폴링이 그 주기를 영원히 다시 집는다.
     * 시도를 {@code FAILED}+{@code UNKNOWN} 으로 세기로 한 결정은 ①만으로는 값을 못 낸다.
     *
     * <h3>여기는 0 을 받는다 — {@link #nextRetryAt} 과 다른 점</h3>
     * 시도를 걸어놓고 <b>결말 전에 프로세스가 죽으면</b> {@code PENDING} 인 {@code payment} 행이
     * 남는다. 그 행은 {@code FAILED} 도 {@code UNKNOWN} 도 아니라 세어지지 않으므로
     * {@code completedAttempts} 가 0 일 수 있다. 그건 "물을 수 없는 질문"이 아니라
     * <b>아무것도 확정되지 않았다</b>는 정상 상태이고, 답은 당연히 "걸어도 된다"다.
     *
     * <p>반면 {@link #nextRetryAt} 에 0 을 물으면 <i>"직전 실패로부터 얼마 뒤"</i> 의
     * 직전 실패가 없다 — 그건 프로그래밍 오류라 던진다.
     *
     * @param completedAttempts 결말이 난 시도 수. 다음 시도는 {@code completedAttempts + 1} 번째다
     */
    public boolean hasRetryLeft(int completedAttempts) {
        if (completedAttempts < 0) {
            throw new IllegalArgumentException("시도 수는 음수일 수 없다. completedAttempts=" + completedAttempts);
        }
        return completedAttempts <= INTERVALS.size();
    }

    private void requireCounted(int completedAttempts) {
        if (completedAttempts < 1) {
            throw new IllegalArgumentException(
                    "결말이 난 시도가 없는 주기에는 다음 재시도 시각이 정의되지 않는다. completedAttempts="
                            + completedAttempts);
        }
    }
}
