package com.settleflow.settlement.job;

import com.settleflow.settlement.service.SettlementService;
import org.quartz.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.TimeZone;

/**
 * 야간 정산 배치 스케줄(W3D3, 계획서의 {@code SettlementBatchScheduler}). Spring Boot 가 컨텍스트의
 * {@link JobDetail}·{@link Trigger} 빈을 주워 Quartz 에 등록한다 — {@code OutboxRelayConfig} 에서
 * 이미 검증된 배선이라 오늘 새로 배울 건 없다.
 *
 * <p><b>2 초 폴링(아웃박스)과 달리 cron 이다.</b> 아웃박스는 "빨리 비워야 하는 큐"고, 정산은
 * "하루가 끝나야 말이 되는 집계"라 주기가 도메인에서 나온다.
 *
 * <p><b>타임존을 트리거에 명시하는 게 핵심이다.</b> 안 주면 Quartz 는 JVM 기본 존으로 02:00 을
 * 해석한다. 경계를 자르는 존({@link SettlementService#ZONE})과 배치가 도는 존이 갈라지면
 * UTC 서버에서 "26일 02:00"이 KST 11:00 이 되어 <b>아직 안 끝난 날을 정산</b>하게 된다.
 * 그래서 존은 새로 쓰지 않고 서비스의 상수를 그대로 참조한다 — 정의는 한 곳뿐이다.
 *
 * <p>Quartz 는 현재 {@code NON_CLUSTERED}(단일 인스턴스)라 같은 시각에 배치가 둘 뜨는 일이 없다.
 * 다중 인스턴스가 되면 클러스터 모드가 필요하지만, 그때도 {@code ON CONFLICT DO UPDATE} 라
 * 최악이 이중계상이 아니라 같은 값 두 번 쓰기다.
 */
@Configuration
public class SettlementBatchConfig {

    static final String JOB_NAME = "settlementBatch";

    /**
     * 매일 02:00(KST).
     *
     * <p>W3D3 에선 새벽에 도는 것 자체가 방어였다 — 자정 직전에 시작된 트랜잭션이 자정 뒤에
     * 커밋되면 배치가 그 줄을 못 보므로, 몇 시간을 두면 그런 줄이 대부분 이미 커밋돼 있다.
     * <b>줄이는 것이지 없애는 게 아니었다.</b>
     *
     * <p>W3D5 이후로는 그 방어가 <b>더 이상 정확성에 필요하지 않다</b>({@code V10} 의 진행 축이
     * 늦게 커밋된 줄을 제 날짜로 주워온다). 새벽을 유지하는 이유는 이제 정확성이 아니라 부하다 —
     * 그리고 시각을 앞당길수록 밀린 날짜가 <b>다음 실행까지</b> 미정산으로 남는 시간이 길어진다.
     */
    private static final String CRON = "0 0 2 * * ?";

    @Bean
    public JobDetail settlementBatchJobDetail() {
        return JobBuilder.newJob(SettlementBatchJob.class)
                .withIdentity(JOB_NAME)
                .storeDurably()
                .build();
    }

    @Bean
    public Trigger settlementBatchTrigger(JobDetail settlementBatchJobDetail) {
        return TriggerBuilder.newTrigger()
                .forJob(settlementBatchJobDetail)
                .withIdentity(JOB_NAME + "Trigger")
                .withSchedule(CronScheduleBuilder.cronSchedule(CRON)
                        .inTimeZone(TimeZone.getTimeZone(SettlementService.ZONE)))
                .build();
    }
}
