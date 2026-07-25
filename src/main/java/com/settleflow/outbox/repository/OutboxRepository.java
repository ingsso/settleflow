package com.settleflow.outbox.repository;

import com.settleflow.outbox.domain.OutboxEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * 미발행 이벤트를 <b>id 오름차순</b>(= 삽입 순서)으로 긁는다. 릴레이가 이 순서로 발행하면
     * 같은 aggregate 안에서 발생 순서가 보존된다. 배치 크기는 {@link Pageable} 로 제한한다.
     */
    @Query("SELECT o FROM OutboxEvent o WHERE o.publishedAt IS NULL ORDER BY o.id ASC")
    List<OutboxEvent> findUnpublished(Pageable pageable);
}
