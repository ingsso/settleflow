package com.settleflow.settlement.job;

import com.settleflow.settlement.service.SettlementService;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 전날치 정산을 돌리는 Quartz Job(W3D3). 스케줄은 {@link SettlementBatchConfig} 에.
 *
 * <p>{@code OutboxRelayJob} 과 같은 모양 — 로직은 전부 서비스에 있고 여긴 트리거일 뿐이다.
 * 그래야 테스트가 Quartz 를 끄고({@code spring.quartz.auto-startup=false}) 서비스를 직접 불러
 * 결정적으로 검증할 수 있다.
 */
public class SettlementBatchJob implements Job {

    @Autowired
    private SettlementService settlementService;

    @Override
    public void execute(JobExecutionContext context) {
        settlementService.settleYesterday();
    }
}
