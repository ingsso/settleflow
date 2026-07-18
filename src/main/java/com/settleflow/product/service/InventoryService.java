package com.settleflow.product.service;

import com.settleflow.product.repository.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.TreeMap;

/**
 * 재고 차감·복원.
 *
 * Redis 락을 쓰지 않는다(W1D3). 좌석 선점(지속·자동만료 필요)과 달리 재고 차감은 순간이라
 * 원자적 조건부 UPDATE 한 줄로 충분하고, 정합성은 DB 가 이미 보장한다.
 * Redis 를 얹으면 재고의 진실이 두 곳으로 갈라질 뿐이다.
 */
@Service
public class InventoryService {

    private final ProductRepository productRepository;

    public InventoryService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    /**
     * 여러 상품의 재고를 한 트랜잭션에서 차감한다.
     *
     * <p><b>productId 오름차순으로 정렬해서 처리한다.</b> UPDATE 는 행 락을 잡으므로,
     * 두 주문이 같은 상품 두 개를 서로 반대 순서로 건드리면 순환 대기 — 데드락이 난다.
     * (Postgres 가 감지해 한쪽을 죽이므로 멈추진 않지만, 그 주문은 이유 없이 실패한다.)
     * 모두가 같은 순서로 잡으면 순환 자체가 만들어지지 않는다.
     *
     * <p>재고가 모자라면 예외를 던져 트랜잭션 전체를 롤백한다 — 앞서 깎은 재고도 되돌아간다.
     *
     * @param quantities productId → 차감 수량
     */
    @Transactional
    public void decrease(Map<Long, Integer> quantities) {
        // TreeMap = productId 오름차순. 락 획득 순서를 전 코드 경로에서 통일한다.
        for (Map.Entry<Long, Integer> e : new TreeMap<>(quantities).entrySet()) {
            Long productId = e.getKey();
            int quantity = e.getValue();

            int affected = productRepository.decreaseStock(productId, quantity);
            if (affected == 0) {
                throw new OutOfStockException(productId, quantity);
            }
        }
    }

    /**
     * 주문 취소·미결제 쓸이에서 재고를 되돌린다.
     * 여기서도 같은 순서 규칙을 지킨다.
     */
    @Transactional
    public void restore(Map<Long, Integer> quantities) {
        for (Map.Entry<Long, Integer> e : new TreeMap<>(quantities).entrySet()) {
            productRepository.increaseStock(e.getKey(), e.getValue());
        }
    }
}
