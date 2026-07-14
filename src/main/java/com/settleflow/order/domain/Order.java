package com.settleflow.order.domain;

import com.settleflow.product.domain.Product;
import jakarta.persistence.*;
import lombok.Getter;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 주문. 환불 상태는 저장하지 않고 refund 합계에서 파생시킨다(W1D1 §1).
 *
 * total_amount 는 비정규화 값이다. 성립 조건은 "order_item 은 생성 후 불변" —
 * 항목을 나중에 수정할 수 있게 되는 순간 총액이 드리프트하고 Week 3 대사가 깨진다.
 */
@Getter
@Entity
@Table(name = "orders")   // "order" 는 Postgres 예약어(ORDER BY)
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OrderStatus status;

    @Column(name = "total_amount", nullable = false)
    private Long totalAmount;

    @OneToMany(mappedBy = "order", cascade = CascadeType.PERSIST)
    private final List<OrderItem> items = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected Order() {   // JPA 전용
    }

    private Order(OrderStatus status) {
        this.status = status;
        this.totalAmount = 0L;
    }

    /** 빈 주문으로 시작해 항목을 채운다. 총액은 항목이 붙을 때마다 함께 누적된다. */
    public static Order create() {
        return new Order(OrderStatus.CREATED);
    }

    /** 항목 추가 시점에 스냅샷을 뜨고 총액을 누적한다 — 총액과 항목이 갈라질 틈을 없앤다. */
    public OrderItem addItem(Product product, int quantity) {
        OrderItem item = new OrderItem(this, product, quantity);
        items.add(item);
        this.totalAmount += item.subtotal();
        return item;
    }

    public void markPaid() {
        this.status = OrderStatus.PAID;
        this.updatedAt = OffsetDateTime.now();
    }

    public void cancel() {
        this.status = OrderStatus.CANCELED;
        this.updatedAt = OffsetDateTime.now();
    }

    public List<OrderItem> getItems() {
        return List.copyOf(items);   // 밖에서 항목을 추가·삭제하지 못하게
    }

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }
}
