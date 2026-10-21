package com.settleflow.observability;

import com.settleflow.payment.api.VerifiedKey;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * 웹훅 서명이 어느 키로 통과했나 — 시크릿 교체 때 <b>구 키를 언제 지워도 되는가</b>에 답한다.
 *
 * <pre>
 *   settleflow_webhook_signature_verified_total{key="current"}
 *   settleflow_webhook_signature_verified_total{key="previous"}
 * </pre>
 *
 * <h3>두 시계열을 기동 시점에 미리 만든다</h3>
 * 처음 증가할 때 만들면 구 키로 한 번도 안 온 동안 {@code key="previous"} 시계열이 <b>아예 없다</b>.
 * 그러면 "0 이다"와 "안 보인다(수집 안 됨·이름 틀림)"가 구분되지 않는다 — {@link SettlementMetrics}
 * 의 {@code NaN}/{@code absent()} 이야기와 같은 자리이고, 여기선 반대 방향이 위험하다:
 * <b>없는 걸 0 으로 읽고 구 키를 지우면</b> 아직 구 키로 보내던 PG 서버의 통보가 전부 401 이 된다.
 *
 * <h3>판단은 값이 아니라 증가량으로</h3>
 * 카운터는 누적이라 0 으로 돌아오지 않는다. "구 키 사용이 끝났다"는
 * {@code increase(settleflow_webhook_signature_verified_total{key="previous"}[관찰기간]) == 0} 이다.
 * 재시작으로 카운터가 0 이 되는 건 {@code increase()} 가 리셋으로 처리한다.
 *
 * <p>실패(401)는 세지 않는다 — 실패는 어느 키 문제인지 모른다. 그리고 SETNX 보다 앞에서 센다:
 * 중복 재전송도 "PG 가 아직 그 키로 서명한다"는 신호라 빼면 안 된다.
 */
@Component
public class WebhookMetrics {

    private final Map<VerifiedKey, Counter> verified = new EnumMap<>(VerifiedKey.class);

    public WebhookMetrics(MeterRegistry registry) {
        for (VerifiedKey key : VerifiedKey.values()) {
            verified.put(key, Counter.builder("settleflow.webhook.signature.verified")
                    .description("웹훅 서명이 통과한 키별 건수. previous 의 증가가 멈추면 구 키를 지워도 된다")
                    .tag("key", key.name().toLowerCase())
                    .register(registry));
        }
    }

    public void webhookVerified(VerifiedKey key) {
        verified.get(key).increment();
    }
}
