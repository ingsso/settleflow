package com.settleflow.seller.repository;

import com.settleflow.seller.domain.Seller;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 셀러 조회(W5D5). 다섯 주 동안 없었다 — 셀러는 늘 {@code Product}·{@code LedgerEntry} 를
 * 통해 딸려왔고, <b>id 로 직접 찾을 이유가 구독 생성에서 처음 생겼다.</b>
 */
public interface SellerRepository extends JpaRepository<Seller, Long> {
}
