package com.settleflow.subscription.service;

import com.settleflow.payment.domain.Payment;
import com.settleflow.payment.pg.PgClient;
import com.settleflow.payment.pg.PgResult;
import com.settleflow.payment.pg.PgTimeoutException;
import com.settleflow.payment.repository.PaymentRepository;
import com.settleflow.payment.service.PaymentTransitionService;
import com.settleflow.subscription.domain.BillingCycle;
import com.settleflow.subscription.domain.Subscription;
import com.settleflow.subscription.domain.SubscriptionStatus;
import com.settleflow.subscription.domain.SubscriptionTransitionResult;
import com.settleflow.subscription.repository.BillingCycleRepository;
import com.settleflow.subscription.repository.SubscriptionRepository;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 구독 청구 한 건을 끝까지 밀어붙인다(W5D2). {@code DunningScheduler} 가 <i>무엇을</i> 집을지
 * 정하고, 여기가 <i>집은 것 하나</i>를 처리한다.
 *
 * <h3>트랜잭션 3단 — {@code PaymentService.pay()} 와 같은 모양이다</h3>
 * <pre>
 *   tx1: 시계를 밀어 커밋(리스) + PENDING payment 행 INSERT
 *   ----- 트랜잭션 경계 -----
 *   PG 동기 호출 (느리고 터질 수 있다)
 *   ----- 트랜잭션 경계 -----
 *   tx2: 결과 반영 (PaymentTransitionService)
 * </pre>
 *
 * <p>PG 호출을 tx 안에 두면 안 되는 이유가 여기선 <b>둘</b>이다. W1D1 의 이유(터지면 롤백돼
 * PENDING 행이 사라지고 웹훅이 매칭할 대상을 잃는다)에, 리스 때문에 하나가 더 붙는다 —
 * <b>리스는 커밋돼야 리스다.</b> 같은 트랜잭션 안에 두면 PG 호출 중에는 아무도 그 리스를 볼 수
 * 없어 다른 인스턴스가 같은 주기를 집는다.
 *
 * <h3>왜 리스인가 — 낙관적 락으로는 안 되는 이유</h3>
 * 인스턴스 둘이 같은 주기를 집으면 <b>둘 다 PG 를 부른다</b>. 커밋 시점에 {@code @Version} 이
 * 하나를 롤백시키지만 <b>나간 돈은 안 돌아온다</b> — 롤백은 우리 DB 만 되돌린다.
 * W1D4 {@code dispatch()} 는 [판정 → 부수효과]라 락이 편이었는데, 폴링은 [부수효과 → 판정]이라
 * 순서가 뒤집혀 있다. <b>락이 약해진 게 아니라 순서가 바뀌었다.</b>
 *
 * <p>PG 멱등키도 못 막는다: {@code merchantPaymentId} 는 시도마다 새로 발급되므로
 * ({@code Payment} 생성자) 두 인스턴스가 만든 두 시도는 PG 입장에서 <b>서로 다른 정당한 청구</b>다.
 * 그래서 배타성은 <b>집는 단계</b>에서 확보한다 — 돈이 나가기 전에 커밋되는 사실을 만든다.
 */
