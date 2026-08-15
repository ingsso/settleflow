package com.settleflow.subscription.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@code @Scheduled} 를 켜는 유일한 자리(W5D2).
 *
 * <p><b>속성으로 끌 수 있게 둔 게 요점이다.</b> 테스트에서 폴러가 제멋대로 돌면 이 배치가
 * 테스트 자신의 데이터를 집어가고, 단언은 자기가 만든 상태가 아니라 배치가 남긴 상태를 재게
 * 된다. W4D5 가 <i>"테스트가 자기 데이터가 아니라 스위트가 남긴 배치를 재고 있었다"</i> 로
 * 하루를 쓴 자리라, 같은 구조를 새로 만들지 않는다.
 *
 * <p>선례도 그대로다 — {@code IntegrationTestSupport} 는 이미 같은 이유로
 * {@code spring.quartz.auto-startup} 과 {@code spring.kafka.listener.auto-startup} 을 끈다.
 * 테스트는 {@code DunningScheduler.runBillingDue(today)} / {@code runRetryDue(now)} 를 직접 불러
 * <b>시각까지 결정적으로</b> 검증한다.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "settleflow.dunning.scheduler.enabled",
        havingValue = "true", matchIfMissing = true)
public class DunningSchedulingConfig {
}
