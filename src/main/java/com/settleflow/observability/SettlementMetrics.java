package com.settleflow.observability;

import com.settleflow.reconciliation.repository.ReconciliationRunRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.TimeGauge;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 정산·대사 배치의 관측 지표(W4D2).
 *
 * <h3>★ 두 무리로 갈린다 — 그 경계가 오늘 설계의 축이다</h3>
 * <table border="1">
 *   <tr><th></th><th>지표</th><th>출처</th><th>재시작하면</th></tr>
 *   <tr><td rowspan="2"><b>DB 에 있다</b></td>
 *       <td>미해소 불일치 건수</td><td rowspan="2">스크레이프마다 DB 재계산</td>
 *       <td rowspan="2"><b>복원된다</b></td></tr>
 *   <tr><td>대사 마지막 성공 시각</td></tr>
 *   <tr><td rowspan="3"><b>DB 에 없다</b></td>
 *       <td>배치 1회 소요시간</td><td rowspan="3">인메모리(배치가 밀어넣음)</td>
 *       <td rowspan="3"><b>사라진다</b></td></tr>
 *   <tr><td>날짜 1건 소요시간</td></tr>
 *   <tr><td>이번 배치가 돈 날짜 수</td></tr>
 * </table>
 *
 * <p>앞 무리는 <b>값을 밀어넣지 않는다</b>. 배치가 끝날 때 {@code set()} 하면 앱이 재시작한 순간
 * 0 이 되고, 미해소가 3 건 그대로인데 그래프는 초록불이 된다 — <b>메모리를 원본으로 삼은 대가</b>다.
 * 스크레이프마다 진실에서 <b>재구성</b>하면 그 자리가 없어진다.
 *
 * <p>뒷 무리는 그 선택지가 없다. <b>DB 에 아무 기록이 없어서 재구성할 원본이 없다.</b> 재시작하면
 * 사라지는 건 실수가 아니라 <b>알고 받는 대가</b>다 — 소요시간을 남기려면 테이블을 하나 더 파야
 * 하는데, 그건 "관측을 위해 진실 저장소를 늘린다"라 W2D1 §5 의 규율과 부딪힌다.
 *
 * <h3>라벨을 안 단다</h3>
 * {@code sellerId}·{@code settlementDate} 를 태그로 달지 않는다. {@code settlementDate} 는 날마다
 * 새 시계열이 <b>영구히</b> 늘고, {@code sellerId} 는 셀러 수만큼 곱해진다.
 * <b>메트릭은 수위, DB 는 명세</b> — 어느 날짜의 어느 셀러인지가 궁금하면
 * {@code discrepancy_observation} 을 본다(Grafana 에 Postgres 데이터소스를 나란히 둔 이유).
 */
@Component
public class SettlementMetrics {

    /** 아직 한 번도 안 돌았다. 0 이 아니다 — 0 은 "0초 걸렸다"로 읽힌다. */
    private static final long NEVER = -1L;

    private final ReconciliationRunRepository runRepository;

    /** B — 날짜 1건. 배치 한 번에 N 개가 쌓이므로 분포가 실재한다. */
    private final Timer dateTimer;

    /** A — 배치 1회. 표본이 하루 1개라 분포가 없다. 마지막 값만 뜻이 있다. */
    private final AtomicLong lastBatchNanos = new AtomicLong(NEVER);

    /** N — 이번 배치가 돈 날짜 수. A ÷ N ≈ B 를 닫는다. */
    private final AtomicInteger lastBatchDates = new AtomicInteger((int) NEVER);

