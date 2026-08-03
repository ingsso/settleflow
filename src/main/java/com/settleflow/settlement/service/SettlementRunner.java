package com.settleflow.settlement.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * 배치의 진입점(W3D5) — <b>안 센 줄이 있는 날짜를 전부 돈다</b>.
 *
 * <p>W3D3 의 진입점은 {@code settleYesterday()} 였다. 날짜를 <i>계산</i>해서 넘겼는데, 그러면
 * 어제 들어왔어야 할 줄이 오늘 커밋됐을 때 그 날짜를 아무도 다시 부르지 않는다. 이제
 * {@link SettlementService#pendingDates()} 에 <b>물어본다</b> — 늦게 온 줄이 있으면 그 줄의
 * 날짜가 목록에 나오고, 그 날짜가 통째로 재집계된다.
 *
 * <h3>이게 왜 별도 빈인가 — 프록시 경계가 일을 한다</h3>
 * {@link SettlementService#settle} 의 {@code @Transactional(isolation = REPEATABLE_READ)} 는
 * <b>스프링 프록시를 통과할 때만</b> 적용된다. 이 반복문을 {@code SettlementService} 안에 두면
 * {@code this.settle(...)} 이 되어 프록시를 안 거치고, 격리 수준이 <b>조용히 무시된다</b> —
 * 바깥 트랜잭션의 기본값({@code READ COMMITTED})으로 돌면서 W3D5 가 닫은 구멍이 그대로 다시
 * 열린다. 컴파일도 되고 테스트도 대부분 통과하는 종류의 실패다.
 *
 * <p>그래서 오케스트레이션을 <b>다른 빈</b>으로 뺐다. 여기서 {@code settlementService.settle(d)}
 * 는 주입받은 프록시를 거치므로 격리 수준이 실제로 걸린다.
 *
 * <p>이 클래스에 {@code @Transactional} 이 <b>없는 것도 판단이다</b>. 붙이면 전체가 한
 * 트랜잭션이 되어 (1) 날짜 하나의 실패가 나머지를 다 막고 (2) 스냅샷이 하나로 합쳐져
 * 날짜별 경계가 흐려진다. <b>날짜 하나가 트랜잭션 하나</b>여야 한다.
 */
@Service
public class SettlementRunner {

    private static final Logger log = LoggerFactory.getLogger(SettlementRunner.class);

    private final SettlementService settlementService;

    public SettlementRunner(SettlementService settlementService) {
        this.settlementService = settlementService;
    }

    /**
     * 안 센 줄이 있는 날짜를 전부 정산한다.
     *
     * <p>날짜 목록을 뜨는 것과 각 날짜를 처리하는 것은 서로 다른 트랜잭션이다. 그 사이에 새
     * 날짜가 생기면 이번엔 못 보고 <b>다음 실행에 잡힌다</b> — 표식이 안 지워졌으므로 안전한
     * 쪽으로 떨어진다.
     *
     * @return 이번에 정산한 날짜들. 비어 있으면 밀린 게 없다는 뜻이고, 그게 정상 상태다.
     *         <b>대사가 이 목록을 날짜 출처 ① 로 받는다</b>({@code ReconciliationRunner}) —
     *         최근 창만 보면 아주 오래된 날짜가 재정산돼도 아무도 대사하지 않기 때문이다.
     */
    public List<LocalDate> runPending() {
        List<LocalDate> dates = settlementService.pendingDates();
        if (dates.isEmpty()) {
            log.info("정산 배치 — 밀린 날짜 없음");
            return List.of();
        }

        log.info("정산 배치 — 밀린 날짜 {}건 {}", dates.size(), dates);
        for (LocalDate date : dates) {
            settlementService.settle(date);
        }
        return dates;
    }
}
