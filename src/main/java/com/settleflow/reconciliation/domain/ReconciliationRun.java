package com.settleflow.reconciliation.domain;

import jakarta.persistence.*;
import lombok.Getter;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 대사를 한 번 돈 기록(W3D5 ①, {@code V11}) — <b>불일치가 0 건이어도 남는다</b>.
 *
 * <h3>이 행이 없으면 "부재"에 뜻이 없다</h3>
 * 관측을 쌓는 방식(append-only)에서 해소는 <i>다음 관측에 안 나타남</i>인데, 그것만으로는
 * <b>대사가 돌았는데 깨끗했다</b> 와 <b>대사가 그 날짜를 아예 안 봤다</b> 를 못 가른다.
 * 이 테이블이 "이 날짜를 이때 봤다"를 남겨 부재에 뜻을 준다.
 *
 * <p>그게 없으면 "미해소 불일치가 있는 날짜"를 만들 수 없고, 그 목록이 대사의 날짜 출처
 * 셋 중 하나라 시스템 전체가 성립하지 않는다({@code ReconciliationRunner}).
 */
@Getter
@Entity
@Table(name = "reconciliation_run")
public class ReconciliationRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "settlement_date", nullable = false, updatable = false)
    private LocalDate settlementDate;

    @Column(name = "observed_at", nullable = false, updatable = false)
    private OffsetDateTime observedAt;

    @Column(name = "discrepancy_count", nullable = false, updatable = false)
    private int discrepancyCount;

    /**
     * run 이 자기 관측을 소유한다. 관측은 run 없이는 뜻이 없으므로 cascade 로 함께 저장된다
     * ({@code Journal} → {@code LedgerEntry} 와 같은 모양).
     */
    @OneToMany(mappedBy = "run", cascade = CascadeType.ALL)
    private List<DiscrepancyObservation> observations = new ArrayList<>();

    protected ReconciliationRun() {}

    private ReconciliationRun(LocalDate settlementDate) {
        this.settlementDate = settlementDate;
    }

    /**
     * 대사 결과 한 번을 기록으로 만든다.
     *
     * <p>세터가 없다 — 이 기록은 만들어진 뒤 안 바뀐다(append-only). 해소는 이 행을 고쳐서가
     * 아니라 <b>다음 run 에 관측이 없는 것</b>으로 표현된다.
     */
    public static ReconciliationRun of(LocalDate settlementDate, List<Discrepancy> found) {
        ReconciliationRun run = new ReconciliationRun(settlementDate);
        run.discrepancyCount = found.size();
        found.forEach(d -> run.observations.add(DiscrepancyObservation.of(run, d)));
        return run;
    }

    @PrePersist
    void onCreate() {
        this.observedAt = OffsetDateTime.now();
    }

    /** 이번 run 이 깨끗했는가. 해소 판정의 단위다. */
    public boolean isClean() {
        return discrepancyCount == 0;
    }
}
