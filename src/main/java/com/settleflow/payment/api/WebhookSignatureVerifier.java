package com.settleflow.payment.api;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;

/**
 * 웹훅 발신자 검증 — "요청이 진짜 PG 에서 왔는가".
 *
 * <p>서명 대상은 {@code timestamp + "." + 원본 바디}, HMAC-SHA256, 공유 시크릿.
 * <b>타임스탬프를 서명 대상에 넣는 이유</b>: 빼면 공격자가 옛날 요청의 시각 헤더만 바꿔 재사용한다.
 * 허용 오차(기본 5분) 안의 재전송은 여기가 아니라 {@code eventId} 멱등 처리가 막는다.
 *
 * <p><b>{@code equals} 가 아니라 {@link MessageDigest#isEqual}</b> — 앞에서부터 비교하다 다르면 일찍
 * 끝나는 비교는 그 시간 차로 서명을 한 바이트씩 추측하게 해준다(타이밍 공격). 상수 시간 비교를 쓴다.
 *
 * <p>호출 위치는 {@link WebhookController} 의 <b>SETNX 앞</b>이다. 이유는 그쪽 javadoc 참조.
 */
@Component
public class WebhookSignatureVerifier {

    private static final String ALGORITHM = "HmacSHA256";

    private final byte[] secret;
    private final long toleranceSeconds;
    private final Clock clock;

    @Autowired
    public WebhookSignatureVerifier(@Value("${settleflow.webhook.secret}") String secret,
                                    @Value("${settleflow.webhook.tolerance-seconds}") long toleranceSeconds) {
        this(secret, toleranceSeconds, Clock.systemUTC());
    }

    /** 시각을 고정해야 하는 단위 테스트용. 앱 전역 {@code Clock} 빈은 만들지 않는다. */
    WebhookSignatureVerifier(String secret, long toleranceSeconds, Clock clock) {
        // 빈 시크릿이면 SecretKeySpec 이 요청 시점에 IllegalArgumentException → 모든 웹훅이 500.
        // 그러면 PG 가 재전송을 반복할 뿐 원인이 안 보이므로, 기동 시점에 멈춘다.
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("settleflow.webhook.secret 이 비어 있다 (WEBHOOK_SECRET)");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.toleranceSeconds = toleranceSeconds;
        this.clock = clock;
    }

    public boolean verify(String timestampHeader, String signatureHeader, String rawBody) {
        if (timestampHeader == null || signatureHeader == null) return false;

        long ts;
        try { ts = Long.parseLong(timestampHeader); }
        catch (NumberFormatException e) { return false; }

        long now = clock.instant().getEpochSecond();
        if (Math.abs(now - ts) > toleranceSeconds) return false;

        byte[] expected = hmac(timestampHeader + "." + rawBody);
        byte[] actual;
        try { actual = HexFormat.of().parseHex(signatureHeader); }
        catch (IllegalArgumentException e) { return false; }

        return MessageDigest.isEqual(expected, actual);
    }

    /** 테스트·k6 대조용. 프로덕션 경로에서는 안 쓴다. */
    public String sign(String timestamp, String rawBody) {
        return HexFormat.of().formatHex(hmac(timestamp + "." + rawBody));
    }

    private byte[] hmac(String payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
