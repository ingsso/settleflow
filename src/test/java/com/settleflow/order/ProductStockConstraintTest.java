package com.settleflow.order;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.settleflow.support.TestImages;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class ProductStockConstraintTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(TestImages.POSTGRES);

    @Autowired
    JdbcTemplate jdbcTemplate;

    Long sellerId;

    @BeforeEach
    void setUp() {
        sellerId = jdbcTemplate.queryForObject(
                "INSERT INTO seller (name) VALUES ('테스트셀러') RETURNING id",
                Long.class);
    }

    @Test
    void 음수_재고로_INSERT하면_제약조건이_막는다() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO product (name, price, stock, seller_id) VALUES (?, ?, ?, ?)",
                "테스트상품", 10_000L, -1, sellerId))
                .isInstanceOf(DataIntegrityViolationException.class)
                // 우산 예외(CHECK/FK/UNIQUE/NOT NULL 공통)라 제약 이름까지 못 박아야
                // 다른 제약이 터진 가짜 통과를 걸러낸다.
                .hasMessageContaining("chk_product_stock");
    }

    @Test
    void 재고보다_많이_차감하는_UPDATE도_제약조건이_막는다() {
        Long productId = jdbcTemplate.queryForObject(
                "INSERT INTO product (name, price, stock, seller_id) VALUES (?, ?, ?, ?) RETURNING id",
                Long.class, "테스트상품", 10_000L, 1, sellerId);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE product SET stock = stock - 2 WHERE id = ?", productId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_product_stock");
    }
}