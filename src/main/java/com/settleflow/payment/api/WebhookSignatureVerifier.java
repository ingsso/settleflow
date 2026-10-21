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
import java.util.Optional;

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
 *
 * <h3>시크릿 교체 — 키가 둘이다</h3>
 * {@code secret}(현재 키: 검증 + {@link #sign})과 {@code previous-secret}(직전 키: <b>검증만</b>).
 * 목록이 아니라 역할로 나눈 건 "지금 키가 무엇인가"가 설정에서 바로 보이게 하려는 것이다.
 * 교체 기간에만 구 키를 넣고, 구 키로 통과하는 요청이 사라지면({@link VerifiedKey#PREVIOUS} 카운터)
 * 뺀다. 순서는 "받을 준비 → PG 전환 → 사용량 0 확인 → 구 키 제거" — 가이드 §운영 절차.
 */
@Component
public class WebhookSignatureVerifier {

    private static final String ALGORITHM = "HmacSHA256";

    private final byte[] current;
    /** 교체 기간이 아니면 {@code null}. */
    private final byte[] previous;
    private final long toleranceSeconds;
    private final Clock clock;

    @Autowired
    public WebhookSignatureVerifier(@Value("${settleflow.webhook.secret}") String secret,
                                    @Value("${settleflow.webhook.previous-secret:}") String previousSecret,
                                    @Value("${settleflow.webhook.tolerance-seconds}") long toleranceSeconds) {
        this(secret, previousSecret, toleranceSeconds, Clock.systemUTC());
    }

    /** 테스트 편의 — 교체 기간이 아닌 검증기. 테스트가 "다른 키로 서명한 값"을 만들 때 쓴다. */
    public WebhookSignatureVerifier(String secret, long toleranceSeconds) {
        this(secret, null, toleranceSeconds, Clock.systemUTC());
    }

    /** 시각을 고정해야 하는 단위 테스트용. 앱 전역 {@code Clock} 빈은 만들지 않는다. */
    WebhookSignatureVerifier(String secret, String previousSecret, long toleranceSeconds, Clock clock) {
        // 빈 시크릿이면 SecretKeySpec 이 요청 시점에 IllegalArgumentException → 모든 웹훅이 500.
        // 그러면 PG 가 재전송을 반복할 뿐 원인이 안 보이므로, 기동 시점에 멈춘다.
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("settleflow.webhook.secret 이 비어 있다 (WEBHOOK_SECRET)");
        }
        this.current = secret.getBytes(StandardCharsets.UTF_8);
        // 공백뿐인 구 키는 "구 키 없음"이다. 걸러야 하는 이유가 둘이고 값에 따라 갈린다:
        //   ""  → SecretKeySpec 이 요청 시점에 IllegalArgumentException → 현재 키로 실패한 요청이 전부 500
        //   " " → 예외 없이 <b>공백 한 글자가 유효한 키</b>가 된다. 아무나 맞힐 수 있는 키로 서명이 통과한다
        this.previous = (previousSecret == null || previousSecret.isBlank())
                ? null
                : previousSecret.getBytes(StandardCharsets.UTF_8);
        this.toleranceSeconds = toleranceSeconds;
        this.clock = clock;
    }

    /**
     * @return 통과했으면 어느 키로 통과했는지, 실패면 비어 있음. 타임스탬프 검사는 키와 무관하므로 먼저 한다.
     */
    public Optional<VerifiedKey> verify(String timestampHeader, String signatureHeader, String rawBody) {
        if (timestampHeader == null || signatureHeader == null) return Optional.empty();

        long ts;
        try { ts = Long.parseLong(timestampHeader); }
        catch (NumberFormatException e) { return Optional.empty(); }

        long now = clock.instant().getEpochSecond();
        if (Math.abs(now - ts) > toleranceSeconds) return Optional.empty();

        byte[] actual;
        try { actual = HexFormat.of().parseHex(signatureHeader); }
        catch (IllegalArgumentException e) { return Optional.empty(); }

        String payload = timestampHeader + "." + rawBody;
        if (MessageDigest.isEqual(hmac(current, payload), actual)) {
            return Optional.of(VerifiedKey.CURRENT);
        }
        if (previous != null && MessageDigest.isEqual(hmac(previous, payload), actual)) {
            return Optional.of(VerifiedKey.PREVIOUS);
        }
        return Optional.empty();
    }

    /** 테스트·k6 대조용. 프로덕션 경로에서는 안 쓴다. <b>항상 현재 키</b>로 서명한다. */
    public String sign(String timestamp, String rawBody) {
        return HexFormat.of().formatHex(hmac(current, timestamp + "." + rawBody));
    }

    private byte[] hmac(byte[] key, String payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
