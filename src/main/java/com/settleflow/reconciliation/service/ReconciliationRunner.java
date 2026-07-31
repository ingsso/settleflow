package com.settleflow.reconciliation.service;

import com.settleflow.reconciliation.repository.ReconciliationRunRepository;
import com.settleflow.settlement.service.SettlementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.SequencedSet;
import java.util.TreeSet;
import java.util.stream.IntStream;

/**
 * 대사 배치의 진입점(W3D5 ①) — <b>어느 날짜를 볼지 정하는 게 이 클래스의 전부</b>다.
 *
 * <p>W3D4 까지 {@link ReconciliationService} 는 프로덕션에서 <b>아무도 안 불렀다</b>. 비교 로직만
 * 있고 도는 사람이 없었다. 여기서 돌린다.
 *
 * <h3>날짜 출처가 셋이고, 셋 다 다른 걸 잡는다</h3>
 * <table border="1">
 *   <tr><th>출처</th><th>잡는 것</th></tr>
 *   <tr><td>① 정산이 방금 돈 날짜</td>
 *       <td>재정산된 <b>오래된 날짜</b>. W3D5 ② 이후 배치는 밀린 날짜를 아무리 과거라도 도는데,
 *           최근 창만 보면 그 결과를 아무도 대사하지 않는다</td></tr>
 *   <tr><td>② 미해소 불일치가 있는 날짜</td>
 *       <td>해소될 때까지 <b>스스로 살아남는다</b>. 이게 없으면 "며칠째"가 1 에서 멈춘다 —
 *           불일치가 있어도 정산이 이미 끝난 날은 ①에 안 나오기 때문이다</td></tr>
 *   <tr><td>③ 최근 {@link #RECENT_WINDOW_DAYS}일</td>
 *       <td><b>아무도 안 돈 날.</b> ①만 쓰면 대사가 감시 대상에게서 볼 목록을 물려받는 꼴이라,
 *           {@code trg_journal_settlement_pending} 이 죽으면 정산도 안 되고 대사도 눈을 감는다
 *           (불일치 0 건 = 초록불). ③이 그 상관 실패를 끊는다</td></tr>
 * </table>
 *
 * <p><b>③ 이 있는 이유가 W3D4 독립성 논증의 연장이다.</b> 거기선 <i>금액을 어떻게 계산하는가</i>를
 * 독립으로 만들었는데(같은 SQL 재실행 = 동어반복), <i>무엇을 볼 것인가</i> 는 여전히 감시 대상이
 * 정하고 있었다. 초록불이 "옳다"가 아니라 "그 경로를 안 밟았다"는 뜻이 되는 자리다(W3D3 §8).
 */
@Service
public class ReconciliationRunner {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationRunner.class);

    /**
     * 정산이 뭘 하든 무조건 보는 창.
     *
     * <p>이 값이 정하는 건 <b>감당할 최대 배치 중단 기간</b>이다 — 배치가 이만큼 넘게 멈춰 있으면
     * 그 앞의 날들은 아무도 안 본 채로 창 밖으로 나간다. 늘리면 매일 재구성하는 비용이 그만큼
     * 늘고(대사는 {@code payment}+{@code refund} 를 다시 walk 한다), 줄이면 감지 지연을 못 견딘다.
     * 7 은 "주말 낀 장애를 넘긴다"에서 나온 값이다.
     */
    static final int RECENT_WINDOW_DAYS = 7;

    private final ReconciliationRecorder recorder;
    private final ReconciliationRunRepository runRepository;

    public ReconciliationRunner(ReconciliationRecorder recorder,
                                ReconciliationRunRepository runRepository) {
        this.recorder = recorder;
        this.runRepository = runRepository;
    }

    /**
     * 세 출처를 합쳐 대사한다.
     *
     * <p>{@code TreeSet}: 합집합이라 중복이 생기고(세 출처가 겹친다), 순서가 실행마다 바뀌면
     * 로그도 테스트도 비교할 수 없다({@code ReconciliationService} 가 결과 목록에 쓴 것과 같은 이유).
     *
     * <p>날짜 하나가 {@link ReconciliationRecorder#record} 한 번 = <b>트랜잭션 하나</b>다.
     * 한 날짜의 실패가 나머지를 막지 않는다({@code SettlementRunner} 와 같은 판단).
     *
     * @param settledDates 방금 정산이 돈 날짜들(출처 ①). 정산과 무관하게 부를 땐 빈 목록
     * @return 실제로 대사한 날짜들
     */
    public List<LocalDate> run(List<LocalDate> settledDates) {
        List<LocalDate> unresolved = runRepository.findUnresolvedDates();

        SequencedSet<LocalDate> targets = new TreeSet<>();
        targets.addAll(settledDates);
        targets.addAll(unresolved);
        targets.addAll(recentDates());

        log.info("대사 배치 — 대상 {}건 (정산={} 미해소={} 최근{}일)", targets.size(),
                settledDates.size(), unresolved.size(), RECENT_WINDOW_DAYS);

        targets.forEach(recorder::record);
        return List.copyOf(targets);
    }

    /**
     * 어제부터 거꾸로 {@link #RECENT_WINDOW_DAYS}일.
     *
     * <p><b>오늘을 빼는 게 판단이다.</b> 오늘은 아직 안 끝난 날이라 정산이 돌 이유가 없고,
     * 대사하면 "장부엔 있는데 정산엔 없다"가 매번 뜬다 — 정상 상태의 거짓 경보다.
     * 경계는 정산과 같은 {@link SettlementService#ZONE} 로 자른다.
     */
    private List<LocalDate> recentDates() {
        LocalDate yesterday = LocalDate.now(SettlementService.ZONE).minusDays(1);
        return IntStream.range(0, RECENT_WINDOW_DAYS)
                .mapToObj(yesterday::minusDays)
                .toList();
    }
}
