package com.settleflow.settlement.job;

import com.settleflow.reconciliation.service.ReconciliationRunner;
import com.settleflow.settlement.service.SettlementRunner;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.List;

/**
 * 야간 배치 Quartz Job(W3D3 → W3D5): <b>정산 다음에 대사</b>. 스케줄은 {@link SettlementBatchConfig} 에.
 *
 * <p>{@code OutboxRelayJob} 과 같은 모양 — 로직은 전부 서비스에 있고 여긴 트리거일 뿐이다.
 * 그래야 테스트가 Quartz 를 끄고({@code spring.quartz.auto-startup=false}) 서비스를 직접 불러
 * 결정적으로 검증할 수 있다.
 *
 * <p>W3D5 에서 {@code settleYesterday()} 가 사라졌다. 배치는 이제 <b>날짜를 계산해서 넘기지 않고</b>
 * "안 센 줄이 있는 날짜"를 물어본다({@link SettlementRunner#runPending()}).
 *
 * <h3>여기 두 줄이 있는 게 판단이다</h3>
 * 대사는 <b>정산 뒤에</b> 돌아야 하고, 정산이 실제로 돈 날짜를 <b>받아야</b> 한다 — 정산이 밀린
 * 과거 날짜를 도는데 대사가 최근 창만 보면 그 결과를 아무도 안 본다. 순서와 값 전달이 둘 다
 * 필요하므로 별도 cron 두 개로는 표현이 안 된다.
 *
 * <p>다만 이건 <b>배선이지 로직이 아니다</b>. 정산이 무엇을 도는지, 대사가 무엇을 볼지는 각
 * 러너가 정한다. 여기 있는 건 "정산 → 대사" 순서 하나뿐이고, 테스트는 두 러너를 직접
 * 순서대로 부른다.
 */
public class SettlementBatchJob implements Job {

    @Autowired
    private SettlementRunner settlementRunner;

    @Autowired
    private ReconciliationRunner reconciliationRunner;

    @Override
    public void execute(JobExecutionContext context) {
        List<LocalDate> settled = settlementRunner.runPending();
        reconciliationRunner.run(settled);
    }
}
