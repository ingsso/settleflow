package com.settleflow.product.domain;

import com.settleflow.seller.domain.Seller;
import jakarta.persistence.*;
import lombok.Getter;

import java.time.OffsetDateTime;

@Getter
@Entity
@Table(name = "product")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "seller_id", nullable = false)
    private Seller seller;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "price", nullable = false)
    private Long price;

    /**
     * 재고 갱신 경로는 원자적 조건부 UPDATE(네이티브 SQL) 하나뿐이다.
     * updatable = false 로 JPA의 더티 체킹이 이 컬럼을 건드리지 못하게 막는다.
     * (막지 않으면 낡은 엔티티가 flush될 때 차감된 재고를 옛날 값으로 되돌린다.)
     * -- GETTER --
     * 읽기 전용. 차감은 ProductRepository의 조건부 UPDATE로만 한다.

     */
    @Column(name = "stock", nullable = false, updatable = false)
    private Integer stock;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected Product() {   // JPA 전용
    }

    public Product(Seller seller, String name, Long price, Integer stock) {
        this.seller = seller;
        this.name = name;
        this.price = price;
        this.stock = stock;
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
