package com.settleflow.product;

import com.settleflow.product.domain.Product;
import com.settleflow.product.repository.ProductRepository;
import com.settleflow.product.service.InventoryService;
import com.settleflow.product.service.OutOfStockException;
import com.settleflow.seller.domain.Seller;
import com.settleflow.support.TestImages;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import jakarta.persistence.EntityManager;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 재고 차감이 Redis 락 없이 동시성을 막는지 실측한다(W1D3 결정의 근거).
 *
 * 원자적 조건부 UPDATE(WHERE stock >= :qty)만으로 초과 판매가 나지 않아야 한다.
 */
@SpringBootTest
@Testcontainers
class InventoryConcurrencyTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(TestImages.POSTGRES);

    @Autowired InventoryService inventoryService;
    @Autowired ProductRepository productRepository;
    @Autowired TransactionTemplate txTemplate;
    @Autowired EntityManager em;

    Long productId;

    @BeforeEach
    void setUp() {
        txTemplate.executeWithoutResult(tx -> {
            em.createNativeQuery("DELETE FROM order_item").executeUpdate();
            em.createNativeQuery("DELETE FROM product").executeUpdate();
            em.createNativeQuery("DELETE FROM seller").executeUpdate();

            Seller seller = new Seller("테스트셀러");
            em.persist(seller);

            Product product = new Product(seller, "한정판 티셔츠", 10_000L, 3);   // 재고 3개
            em.persist(product);
            em.flush();
            productId = product.getId();
        });
    }

    @Test
    void 재고3개에_동시10명이_1개씩_주문하면_정확히_3건만_성공한다() throws Exception {
        int contenders = 10;

        CyclicBarrier gate = new CyclicBarrier(contenders);   // 다 모이면 동시 발사
        ExecutorService pool = Executors.newFixedThreadPool(contenders);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger outOfStock = new AtomicInteger();

        Callable<Void> attempt = () -> {
            gate.await();
            try {
                inventoryService.decrease(Map.of(productId, 1));
                success.incrementAndGet();
            } catch (OutOfStockException e) {
                outOfStock.incrementAndGet();
            }
            return null;
        };

        for (Future<Void> f : pool.invokeAll(
                java.util.Collections.nCopies(contenders, attempt))) {
            f.get();
        }
        pool.shutdown();

        int finalStock = currentStock();

        System.out.printf("[실측] 동시 %d명 / 재고 3 → 성공 %d, 재고부족 %d, 최종재고 %d%n",
                contenders, success.get(), outOfStock.get(), finalStock);

        assertThat(success.get()).isEqualTo(3);
        assertThat(outOfStock.get()).isEqualTo(7);
        assertThat(finalStock).isZero();   // 음수 재고(초과 판매) 없음
    }

    @Test
    void 재고보다_많은_수량을_요청하면_거부되고_재고는_그대로다() {
        try {
            inventoryService.decrease(Map.of(productId, 4));   // 재고 3인데 4개
        } catch (OutOfStockException expected) {
            // 기대한 경로
        }
        assertThat(currentStock()).isEqualTo(3);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    int currentStock() {
        return txTemplate.execute(tx -> ((Number) em
                .createNativeQuery("SELECT stock FROM product WHERE id = :id")
                .setParameter("id", productId)
                .getSingleResult()).intValue());
    }
}
