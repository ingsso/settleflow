package com.settleflow.reconciliation.repository;

import com.settleflow.reconciliation.domain.ReconciliationRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.time.OffsetDateTime;
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
     * <b>지금 미해소인 불일치의 총 건수</b>(W4D2) — {@link #findUnresolvedDates()} 와 같은 정의를
     * 날짜가 아니라 건수로 센다.
     *
     * <h3>왜 이 쿼리가 새로 필요한가 — 이 값은 테이블에 바로 있지 않다</h3>
     * {@code reconciliation_run.discrepancy_count} 는 <b>run 한 번의 스냅샷</b>이다. 그냥 다 더하면
     * 같은 불일치를 며칠째 세는 만큼 중복으로 센다 — 관측을 쌓는 설계(W3D5)의 당연한 귀결이다.
     * "지금 수위"를 얻으려면 <b>날짜마다 최신 run 만 골라</b> 합쳐야 한다.
     *
     * <p><b>이게 메트릭에 Counter 를 못 쓰는 이유와 같은 뿌리다.</b> 재대사는 새 불일치가 아니라
     * 같은 불일치를 다시 본 것이라, 누적하면 해소돼도 안 내려간다. 여기서도 저기서도
     * <b>관측이 아니라 대상을 센다</b>.
     *
     * <p>스크레이프마다(15s) 돈다. 미해소 날짜는 설계상 소수이고 최신 run 만 보므로 가볍다.
     */
    @Query("""
            SELECT COALESCE(SUM(r.discrepancyCount), 0) FROM ReconciliationRun r
             WHERE r.discrepancyCount > 0
               AND r.id = (SELECT MAX(r2.id) FROM ReconciliationRun r2
                            WHERE r2.settlementDate = r.settlementDate)
            """)
    long sumUnresolvedDiscrepancies();

    /**
     * <b>대사가 마지막으로 돈 시각</b>(W4D2). 없으면 {@code null} — 한 번도 안 돌았다는 뜻이다.
     *
     * <h3>왜 불일치 건수만으로는 부족한가</h3>
     * 미해소 건수 게이지는 배치가 <b>3일째 멈춰 있어도 계속 0</b>을 돌려준다(아무도 해소를
     * 안 했으니 값이 안 변하는 게 <i>옳다</i>). DB 도 앱도 멀쩡히 응답하므로 어디에도 빨간불이
     * 안 뜬다 — <b>"깨끗하다"와 "아무도 안 봤다"가 같은 모양</b>이 되는 자리다.
     *
     * <p>W3D5 가 {@code ReconciliationRun} 테이블을 만든 이유가 <i>"부재에 뜻을 주기 위해"</i>
     * 였는데, 그건 <b>날짜 단위</b>의 부재였다. 배치 자체가 멈춘 부재는 그보다 한 층 위에 있고,
     * 이 값이 그 층을 덮는다: {@code time() - metric > 26h}.
     *
     * <p>run 은 불일치가 0 건이어도 남으므로({@code ReconciliationRun} javadoc) 이 시각은
     * 대사가 돌 때마다 앞으로 간다.
     */
    @Query("SELECT MAX(r.observedAt) FROM ReconciliationRun r")
    OffsetDateTime findLastObservedAt();

    /**
     * 그 날짜의 run 이력을 <b>최신순</b>으로. "며칠째 안 메워지는가"를 세는 데 쓴다.
     *
     * <p>세는 규칙은 {@code ReconciliationRecorder.openSince} 에 있다 — 최신부터 거꾸로 훑다가
     * <b>깨끗한 run 을 만나면 멈춘다</b>. 그래야 해소됐다 다시 터진 경우에 옛날 날짜가 아니라
     * 이번 구간의 시작이 나온다.
     */
    List<ReconciliationRun> findBySettlementDateOrderByIdDesc(LocalDate settlementDate);
}
