package com.settleflow.ledger;

import com.settleflow.ledger.domain.EntrySide;
import com.settleflow.ledger.domain.EntrySpec;
import com.settleflow.ledger.domain.Journal;
import com.settleflow.ledger.domain.LedgerAccount;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * {@code Journal.assertBalanced()} 가 실제로 이빨이 있는지 — 불균형 분개를 거부하는가.
 *
 * <p>순수 도메인 테스트(컨테이너·Spring 불필요). LedgerPostingTest 의 균형 초록불이
 * "가드 덕"인지 "입력이 우연히 맞아서"인지 구분하는 음성대조군이다: 일부러 대변을 모자라게 넣어
 * 빨간불(예외)이 나는지 본다.
 */
class JournalBalanceTest {

    @Test
    void 불균형_분개는_IllegalStateException으로_거부된다() {
        List<EntrySpec> unbalanced = List.of(
                new EntrySpec(LedgerAccount.CASH, EntrySide.DEBIT, 20_000L, null),
                new EntrySpec(LedgerAccount.SELLER_PAYABLE, EntrySide.CREDIT, 19_000L, 1L)
                // 수수료 줄 누락 → 대변 19,000 ≠ 차변 20,000
        );

        assertThatThrownBy(() -> Journal.forPayment(unbalanced))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("불균형");
    }

    @Test
    void 균형_분개는_통과하고_줄을_담는다() {
        List<EntrySpec> balanced = List.of(
                new EntrySpec(LedgerAccount.CASH, EntrySide.DEBIT, 20_000L, null),
                new EntrySpec(LedgerAccount.SELLER_PAYABLE, EntrySide.CREDIT, 19_000L, 1L),
                new EntrySpec(LedgerAccount.FEE_REVENUE, EntrySide.CREDIT, 1_000L, 1L)
        );

        assertThat(Journal.forPayment(balanced).getEntries()).hasSize(3);
    }
}
