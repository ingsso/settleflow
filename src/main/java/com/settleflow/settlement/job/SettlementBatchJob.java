package com.settleflow.settlement.job;

import com.settleflow.settlement.service.SettlementRunner;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 정산 배치를 돌리는 Quartz Job(W3D3 → W3D5). 스케줄은 {@link SettlementBatchConfig} 에.
 *
 * <p>{@code OutboxRelayJob} 과 같은 모양 — 로직은 전부 서비스에 있고 여긴 트리거일 뿐이다.
 * 그래야 테스트가 Quartz 를 끄고({@code spring.quartz.auto-startup=false}) 서비스를 직접 불러
 * 결정적으로 검증할 수 있다.
 *
 * <p>W3D5 에서 {@code settleYesterday()} 가 사라졌다. 배치는 이제 <b>날짜를 계산해서 넘기지 않고</b>
 * "안 센 줄이 있는 날짜"를 물어본다({@link SettlementRunner#runPending()}).
 */
public class SettlementBatchJob implements Job {

    @Autowired
    private SettlementRunner settlementRunner;

    @Override
    public void execute(JobExecutionContext context) {
        settlementRunner.runPending();
    }
}
