package com.settleflow.payment.pg;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * PG 호출을 차단기로 감싸는 데코레이터(W4D1). 실제 호출은 {@code delegate} 가 한다.
 *
 * <h3>왜 구현체에 직접 안 붙이고 한 겹을 두는가</h3>
 * 회로는 <b>갈아끼워지는 자리 밖</b>에 있어야 한다. {@code @CircuitBreaker} 를
 * {@link StubPgClient} 에 직접 붙이면, 테스트가 그 빈을 대체하는 순간 <b>회로도 같이
 * 사라진다</b> — 테스트 구현체에 애노테이션을 복사하면 초록은 뜨지만 그건 프로덕션 회로가
 * 아니라 <b>테스트 회로를 검증한 것</b>이고, 안 복사하면 회로가 없어 영영 빨간불이다.
 * 이 겹을 두면 테스트는 {@code delegate} 만 바꾸고 <b>프로덕션 회로가 그대로 돈다</b>.
 *
 * <p>덤으로 <b>프록시 경계</b> 문제가 사라진다. 호출자({@code PaymentService}·
 * {@code RefundConfirmService})가 <b>다른 빈</b>을 부르는 구조라 자기 호출이 아니고,
 * 애노테이션이 조용히 무시될 자리가 없다 — W3D5 에서 {@code SettlementRunner} 를 별도 빈으로
 * 뺀 것과 같은 이유다.
 *
 * <p>회로를 서비스 쪽(예: {@code PaymentService.pay})에 걸지 않은 이유도 같은 계열이다.
 * 그러면 회로가 PG 호출뿐 아니라 <b>그 메서드의 트랜잭션 작업까지</b> 감싼다. 두 서비스 모두
 * PG 호출을 tx 밖으로 빼내려고 tx 를 쪼개놨는데(W1D1 §2 함정1) 그 설계와 어긋난다.
 *
 * <h3>회로가 둘인 이유</h3>
 * 승인과 환불은 <b>실패의 뜻이 다르다</b>. 승인이 못 나가면 아무 빚도 안 생기지만, 환불이
 * 못 나가면 <b>이미 진 빚</b>의 이행이 늦어진다. 지키려는 게 다르므로 격리 단위도 가른다 —
 * 승인 API 가 죽었다고 환불까지 막으면 갚아야 할 돈이 우리 회로 사정으로 밀린다.
 * 실제 PG 도 승인과 환불은 다른 엔드포인트라 따로 죽을 수 있다.
 *
 * <h3>무엇이 실패로 세어지나</h3>
 * <b>예외만</b> 센다. PG 거절({@link PgResult#DECLINED} · {@link PgRefundResult.Declined})은
 * 예외가 아니라 <b>반환값</b>이라 회로를 흔들지 않는다 — 이게 맞다. 거절은 PG 가 멀쩡히
 * 답한 것이고 격리할 장애가 아니다. 실질적으로 회로를 여는 건 {@link PgTimeoutException} 이다.
 *
 * <h3>열렸을 때</h3>
 * {@code CallNotPermittedException} 이 <b>호출자에게 그대로 나간다</b>. 폴백을 두지 않는 게
 * 판단이다 — 여기서 "실패"를 만들어 돌려주면 승인·환불이 그걸 <b>PG 의 답</b>으로 오인한다.
 * 안 물어봤다는 사실 자체가 정보이고, 그걸 어떻게 해석할지는 경로마다 다르다:
 * <ul>
 *   <li><b>승인</b> → {@code FAILED}. 안 보냈으니 PG 에 기록이 없고 돈이 안 움직였다.
 *       {@code UNKNOWN}(정보의 부재)이 아니다 — 여기선 <b>확실한 앎</b>이다</li>
 *   <li><b>환불</b> → 예외를 소비자까지 내보내 <b>재배달</b>시킨다. {@code FAILED} 로 찍으면
 *       "PG 가 거절했다"가 되어 예약이 풀리고 장부엔 끝내 아무것도 안 적힌다 —
 *       고객에겐 이미 201 로 접수했다고 답해놓고 우리 회로 사정으로 조용히 종료시키는 셈이다</li>
 * </ul>
 */
@Primary
@Component
public class ResilientPgClient implements PgClient {

    private final PgClient delegate;

    /**
     * {@code @Qualifier} 로 <b>이름을 박아</b> 받는다 — 타입으로 받으면 자기 자신도 후보가 되어
     * 순환한다. 테스트는 이 이름의 빈을 대체해 실패를 주입한다.
     */
    public ResilientPgClient(@Qualifier("stubPgClient") PgClient delegate) {
        this.delegate = delegate;
    }

    @Override
    @CircuitBreaker(name = "pg-approve")
    public PgResult approve(UUID merchantPaymentId, long amount) {
        return delegate.approve(merchantPaymentId, amount);
    }

    @Override
    @CircuitBreaker(name = "pg-refund")
    public PgRefundResult refund(long refundId, long amount) {
        return delegate.refund(refundId, amount);
    }
}
