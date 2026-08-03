package com.settleflow.reconciliation.repository;

import com.settleflow.reconciliation.domain.ReconciliationRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;

public interface ReconciliationRunRepository extends JpaRepository<ReconciliationRun, Long> {

    /**
     * <b>아직 안 메워진 불일치가 있는 날짜들</b> — 대사의 날짜 출처 셋 중 둘째.
     *
     * <p>정의: 그 날짜의 <b>가장 최근</b> run 에 관측이 하나라도 있으면 미해소다. 그보다 앞선
     * run 이 어땠는지는 상관없다 — 지금 어긋나 있느냐만 본다.
     *
     * <p>이 목록이 <b>스스로 살아남는 성질</b>을 만든다. 불일치가 뜬 날짜는 해소될 때까지 매번
     * 대사 대상에 다시 오르고, 그래서 "며칠째"가 계속 올라간다. 안 그러면 카운터가 1 에서 멈춘다
     * — 정산이 방금 돈 날짜만 보면 이미 정산이 끝난 날은 아무도 다시 안 보기 때문이다.
     * <b>W3D5 ② 의 {@code settlement_pending} 과 같은 모양</b>이다(끝나지 않은 것이 자기 날짜를
     * 계속 목록에 올린다).
     */
    @Query("""
            SELECT r.settlementDate FROM ReconciliationRun r
             WHERE r.discrepancyCount > 0
               AND r.id = (SELECT MAX(r2.id) FROM ReconciliationRun r2
                            WHERE r2.settlementDate = r.settlementDate)
             ORDER BY r.settlementDate
            """)
    List<LocalDate> findUnresolvedDates();

    /**
     * 그 날짜의 run 이력을 <b>최신순</b>으로. "며칠째 안 메워지는가"를 세는 데 쓴다.
     *
     * <p>세는 규칙은 {@code ReconciliationRecorder.openSince} 에 있다 — 최신부터 거꾸로 훑다가
     * <b>깨끗한 run 을 만나면 멈춘다</b>. 그래야 해소됐다 다시 터진 경우에 옛날 날짜가 아니라
     * 이번 구간의 시작이 나온다.
     */
    List<ReconciliationRun> findBySettlementDateOrderByIdDesc(LocalDate settlementDate);
}
