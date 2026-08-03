package com.settleflow.settlement.service;

import com.settleflow.settlement.repository.SettlementPendingRepository;
import com.settleflow.settlement.repository.SettlementRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 야간 정산 배치(W3D3 → W3D5). 하루치 장부를 셀러별로 집계해 {@code settlement} 에 굳힌다.
 *
 * <h3>축이 둘이다(W3D5)</h3>
 * W3D3 은 {@code journal.created_at} 범위 하나로 두 가지를 동시에 물었다 — <i>이 줄이 어느 날에
 * 귀속되는가</i>와 <i>이 줄을 이미 셌는가</i>. 앞은 persist 때 멈추는 시계고 뒤는 커밋 때 움직이는
 * 시계라, 컬럼 하나로는 둘 다 답할 수 없었다. 그 결과가 W3D3 §4 의 구멍이다(경계 직전에 찍히고
 * 배치 뒤에 커밋된 줄이 <b>증발</b>).
 *
 * <p>이제 귀속은 {@code created_at} 이, 진행은 {@code settlement_pending} 이 답한다({@code V10}).
 * 나눠서 둘 다 얻는다 — "26일자 매출"의 뜻은 안 흐려지고, 늦게 커밋된 줄은 <b>제 날짜로</b>
 * 주워진다.
 *
 * <h3>표식은 집계 대상을 정의하지 않는다</h3>
 * 배치는 여전히 그 날짜 <b>전체</b>를 재집계하고 덮어쓴다(W3D3 §5 유지). 표식은 "다시 볼 날짜냐"만
 * 알려준다. 그래서 표식이 틀려도 금액은 안 틀리고, 표식 삭제와 집계의 원자성 요구가 낮아진다.
 * 표식으로 집계 대상을 정의했으면(=안 센 줄만 더하기) 쓰는 값이 델타라 <b>누적</b>이어야 했고,
 * 그러면 W3D3 §5 가 누적을 기각한 자리로 되돌아간다.
 */
@Service
public class SettlementService {

    private static final Logger log = LoggerFactory.getLogger(SettlementService.class);

    /**
     * 정산일 경계의 기준 시계. <b>프로젝트에서 이 상수는 여기 하나뿐이어야 한다.</b>
     * 배치 cron 도 이 값을 참조한다({@code SettlementBatchConfig}) — 경계를 자르는 존과 배치가 도는
     * 존이 갈라지면 "26일 02시"가 두 뜻을 갖는다.
     *
     * <p>{@code journal.created_at} 은 {@code TIMESTAMPTZ} + JVM 이 찍는 {@code OffsetDateTime}
     * ({@code Journal.onCreate})이라 <b>어느 존에서 찍혀도 같은 순간을 가리킨다</b>. 그래서 서버가
     * UTC 로 떠도 아래 경계 계산은 흔들리지 않는다 — 흔들리는 건 {@code DATE} 쪽 라벨뿐이고,
     * 그 라벨을 이 상수가 고정한다.
     *
     * <p>W3D5 에서 세 번째 사용처가 생겼다: {@link #pendingDates()} 의 시각→날짜 변환.
     * 그 변환을 SQL 에 두면 존이 DB 로 새므로 자바에 뒀다.
     */
    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private final SettlementRepository settlementRepository;
    private final SettlementPendingRepository pendingRepository;

    public SettlementService(SettlementRepository settlementRepository,
                             SettlementPendingRepository pendingRepository) {
        this.settlementRepository = settlementRepository;
        this.pendingRepository = pendingRepository;
    }

    /**
     * 아직 어느 정산에도 안 들어간 장부 줄이 있는 날짜들 — 오름차순.
     *
     * <p>배치가 <b>"어제"를 계산하는 게 아니라 물어본다</b>는 게 W3D5 의 진입점 변경이다.
     * 어제 들어왔어야 할 줄이 오늘 커밋됐으면 여기에 <b>어제</b>가 나온다.
     *
     * <p>날짜 변환이 여기 있는 이유는 {@link #ZONE} 을 SQL 로 내보내지 않기 위해서다
     * ({@code SettlementPendingRepository.findPendingJournalTimestamps} javadoc).
     */
    @Transactional(readOnly = true)
    public List<LocalDate> pendingDates() {
        return pendingRepository.findPendingJournalTimestamps().stream()
                .map(ts -> ts.atZone(ZONE).toLocalDate())
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * 하루치를 정산한다. 이미 정산된 날짜면 덮어쓴다(재실행 = 복구).
     *
     * <h3>{@code REPEATABLE READ} 가 여기서 일을 한다</h3>
     * 이 메서드는 두 가지를 한다 — <b>집계해서 쓰고</b>, <b>표식을 지운다</b>. 둘이 같은 줄 집합을
     * 봐야 한다. Postgres 기본 {@code READ COMMITTED} 는 <b>문장마다</b> 새 스냅샷을 뜨므로,
     * 한 트랜잭션에 넣어도 그 사이 커밋된 줄이 <i>집계엔 안 들어갔는데 표식은 지워지는</i> 상태가
     * 된다 — 구멍의 크기만 하루에서 몇 밀리초로 줄고 성질은 그대로다.
     *
     * <p>{@code REPEATABLE READ} 는 트랜잭션의 <b>첫 문장</b>에서 뜬 스냅샷을 이후 모든 문장이
     * 재사용한다. 그 사이 커밋된 줄은 집계에도 DELETE 에도 안 보인다 → 표식이 남고 다음 배치가
     * 제 날짜로 주워간다.
     *
     * <p><b>대가</b>: RR 은 충돌을 막지 않고 에러로 만든다({@code 40001 serialization failure}).
     * 지금은 배치가 단일 인스턴스({@code NON_CLUSTERED})고 장부가 append-only 라 경쟁자가 없어서
     * 안 터지지만, "안 생기게 설계했다"가 아니라 "아직 조건이 안 됐다"다. 재시도는 아직 없다.
     * 다만 <b>실패해도 (증발 없음)은 안 깨진다</b> — 전체 롤백이면 표식도 안 지워져 "안 셌다"로
     * 떨어지고 다음 배치가 줍는다. 피해가 증발이 아니라 지연이다.
     *
     * <p>표식 삭제를 집계 <b>뒤에</b> 두는 것 자체는 RR 하에서는 일을 하지 않는다(같은 스냅샷이라
     * 순서가 결과를 안 바꾼다). 격리 수준이 조용히 되돌아갔을 때의 백스톱으로만 의미가 있다 —
     * 실제로 막고 있는 건 {@code REPEATABLE READ} 하나다.
     *
     * @param date KST 기준 날짜
     * @return 그날 장부에 등장한 셀러 수. 0 이면 그날 거래가 없었다는 뜻이지 실패가 아니다.
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public int settle(LocalDate date) {
        OffsetDateTime from = date.atStartOfDay(ZONE).toOffsetDateTime();
        OffsetDateTime to = date.plusDays(1).atStartOfDay(ZONE).toOffsetDateTime();

        int sellers = settlementRepository.upsertDailyNetBySeller(date, from, to);
        int cleared = pendingRepository.clearSettled(from, to);

        log.info("정산 배치 date={} [{} ~ {}) 셀러={}건 표식정리={}줄", date, from, to, sellers, cleared);
        return sellers;
    }
}
