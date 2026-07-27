package com.settleflow.settlement.domain;

import jakarta.persistence.*;
import lombok.Getter;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 하루치 셀러 정산(W3D3). (셀러, 날짜) 하나당 한 행.
 *
 * <p><b>파생 캐시가 아니다.</b> W1D3(재고 Redis)·W2D4(환불 상태)에서 저장을 두 번 기각한 이유는
 * "원본과 항상 같아야 하는 값의 복제"였기 때문이다. 여기 저장하는 건 복제가 아니라 <b>"그날 우리가
 * 그렇게 판단했다"</b>는 별개의 사실이다 — 장부와 어긋날 수 있고, 어긋남 자체가 정보다.
 * 매번 SUM 으로 파생하면 늦게 커밋된 장부 줄 때문에 <b>같은 날의 값이 나중에 달라진다</b>.
 *
 * <p><b>생성자가 없다.</b> 이 엔티티는 읽기 창일 뿐이고, 행은 오직
 * {@code SettlementRepository.upsertDailyNetBySeller} 의 {@code INSERT ... ON CONFLICT} 한 문장만
 * 만든다. 앱에서 만들 길을 열면 집계식이 두 군데로 갈라진다.
 */
@Getter
@Entity
@Table(name = "settlement")
public class Settlement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * {@code @ManyToOne} 이 아니라 생 {@code Long} — {@link com.settleflow.ledger.domain.LedgerEntry}
     * 와 같은 선택이다. 정산은 Seller 로 항해하지 않고 seller_id 로 집계·조인할 뿐이다.
     * 무결성은 DB FK(V8)가 지킨다.
     */
    @Column(name = "seller_id", nullable = false)
    private Long sellerId;

    /**
     * 정산 대상 날짜. {@code DATE} 라 <b>이 값 안에 타임존이 없다</b> — "KST 기준 며칠"이라는 뜻은
     * {@code SettlementService.ZONE} 한 곳에만 존재한다. 그 상수가 두 군데로 흩어지면 26일이 둘이 된다.
     */
    @Column(name = "settlement_date", nullable = false)
    private LocalDate settlementDate;

    /**
     * 셀러에게 줄 금액 = 그날 {@code SELLER_PAYABLE} 의 CREDIT − DEBIT.
     * 수수료를 뗀 net 이고, 환불 역분개(DEBIT)가 이미 빠져 있다.
     *
     * <p><b>음수일 수 있다.</b> 판 것 없이 환불만 있는 날이 그렇다. 그래서 이름이 "지급액"이 아니라
     * 정산액이고, V8 에 {@code CHECK (net_amount > 0)} 을 걸지 않았다.
     */
    @Column(name = "net_amount", nullable = false)
    private Long netAmount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** journal/ledger_entry 와 달리 이 행은 변한다(재실행 시 덮어쓰기). */
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected Settlement() {}
}
