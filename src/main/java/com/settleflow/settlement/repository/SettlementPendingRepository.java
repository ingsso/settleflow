package com.settleflow.settlement.repository;

import com.settleflow.settlement.domain.SettlementPending;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

public interface SettlementPendingRepository extends JpaRepository<SettlementPending, Long> {

    /**
     * 아직 안 센 journal 들의 <b>시각</b>을 준다 — 날짜가 아니라 시각이다.
     *
     * <p>SQL 에서 {@code (created_at AT TIME ZONE 'Asia/Seoul')::date} 로 바로 날짜를 뽑을 수도
     * 있었다. 안 한 이유는 <b>존이 SQL 로 새기 때문</b>이다. V8 이 못박은 대로 "KST 기준 며칠"의
     * 뜻은 {@code SettlementService.ZONE} 한 곳에만 살아야 하고, 그러려면 존을 파라미터로
     * 바인딩하거나 변환 자체를 자바에 두는 수밖에 없다. 후자를 골랐다 — 바인딩은 거처를 하나로
     * 지키긴 하지만 <b>존이 무엇인지 아는 코드가 SQL 안에 남는다</b>.
     *
     * <p>대가는 날짜 D 개가 아니라 시각 N 개가 올라온다는 것. 그런데 이 테이블은 설계상
     * <b>거의 비어 있다</b>(처리하면 지우는 큐라서) — 상시 N ≈ 그날 밀린 것뿐이다. 예외는
     * {@code V10} 의 backfill 직후 한 번인데, 그때는 어차피 과거 전체를 한 번 돌아야 한다.
     *
     * <p>{@link Instant} 인 이유: 네이티브 스칼라 조회에서 Hibernate 는 {@code timestamptz} 를
     * {@code Instant} 로 읽는다 — {@code OffsetDateTime} 으로 선언하면 Spring Data 가
     * {@code ConverterNotFoundException} 을 던진다(실측으로 확인). 그리고 이쪽이 의미상으로도
     * 맞다: 필요한 건 <b>순간</b>이고, 그 순간을 어느 존의 며칠로 부를지는
     * {@code SettlementService.pendingDates()} 가 {@code ZONE} 으로 정한다.
     */
    @Query(value = """
            SELECT DISTINCT j.created_at
              FROM settlement_pending sp
              JOIN journal j ON j.id = sp.journal_id
            """, nativeQuery = true)
    List<Instant> findPendingJournalTimestamps();

    /**
     * 그 날짜 범위의 표식을 지운다 = "이 줄들은 셌다".
     *
     * <p><b>반드시 집계와 같은 트랜잭션·같은 스냅샷에서 불려야 한다</b>({@code SettlementService.settle}).
     * 두 오류 방향이 대칭이 아니기 때문이다 — 안 셌는데 지우면 그 줄은 증발하고(치명), 셌는데
     * 안 지우면 같은 값을 다시 쓸 뿐이다(무해). {@code READ COMMITTED} 였으면 집계 이후 커밋된
     * 줄이 <b>집계엔 안 들어갔는데 이 DELETE 엔 잡혀서</b> 구멍이 그대로 부활한다.
     *
     * <p>{@code REPEATABLE READ} 에서는 트랜잭션의 첫 문장이 뜬 스냅샷을 이후 모든 문장이
     * 재사용하므로, 그 사이 커밋된 줄은 이 DELETE 에 <b>보이지 않는다</b>. 표식이 남고 다음
     * 배치가 제 날짜로 주워간다.
     *
     * <p>범위를 시각({@code from}/{@code to})으로 받으므로 여기엔 존이 필요 없다 — 경계 계산은
     * 이미 {@code SettlementService} 에서 끝나 있다.
     *
     * @return 지운 행 수. 그 날짜에 <b>새로 센 장부 줄 수</b>다(이미 세진 줄은 행이 없다).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            DELETE FROM settlement_pending sp
             USING journal j
             WHERE j.id = sp.journal_id
               AND j.created_at >= :from
               AND j.created_at <  :to
            """, nativeQuery = true)
    int clearSettled(@Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to);
}
