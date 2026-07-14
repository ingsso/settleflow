package com.settleflow.product.repository;

import com.settleflow.product.domain.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductRepository extends JpaRepository<Product, Long> {

    /**
     * 원자적 조건부 차감. 락도 Redis도 없다 — WHERE 절이 곧 가드다.
     *
     * Postgres READ COMMITTED 에서 뒤늦은 트랜잭션은 앞선 트랜잭션의 커밋을 기다린 뒤
     * '갱신된 행'으로 WHERE 를 재평가한다. 재고가 모자라면 조건이 깨져 영향 행 0 이 된다.
     * (같은 모양의 SQL 을 W1D2 RefundRaceTest 에서 실측 검증했다.)
     *
     * clearAutomatically: 네이티브 UPDATE 는 영속성 컨텍스트를 모른다.
     *   비워주지 않으면 1차 캐시가 차감 전의 낡은 stock 을 계속 들고 있는다.
     * flushAutomatically: 이 UPDATE 전에 쌓인 변경을 먼저 DB 에 반영해 순서를 맞춘다.
     *
     * @return 영향 행 수. 1 = 차감 성공, 0 = 재고 부족(거부)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE product
               SET stock = stock - :quantity,
                   updated_at = now()
             WHERE id = :productId
               AND stock >= :quantity
            """, nativeQuery = true)
    int decreaseStock(@Param("productId") Long productId,
                      @Param("quantity") int quantity);

    /**
     * 재고 복원(주문 취소·미결제 쓸이). 상한이 없으므로 조건부가 아니다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE product
               SET stock = stock + :quantity,
                   updated_at = now()
             WHERE id = :productId
            """, nativeQuery = true)
    int increaseStock(@Param("productId") Long productId,
                      @Param("quantity") int quantity);
}
