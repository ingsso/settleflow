package com.settleflow.settlement.repository;

import com.settleflow.settlement.domain.Settlement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface SettlementRepository extends JpaRepository<Settlement, Long> {

    /**
     * 하루치 셀러별 정산을 집계해 <b>한 문장으로</b> 써넣는다(W3D3의 심장부).
     *
     * <p>오늘의 설계 판단 다섯이 전부 이 SQL 안에 있다:
     * <ol>
     *   <li><b>원천은 장부</b> — {@code payment}/{@code refund} 가 아니라 {@code ledger_entry}.
     *       그래서 환불이 "어느 날에 귀속되는지"를 손으로 정할 필요가 없다. 역분개 journal 이 찍힌
     *       날에 자동으로 빠진다. 또 {@code Payment.refundStatus()} 를 한 번도 안 쓴다 — W3D2 가
     *       남긴 {@code refunded_amount} 의미 변화(환불된 + <i>진행중인</i>) 문제를 오늘 안 만난다.</li>
     *   <li><b>{@code SELLER_PAYABLE} 만</b> — net(셀러에게 줄 돈)을 저장한다. gross 는
     *       {@code + FEE_REVENUE} 로 언제든 다시 SUM 해도 같은 값이라 파생해도 안전하고,
     *       net 은 확정해야 하는 약속이라 스냅샷이 필요하다.</li>
     *   <li><b>CREDIT − DEBIT</b> — SELLER_PAYABLE 은 부채다. 결제가 CREDIT(빚 증가),
     *       환불 역분개가 DEBIT(빚 감소). 그 차가 줄 돈이다.</li>
     *   <li><b>{@code >= from AND < to}</b> — 반열림. 양쪽을 닫으면 이웃한 두 날이 1초를 나눠 갖는다.</li>
     *   <li><b>{@code ON CONFLICT DO UPDATE}</b> — 재실행은 덮어쓴다. V8 의 UNIQUE 가 없으면 이
     *       선택 자체가 성립하지 않는다(충돌을 감지할 제약이 있어야 동작한다).</li>
     * </ol>
     *
     * <p><b>덮어쓰기가 복구 수단이다.</b> {@code journal.created_at} 은 persist 시점에 찍히지만 그
     * 행이 남에게 보이는 건 커밋 시점이라, 경계 직전에 찍히고 배치 뒤에 커밋된 줄을 배치가 못 본다
     * (타임스탬프 순서 ≠ 가시성 순서). 누적(행 둘)이면 재실행이 이중계상이고, 거부면 영영 못 고친다.
     * <b>덮어쓰기만 "한 번 더 돌리면 메워진다"</b>. 근본 해결은 W3D5(스냅샷 기준 조회)이고, 그때까지
     * 이 구멍은 열려 있다 — W3D4 대사에 "장부엔 있는데 정산엔 없는 건"으로 나타난다.
     *
     * <p><b>이 덮어쓰기는 "아직 아무에게도 통보하지 않는다"는 전제 위에 서 있다.</b> 송금·통보
     * 단계가 생기면 확정 상태를 두고 그 이후의 덮어쓰기를 막아야 한다(값이 조용히 바뀌는 것은
     * append-only 규율과 정면으로 부딪힌다 — W2D1 §5 "지우개가 아니라 빨간 펜").
     *
     * <p>{@code flushAutomatically}: 같은 트랜잭션에서 방금 만든 journal 이 아직 flush 되지 않았으면
     * 이 네이티브 쿼리가 못 본다. {@code clearAutomatically}: 네이티브 UPDATE 는 영속성 컨텍스트를
     * 모르므로 낡은 Settlement 를 들고 있지 않게 비운다(W2D4 {@code em.refresh} 와 같은 대가).
     *
     * @return 써넣은 행 수 = 그날 장부에 등장한 셀러 수(INSERT + UPDATE 합)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            INSERT INTO settlement (seller_id, settlement_date, net_amount)
            SELECT le.seller_id,
                   CAST(:date AS date),
                   SUM(CASE WHEN le.dr_cr = 'CREDIT' THEN le.amount ELSE -le.amount END)
              FROM ledger_entry le
              JOIN journal j ON j.id = le.journal_id
             WHERE le.account = 'SELLER_PAYABLE'
               AND j.created_at >= :from
               AND j.created_at <  :to
             GROUP BY le.seller_id
            ON CONFLICT ON CONSTRAINT uq_settlement_date_seller
            DO UPDATE SET net_amount = EXCLUDED.net_amount,
                          updated_at = now()
            """, nativeQuery = true)
    int upsertDailyNetBySeller(@Param("date") LocalDate date,
                               @Param("from") OffsetDateTime from,
                               @Param("to") OffsetDateTime to);

    List<Settlement> findBySettlementDateOrderBySellerId(LocalDate settlementDate);

    Optional<Settlement> findBySettlementDateAndSellerId(LocalDate settlementDate, Long sellerId);
}
