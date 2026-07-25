package com.settleflow.outbox.job;

import org.quartz.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 아웃박스 릴레이 스케줄(W3D1). Spring Boot 가 컨텍스트의 {@link JobDetail}·{@link Trigger} 빈을
 * 주워 Quartz 스케줄러에 등록한다.
 *
 * <p>2 초 주기 폴링. 정산·대사 성격상 초 단위 지연은 무관하다(W1D3 의 "조회할 줄 아는 배치"와 같은 결 —
 * 느린 게 아니라 필요한 만큼만 자주). Quartz 는 현재 {@code NON_CLUSTERED}(단일 인스턴스)라 릴레이가
 * 하나뿐이고, 그래서 같은 행을 두 릴레이가 집으려는 claim 경쟁이 아직 없다.
 */
@Configuration
public class OutboxRelayConfig {

    static final String JOB_NAME = "outboxRelay";
    private static final int POLL_INTERVAL_SECONDS = 2;

    @Bean
    public JobDetail outboxRelayJobDetail() {
        return JobBuilder.newJob(OutboxRelayJob.class)
                .withIdentity(JOB_NAME)
                .storeDurably()   // 트리거 없이도 스케줄러에 남는다(정의를 먼저 등록)
                .build();
    }

    @Bean
    public Trigger outboxRelayTrigger(JobDetail outboxRelayJobDetail) {
        return TriggerBuilder.newTrigger()
                .forJob(outboxRelayJobDetail)
                .withIdentity(JOB_NAME + "Trigger")
                .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                        .withIntervalInSeconds(POLL_INTERVAL_SECONDS)
                        .repeatForever())
                .build();
    }
}