@Service
public class SubscriptionChargeService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionChargeService.class);

    /**
     * 리스 기간 = <b>청구 한 번에 걸릴 최대 시간</b>. 재시도 간격과 <b>다른 축</b>이라
     * {@link DunningPolicy} 에 두지 않았다 — 저건 도메인 정책이고 이건 운영 상수다.
     *
     * <p>짧으면 느린 PG 응답 중에 남이 같은 주기를 집고, 길면 크래시한 주기가 그만큼 멈춘다.
     * PG 타임아웃보다 넉넉해야 한다는 게 유일한 제약이다.
     */
    private static final Duration LEASE = Duration.ofMinutes(5);

    private final PgClient pgClient;
    private final DunningPolicy policy;
    private final SubscriptionRepository subscriptionRepository;
    private final BillingCycleRepository billingCycleRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentTransitionService transitions;
    private final TransactionTemplate txTemplate;

    public SubscriptionChargeService(PgClient pgClient,
                                     DunningPolicy policy,
                                     SubscriptionRepository subscriptionRepository,
                                     BillingCycleRepository billingCycleRepository,
                                     PaymentRepository paymentRepository,
                                     PaymentTransitionService transitions,
                                     PlatformTransactionManager txManager) {
        this.pgClient = pgClient;
        this.policy = policy;
        this.subscriptionRepository = subscriptionRepository;
        this.billingCycleRepository = billingCycleRepository;
        this.paymentRepository = paymentRepository;
        this.transitions = transitions;
        this.txTemplate = new TransactionTemplate(txManager);
    }

    /**
     * 정기 청구가 도래한 구독 하나를 처리한다(P1).
     *
     * <p>한 번에 청구가 <b>둘 이상</b> 나갈 수 있다. 열린 {@code PENDING} 주기가 남아 있으면
     * 그것도 함께 회수하기 때문이다(결정 4·5) — 새 주기를 안 만들고 회수만 하면 그 달치를
     * 영영 못 받고, 그 사실이 어디에도 안 남는다. <b>둘은 다른 기간의 돈</b>이라 둘 다 나간다.
     *
     * <p>회수 대상은 {@code PENDING} 뿐이다. {@code FAILED} 주기는 자기 시계가 있어 P2 가
     * 집으므로, 여기서 손대면 <b>두 폴러가 같은 주기를 노린다.</b>
     */
    public void startDueCycle(Long subscriptionId, LocalDate today) {
        List<Attempt> attempts = txTemplate.execute(tx -> claimAndStage(subscriptionId, today));
        if (attempts == null) return;
        attempts.forEach(this::charge);
    }

    /**
     * 재시도할 실패 주기 하나를 처리한다(P2).
     *
     * <p>여기서도 상한을 본다. 정상 경로의 상한 판정은 실패 통보 직후에 있지만
     * ({@link #concludeFailure}), {@code UNKNOWN} 은 주기에 통보되지 않아 그 자리에 도달하지
     * 못한다 — 그러면 리스가 만료될 때마다 이 주기가 영원히 다시 집힌다.
     * <b>{@code UNKNOWN} 을 시도로 세기로 한 결정은 이 백스톱이 있어야 값을 낸다.</b>
     */
    public void retryCycle(Long cycleId, OffsetDateTime now) {
        Attempt attempt = txTemplate.execute(tx -> {
            if (billingCycleRepository.claimForRetry(cycleId, now, now.plus(LEASE)) == 0) {
                return null;   // 남이 먼저 집었거나 그 사이 종착지로 갔다
            }
            return stageAttempt(cycleId);
        });
        if (attempt == null) return;
        charge(attempt);
    }

    /**
     * tx1 — 구독 시계를 밀어 집고(CAS), 이번에 나갈 시도들의 {@code PENDING} 행을 만든다.
     *
     * @return 청구할 시도 목록. {@code null} 이면 내가 못 집은 것이다
     */
    private List<Attempt> claimAndStage(Long subscriptionId, LocalDate today) {
        Subscription probe = subscriptionRepository.findById(subscriptionId).orElse(null);
        if (probe == null || probe.getStatus() != SubscriptionStatus.ACTIVE) return null;

        LocalDate due = probe.getNextBillingDate();
        if (due == null || due.isAfter(today)) return null;
        LocalDate next = due.plusDays(probe.getIntervalDays());

        // 돈이 나가기 전에 커밋되는 사실. 진 인스턴스는 0 행을 잡고 물러난다.
        if (subscriptionRepository.claimBillingDate(subscriptionId, due, next) == 0) return null;

        // 위 네이티브 UPDATE 가 영속성 컨텍스트를 비웠으므로(clearAutomatically) 다시 읽는다.
        Subscription subscription = subscriptionRepository.findById(subscriptionId).orElseThrow();

        List<Attempt> attempts = new ArrayList<>();
        for (Long openCycleId : billingCycleRepository.findOpenPendingCycleIds(subscriptionId)) {
            Attempt recovered = stageAttempt(openCycleId);
            if (recovered != null) attempts.add(recovered);
        }

        BillingCycle cycle = new BillingCycle(subscription,
                billingCycleRepository.nextCycleNo(subscriptionId),
                subscription.getAmount(), due, next);
        billingCycleRepository.save(cycle);
        attempts.add(newAttempt(cycle));

        return attempts;
    }

    /**
     * 주기 하나를 다음으로 밀 준비를 한다. <b>새 시도를 만들기 전에 미결 시도가 있는지 본다</b>(W5D4).
     *
     * <h3>미결 시도가 있으면 재전송이지 새 청구가 아니다</h3>
     * {@code PENDING}·{@code UNKNOWN} 은 <i>"돈이 움직였는지 모른다"</i> 이고, 모르는 상태에서
     * 새 {@code merchantPaymentId} 로 청구를 내면 PG 는 그걸 <b>정당한 별개 청구</b>로 받는다
     * (W5D3 [실측]: 크래시 뒤 재집기 → PG approve 3회 · 서로 다른 키 3개).
     *
     * <p>같은 키로 다시 보내면 그건 <b>같은 시도의 재전송</b>이고, 멱등키가 정확히 그걸 위해
     * 있는 물건이다. <b>시도 층은 안 무너진다</b> — 행이 안 늘고, 상한 계산도 안 흔들리며
     * ({@code PENDING} 은 애초에 안 세고 {@code UNKNOWN} 은 이미 세어졌다),
     * 결과 통보는 그 {@code payment} 행의 전이표가 흡수한다.
     *
     * <h3>재전송에는 상한을 안 건다</h3>
     * 상한은 <i>"청구를 몇 번 걸었나"</i> 를 세는데 재전송은 청구를 안 늘린다. 답을 얻을
     * 때까지 계속 묻는다 — <b>무한 질의는 무한 청구와 다르다.</b> 대신 조용하면 안 되므로
     * {@code DunningScheduler} 가 오래된 미결 시도를 센다.
     *
     * <p>{@code null} 반환은 "이 주기는 이제 청구하지 않는다"는 뜻이다.
     */
    private Attempt stageAttempt(Long cycleId) {
        BillingCycle cycle = billingCycleRepository.findById(cycleId).orElseThrow();

        Optional<Long> unresolved = paymentRepository.findUnresolvedAttemptId(cycleId);
        if (unresolved.isPresent()) {
            Payment attempt = paymentRepository.findById(unresolved.get()).orElseThrow();
            log.info("미결 시도 재전송 billingCycleId={} paymentId={} status={} — 새 청구가 아니다",
                    cycleId, attempt.getId(), attempt.getStatus());
            return new Attempt(attempt.getMerchantPaymentId(), attempt.getAmount(), cycleId, true);
        }

        int concluded = paymentRepository.countConcludedAttempts(cycleId);
        if (!policy.hasRetryLeft(concluded)) {
            abandon(cycle, concluded);
            return null;
        }
        return newAttempt(cycle);
    }

    private Attempt newAttempt(BillingCycle cycle) {
        Payment payment = new Payment(cycle, cycle.getAmount());
        paymentRepository.save(payment);
        return new Attempt(payment.getMerchantPaymentId(), payment.getAmount(), cycle.getId(), false);
    }

    /**
     * 시도를 더 걸지 않고 주기를 포기한다. {@code payment} 행 없이 부르는 유일한 경로다 —
     * 상한 초과가 <b>결과 통보가 아니라 집기 판정</b>에서 드러난 경우이기 때문이다.
     *
     * <p>호출 시점이 트랜잭션 안이라 더티 체킹으로 반영된다.
     */
    private void abandon(BillingCycle cycle, int concludedAttempts) {
        if (cycle.onRetriesExhausted() == SubscriptionTransitionResult.CONFLICT) {
            log.warn("[ANOMALY] billingCycleId={} status={} — 이미 받은 주기를 포기하려 했다. 사람이 확인해야 함",
                    cycle.getId(), cycle.getStatus());
            return;
        }
        log.info("주기 포기 billingCycleId={} 결말난시도={} — 시도를 걸기 전 상한 판정",
                cycle.getId(), concludedAttempts);
    }

    /**
     * 트랜잭션 밖 — PG 동기 호출과 결과 반영.
     *
     * <p>PG 호출의 네 결말이 여기 다 온다(W4D1). {@code CallNotPermittedException}(차단기 개방)
     * 이 {@code DECLINED} 와 같은 길로 가는 게 W4D1 의 결론이다 — <i>"안 보냈다"</i> 는 정보의
     * 부재가 아니라 확실한 앎이고, PG 에 기록이 없으니 실패로 확정해도 이중청구가 안 열린다.
     *
     * <p><b>다만 그 결정에는 구독 쪽 대가가 있다</b>: 차단기가 열려 있는 동안 재시도 간격이
     * 그냥 소모된다(1일 → 3일 → 7일 → 포기). 계획서 W5D4 가 <i>"PG 장애(OPEN) 시 재시도 스킵,
     * 다음 주기로 연기"</i> 를 요구하는 게 정확히 이 자리이고, <b>오늘은 안 고친다.</b>
     */
    private void charge(Attempt attempt) {
        try {
            PgResult result = pgClient.approve(attempt.merchantPaymentId(), attempt.amount());
            if (result == PgResult.APPROVED) {
                transitions.applySubscriptionApproved(attempt.merchantPaymentId(), null);
            } else {
                concludeFailure(attempt);
            }
        } catch (CallNotPermittedException e) {
            if (attempt.resend()) {
                // ★ W4D1 의 "안 보냈으니 확실한 앎" 은 <b>새 시도</b>에만 참이다.
                // 재전송이 안 나간 건 <b>원래 요청이 어떻게 됐는지</b>를 아무것도 안 알려준다 —
                // 여기서 실패로 확정하면 "모른다"를 잃고, 그 시도는 다시 물어볼 대상에서 빠진다.
                // 아무것도 안 하고 물러난다. 다음 폴링이 같은 키로 또 묻는다.
                log.info("PG 차단기 개방 — 재전송을 보내지 못했다. 결말은 그대로 미결 billingCycleId={}",
                        attempt.cycleId());
                return;
            }
            log.info("PG 차단기 개방 — 구독 청구를 보내지 않고 실패로 확정 billingCycleId={}", attempt.cycleId());
            concludeFailure(attempt);
        } catch (PgTimeoutException e) {
            // 정보의 부재 → UNKNOWN. 주기는 건드리지 않는다 — 이 시도의 결말을 아직 모른다.
            // 주기가 FAILED 였다면 리스가 만료될 때 P2 가 다시 집고, PENDING 이었다면 P1 이
            // 다음 청구일에 회수한다(결정 4: 유실이 아니라 지연, 상한은 한 주기).
            transitions.applyTimedOut(attempt.merchantPaymentId());
        }
    }

    /**
     * tx2 — 실패를 주기에 반영한다.
     *
     * <p><b>규칙은 여기 없다</b>(W5D3 에서 옮겼다). 다음 재시도 시각이냐 포기냐를 정하는 일은
     * {@code PaymentTransitionService.applySubscriptionFailure} 가 트랜잭션 안에서 한다 —
     * 웹훅이 두 번째 호출부가 되면서, 규칙을 호출부마다 두면 복제가 되기 때문이다.
     * 이 메서드에 남은 건 <i>"실패 시각은 지금이다"</i> 하나뿐이다.
     */
    private void concludeFailure(Attempt attempt) {
        transitions.applySubscriptionFailure(attempt.merchantPaymentId(), null, OffsetDateTime.now());
    }

    /**
     * tx1 밖으로 들고 나갈 값. PG 호출이 트랜잭션 밖이라 엔티티가 아니라 값만 꺼낸다
     * ({@code PaymentService.Pending} 과 같은 이유).
     */
    private record Attempt(UUID merchantPaymentId, long amount, Long cycleId, boolean resend) {
    }
}
