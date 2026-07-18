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

    /**
     * 결제 확정 사건. 전이표(W1D5):
     *
     * <pre>
     *   현재 \ 사건 | 결제확정  | 취소
     *   CREATED    | → PAID   | → CANCELED
     *   PAID       | IGNORED  | 미정
     *   CANCELED   | CONFLICT | IGNORED
     * </pre>
     *
     * <p>거절은 열에 없다 — 결제가 거절돼도 주문은 CREATED 로 남는다. Order↔Payment 가 1:N 인
     * 이유가 그것이다(W1D1): 실패 기록을 보존하고 재결제를 허용한다.
     *
     * <p><b>CANCELED + 결제확정 = CONFLICT.</b> 쓸이 배치가 주문을 취소하며 재고를 복원한 뒤
     * 늦은 승인 웹훅이 도착한 경우다. 재고는 이미 남에게 갔는데 주문만 PAID 가 되면 이중판매다
     * (W1D3 §2). 상태를 바꾸지 않고 호출부가 기록·알람하게 한다(W1D1 §4 규칙 2).
     *
     * <p><b>예외를 던지지 않는 이유</b>: 이 메서드는 결제 전이 트랜잭션 안에서 불린다
     * ({@code PaymentTransitionService#dispatch}). 예외를 던지면 PENDING → PAID 전이까지 롤백되어
     * <i>"결제는 승인됐다"</i>는 사실 자체가 사라진다 — CONFLICT 칸에서 반드시 남겨야 하는 것을
     * 예외가 지운다(W1D3 §5와 같은 이유).
     */
    public OrderTransitionResult onPaymentConfirmed() {
        if (this.status == OrderStatus.PAID) return OrderTransitionResult.IGNORED;
        else if (this.status == OrderStatus.CANCELED) return OrderTransitionResult.CONFLICT;

        this.status = OrderStatus.PAID;
        return OrderTransitionResult.TRANSITIONED;
    }

    /**
     * 주문 취소. <b>아직 호출부가 없다</b> — 쓸이 배치도 취소 API 도 없다.
     *
     * <p>가드를 넣지 않은 이유: 전이표의 PAID+취소 칸이 미정이다. 그 칸은 "무시"가 아니라
     * "요청을 거절한다"에 가까운데(IGNORED 를 주면 호출부는 취소된 줄 안다), 결과값을 뭘로 할지는
     * 호출부가 그걸로 뭘 하는지 봐야 정해진다. 호출부가 생길 때 함께 확정한다.
     */
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
