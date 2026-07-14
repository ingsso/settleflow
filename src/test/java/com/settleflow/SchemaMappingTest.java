package com.settleflow;

import com.settleflow.support.TestImages;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 엔티티 매핑이 Flyway가 만든 실제 스키마와 맞는지 검증한다.
 *
 * ddl-auto=validate 이면 Hibernate가 부팅 시 모든 엔티티를 실제 테이블과 대조한다.
 * 컬럼명 오타, 없는 컬럼, 타입 불일치가 있으면 컨텍스트 로딩 자체가 실패한다.
 * → 이 테스트가 통과한다는 것은 "엔티티 6개가 실제 DB와 맞물린다"는 뜻.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@Testcontainers
class SchemaMappingTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(TestImages.POSTGRES);

    @Autowired
    EntityManager em;

    @Test
    void 엔티티_매핑이_실제_스키마와_일치한다() {
        // 여기까지 왔다는 건 Hibernate validate 가 통과했다는 뜻.
        assertThat(em).isNotNull();
    }
}
