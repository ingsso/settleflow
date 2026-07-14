package com.settleflow.order.domain;

import com.settleflow.product.domain.Product;
import jakarta.persistence.*;
import lombok.Getter;

import java.time.OffsetDateTime;

/**
 * 주문 시점의 상품 스냅샷.
 *
 * product FK를 들고 있으면서도 productName·unitPrice를 따로 복사해 둔다.
 * 상품 가격이 나중에 바뀌어도 과거 주문 금액이 소급 변경되면 안 되기 때문 —
 * Week 2 장부와 Week 3 대사가 기대는 기준값이 흔들린다.
 * (FK = "어떤 상품이었나", 스냅샷 = "그때 얼마였나")
 *
 * 생성 후 불변. updated_at 이 없는 것도 그래서다.
 * order.total_amount 비정규화가 안전한 유일한 근거가 이 불변성이다.
 */
@Getter
@Entity
@Table(name = "order_item")
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "product_name", nullable = false, length = 255)
    private String productName;

    @Column(name = "unit_price", nullable = false)
    private Long unitPrice;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected OrderItem() {   // JPA 전용
    }

    /** 스냅샷은 여기서 뜬다 — 호출부가 이름·가격을 따로 넘기지 못하게 Product에서 직접 읽는다. */
    OrderItem(Order order, Product product, int quantity) {
        this.order = order;
        this.product = product;
        this.productName = product.getName();
        this.unitPrice = product.getPrice();
        this.quantity = quantity;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = OffsetDateTime.now();
    }

    /** 이 항목이 주문 총액에 기여하는 금액. */
    public long subtotal() {
        return unitPrice * quantity;
    }
}
