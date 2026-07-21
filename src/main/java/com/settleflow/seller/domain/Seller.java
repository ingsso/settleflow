package com.settleflow.seller.domain;

import jakarta.persistence.*;
import lombok.Getter;

import java.time.OffsetDateTime;

@Getter
@Entity
@Table(name = "seller")
public class Seller {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    /**
     * 수수료율 — basis point 정수(500 = 5%). V4 에서 추가.
     * JPA 가 INSERT 에 이 컬럼을 포함하므로 DB DEFAULT 500 은 생 INSERT 에만 먹는다 —
     * 엔티티로 만들 땐 생성자가 기본값을 넣어 같은 의도를 유지한다(계약율이 다르면 오버로드로 명시).
     */
    @Column(name = "fee_rate", nullable = false)
    private int feeRate;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    private static final int DEFAULT_FEE_RATE_BP = 500;

    protected Seller() {   // JPA 전용
    }

    public Seller(String name) {
        this(name, DEFAULT_FEE_RATE_BP);
    }

    public Seller(String name, int feeRate) {
        this.name = name;
        this.feeRate = feeRate;
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
