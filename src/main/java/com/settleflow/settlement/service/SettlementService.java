package com.settleflow.settlement.service;

import com.settleflow.settlement.repository.SettlementRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * 야간 정산 배치(W3D3). 하루치 장부를 셀러별로 집계해 {@code settlement} 에 스냅샷으로 굳힌다.
 * Quartz 가 주기적으로 부른다({@code SettlementBatchJob}).
 *
 * <p>로직은 전부 {@link SettlementRepository#upsertDailyNetBySeller} 의 SQL 한 문장에 있고,
 * 이 클래스가 하는 일은 <b>날짜를 시각 경계로 바꾸는 것</b>뿐이다. 그게 사소하지 않아서 따로 있다 —
 * {@code settlement_date} 는 {@code DATE} 라 타임존을 못 담으므로, "며칠"의 뜻은 여기 {@link #ZONE}
 * 한 곳에만 산다.
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
     */
    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private final SettlementRepository settlementRepository;

    public SettlementService(SettlementRepository settlementRepository) {
        this.settlementRepository = settlementRepository;
    }

    /**
     * 하루치를 정산한다. 이미 정산된 날짜면 덮어쓴다(재실행 = 복구).
     *
     * @param date KST 기준 날짜
     * @return 그날 장부에 등장한 셀러 수. 0 이면 그날 거래가 없었다는 뜻이지 실패가 아니다.
     */
    @Transactional
    public int settle(LocalDate date) {
        OffsetDateTime from = date.atStartOfDay(ZONE).toOffsetDateTime();
        OffsetDateTime to = date.plusDays(1).atStartOfDay(ZONE).toOffsetDateTime();

        int sellers = settlementRepository.upsertDailyNetBySeller(date, from, to);

        log.info("정산 배치 date={} [{} ~ {}) 셀러={}건", date, from, to, sellers);
        return sellers;
    }

    /**
     * 전날치를 정산한다 — 야간 배치의 진입점.
     *
     * <p>배치가 새벽에 도는 건 여유를 벌기 위해서다. 자정 직전에 시작된 트랜잭션이 자정 뒤에
     * 커밋되면 배치가 그 줄을 못 보는데(타임스탬프 순서 ≠ 가시성 순서), 몇 시간을 두면 그런 줄이
     * 대부분 이미 커밋돼 있다. <b>줄이는 것이지 없애는 게 아니다</b> — 근본 해결은 W3D5.
     */
    @Transactional
    public int settleYesterday() {
        return settle(LocalDate.now(ZONE).minusDays(1));
    }
}
