package com.settleflow.reconciliation.service;

import com.settleflow.reconciliation.domain.Discrepancy;
import com.settleflow.reconciliation.domain.ReconciliationRun;
import com.settleflow.reconciliation.repository.ReconciliationRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 대사를 돌리고 <b>결과를 기록</b>한다(W3D5 ①) — 계획서의 "불일치 건 자동 플래그".
 *
 * <p>{@link ReconciliationService} 는 비교만 하고 목록을 돌려준다(W3D4). 그 앞뒤로 "기록"을
 * 붙이는 게 이 클래스다. 나누는 이유는 <b>비교가 부수효과 없이 남아야</b> 테스트와 조사에서
 * 아무 때나 부를 수 있기 때문이다 — 대사를 한 번 볼 때마다 행이 쌓이면 곤란하다.
 *
 * <p><b>고치지 않는다.</b> W3D4 가 적어둔 그대로 — 어긋남은 조사 대상이지 정정 대상이 아니다.
 * 무엇이 맞는지는 대사가 알 수 없다.
 */
@Service
public class ReconciliationRecorder {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationRecorder.class);

    private final ReconciliationService reconciliationService;
    private final ReconciliationRunRepository runRepository;

    public ReconciliationRecorder(ReconciliationService reconciliationService,
                                  ReconciliationRunRepository runRepository) {
        this.reconciliationService = reconciliationService;
        this.runRepository = runRepository;
    }

    /**
     * 한 날짜를 대사하고 결과를 남긴다. <b>불일치가 0 건이어도 run 은 남는다</b> — 부재에 뜻을
     * 주는 게 이 기록의 존재 이유다({@link ReconciliationRun} javadoc).
     *
     * @return 이번에 저장된 run
     */
    @Transactional
    public ReconciliationRun record(LocalDate date) {
        List<Discrepancy> found = reconciliationService.reconcileSettlement(date);
        ReconciliationRun run = runRepository.save(ReconciliationRun.of(date, found));

        if (found.isEmpty()) {
            return run;
        }

        long days = openSince(date);
        log.warn("[대사 플래그] date={} 불일치={}건 {}일째 미해소 {}",
                date, found.size(), days, found);
        return run;
    }

    /**
     * 이 날짜의 불일치가 <b>며칠째</b> 안 메워지고 있는가.
     *
     * <p>run 이력을 최신부터 거꾸로 훑다가 <b>깨끗한 run 을 만나면 멈춘다</b>. 전체 이력의
     * {@code MIN} 을 쓰면 해소됐다 다시 터진 경우에 옛날 날짜가 나와 숫자가 부풀려진다 —
     * 세고 싶은 건 "이 사건이 며칠째냐"지 "이 날짜가 언제 처음 문제였냐"가 아니다.
     *
     * <p>당일에 처음 뜨면 0 이다. 이 값의 쓸모는 <b>임계값</b>에 있다 — W3D5 ② 이후
     * {@code MISSING_IN_SETTLEMENT} 는 대체로 일시적이라(다음 배치가 줍는다) 한 번 떴다는 사실
     * 자체는 신호가 약하고, <b>안 사라진다는 사실</b>이 신호다.
     */
    long openSince(LocalDate date) {
        OffsetDateTime streakStart = null;
        for (ReconciliationRun r : runRepository.findBySettlementDateOrderByIdDesc(date)) {
            if (r.isClean()) {
                break;
            }
            streakStart = r.getObservedAt();
        }
        return streakStart == null ? 0
                : Duration.between(streakStart, OffsetDateTime.now()).toDays();
    }
}
