package com.settleflow.settlement.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

/**
 * 아직 어느 정산에도 안 들어간 journal — 정산의 <b>진행</b> 축(W3D5, {@code V10}).
 *
 * <h3>이 엔티티는 애플리케이션이 INSERT 하지 않는다</h3>
 * 행을 넣는 건 {@code trg_journal_settlement_pending} 트리거뿐이다. 앱이 넣으면 journal 을
 * 만드는 새 경로가 생겼을 때 잊을 수 있고, <b>그 누락이 조용하다</b> — 그 장부 줄은 영원히
 * 안 세지는데 아무도 모른다. 그래서 잊는 것이 불가능한 자리로 옮겼다.
 *
 * <p>그래서 여긴 생성자도 세터도 없다. 앱이 이 테이블에 하는 일은 <b>읽기와 삭제</b>뿐이다.
 * 엔티티로 매핑해 둔 이유는 그 둘을 리포지토리로 쓰기 위해서고, 덤으로
 * {@code ddl-auto=validate} 가 스키마와의 어긋남을 컨텍스트 로딩에서 잡아준다.
 *
 * <h3>날짜 컬럼이 없는 게 판단이다</h3>
 * V8 이 <i>"KST 기준 며칠"이라는 뜻은 {@code SettlementService.ZONE} 한 곳에만 산다</i> 고
 * 못박았다. 여기 {@code settlement_date} 를 담으면 그 계산이 두 번째 거처가 되고, 트리거가
 * 채우려면 SQL 안에 {@code 'Asia/Seoul'} 이 박힌다. {@code journal_id} 만 들고, 날짜 변환은
 * {@code SettlementService.pendingDates()} 가 자바에서 한다.
 */
@Getter
@Entity
@Table(name = "settlement_pending")
public class SettlementPending {

    @Id
    @Column(name = "journal_id")
    private Long journalId;

    protected SettlementPending() {}
}
