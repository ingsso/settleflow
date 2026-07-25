package com.settleflow.outbox.job;

import com.settleflow.outbox.service.OutboxRelay;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 아웃박스 릴레이를 주기적으로 깨우는 Quartz Job(W3D1). 스케줄은 {@link OutboxRelayConfig} 에서.
 *
 * <p>Spring Boot 의 Quartz 자동설정이 job 인스턴스를 autowire 하므로 {@link OutboxRelay} 를
 * 필드 주입받는다. 로직은 전부 릴레이에 있고 여긴 트리거일 뿐이다.
 */
public class OutboxRelayJob implements Job {

    @Autowired
    private OutboxRelay outboxRelay;

    @Override
    public void execute(JobExecutionContext context) {
        outboxRelay.publishBatch();
    }
}
