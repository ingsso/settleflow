package com.settleflow.reconciliation.domain;

import jakarta.persistence.*;
import lombok.Getter;

/**
 * 불일치 1건을 <b>한 번 관측한</b> 기록(W3D5 ①, {@code V11}).
 *
 * <p>{@link Discrepancy} 와 필드가 거의 같은데 따로 있는 이유는 <b>시점이 붙기 때문</b>이다.
 * {@code Discrepancy} 는 "지금 계산해보니 다르다"는 값이고, 이건 "언제 봤을 때 달랐다"는
 * 사실이다. 시점은 {@link #run} 이 들고 있다 — 관측은 run 없이는 뜻이 없다.
 *
 * <p>세터가 없고 갱신도 없다. 불일치가 해소돼도 이 행은 안 바뀐다(W2D1 §5 append-only).
 * 해소는 <b>다음 run 에 같은 관측이 없는 것</b>으로 나타난다.
 */
@Getter
@Entity
@Table(name = "discrepancy_observation")
public class DiscrepancyObservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", updatable = false)
    private ReconciliationRun run;

    @Column(name = "seller_id", nullable = false, updatable = false)
    private Long sellerId;

    @Column(name = "type", nullable = false, updatable = false)
    @Enumerated(EnumType.STRING)
    private DiscrepancyType type;

    /** 배치가 굳힌 값. 정산 행이 없었으면 null. */
    @Column(name = "settled", updatable = false)
    private Long settled;

    /** {@code payment}+{@code refund} 에서 독립적으로 다시 만든 값. 없었으면 null. */
    @Column(name = "reconstructed", updatable = false)
    private Long reconstructed;

    /** {@code settled − reconstructed}. <b>양수면 과지급 방향</b>(W3D3 §8 ③). */
    @Column(name = "difference", nullable = false, updatable = false)
    private long difference;

    protected DiscrepancyObservation() {}

    static DiscrepancyObservation of(ReconciliationRun run, Discrepancy d) {
        DiscrepancyObservation o = new DiscrepancyObservation();
        o.run = run;
        o.sellerId = d.sellerId();
        o.type = d.type();
        o.settled = d.settled();
        o.reconstructed = d.reconstructed();
        o.difference = d.difference();
        return o;
    }
}