    public SettlementMetrics(MeterRegistry registry, ReconciliationRunRepository runRepository) {
        this.runRepository = runRepository;

        // ── DB 에서 재구성하는 둘 ──────────────────────────────────────────────
        Gauge.builder("settleflow.reconciliation.unresolved.discrepancies",
                        runRepository, ReconciliationRunRepository::sumUnresolvedDiscrepancies)
                .description("아직 안 메워진 불일치 건수 (날짜별 최신 run 기준)")
                .register(registry);

        Gauge.builder("settleflow.reconciliation.last.success.timestamp", this,
                        SettlementMetrics::lastReconciliationEpochSeconds)
                .description("대사 배치가 마지막으로 돈 시각 (epoch 초). 배치 중단을 잡는 유일한 지표")
                .baseUnit("seconds")
                .register(registry);

        // ── 인메모리 둘 + 타이머 하나 ─────────────────────────────────────────
        TimeGauge.builder("settleflow.settlement.batch.duration", lastBatchNanos,
                        TimeUnit.NANOSECONDS, holder -> orNaN(holder.get()))
                .description("마지막 정산 배치 1회 소요시간. Timer 가 아닌 건 표본이 하루 1개라 분포가 없기 때문")
                .register(registry);

        Gauge.builder("settleflow.settlement.batch.dates", lastBatchDates,
                        holder -> orNaN(holder.get()))
                .description("마지막 배치가 정산한 날짜 수. 이게 없으면 배치 소요시간의 변화를 해석할 수 없다")
                .register(registry);

        // percentiles-histogram 을 안 켠다 — 버킷 시계열 수십 개를 만들 표본량이 아니다.
        // ⚠️ 이 Timer 가 함께 내보내는 `_max` 는 감쇠 게이지라 만료 창(기본 2분)이 지나면 0 으로
        // 떨어진다. 하루 1회 배치에선 거의 항상 0 이라 "빨랐다"로 오독된다 —
        // 대시보드에 올리지 않는다. 쓰는 건 `_count` 와 `_sum` 이고,
        // 하루 평균은 increase(sum[1d]) / increase(count[1d]) 로 낸다.
        this.dateTimer = Timer.builder("settleflow.settlement.date.duration")
                .description("날짜 1건 정산 소요시간. N 에 안 흔들리는 순수 처리 속도")
                .register(registry);
    }

    /**
     * 배치 1회가 끝났다. <b>밀린 날짜가 없어도 부른다</b> — 0 건은 정상 상태이고,
     * 그때도 "배치가 돌긴 했다"가 기록돼야 한다.
     */
    public void recordBatch(Duration elapsed, int dateCount) {
        lastBatchNanos.set(elapsed.toNanos());
        lastBatchDates.set(dateCount);
    }

    /** 날짜 1건이 끝났다. */
    public void recordDate(Duration elapsed) {
        dateTimer.record(elapsed);
    }

    /**
     * 대사 마지막 성공 시각을 epoch 초로. 한 번도 안 돌았으면 {@code NaN}.
     *
     * <p><b>0 을 안 쓰는 게 판단이다</b> — 0 은 1970년이라 {@code time() - metric} 이 즉시
     * 임계를 넘고, 갓 띄운 앱이 매번 거짓 경보를 낸다. {@code NaN} 은 "값이 없다"를 그대로 말한다.
     *
     * <p>⚠️ <b>대가는 알고 남긴다</b>: {@code time() - metric > 26h} 는 {@code NaN} 에서 거짓이라,
     * <b>한 번도 안 돈 상태는 이 규칙만으로 안 잡힌다</b>. 알림은 {@code absent()} 와 짝지어야
     * 완결된다 — 이건 오늘 (다)안(Counter + increase)이 가진 약점과 <b>같은 것</b>이고,
     * 부재를 값으로 표현하는 이상 어느 안을 골라도 남는다.
     */
    private double lastReconciliationEpochSeconds() {
        OffsetDateTime last = runRepository.findLastObservedAt();
        return last == null ? Double.NaN : last.toEpochSecond();
    }

    /** 아직 안 돈 상태를 0 이 아니라 "값 없음"으로 내보낸다. */
    private static double orNaN(long raw) {
        return raw == NEVER ? Double.NaN : raw;
    }
}
