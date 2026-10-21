package com.settleflow;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
// IntegrationTestSupport 밖이라 웹훅 시크릿(${WEBHOOK_SECRET})을 직접 준다 — 없으면 컨텍스트가 안 뜬다.
@SpringBootTest(properties = "settleflow.webhook.secret=test-webhook-secret")
class SettleflowApplicationTests {

	@Test
	void contextLoads() {
	}

}
