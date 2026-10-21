package com.settleflow.payment.api;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 시크릿 교체 — 신·구 키 병행 검증. 시각을 고정한 단위 테스트라 컨테이너가 필요 없다.
 *
 * <p>서명은 검증기의 {@code sign()} 이 아니라 이 클래스의 {@link #hmacHex} 로 만든다. {@code sign()} 은
 * 현재 키로만 서명하므로 "구 키로 서명한 요청"을 만들 수 없고, 검증기 자신으로 검증기를 재면 둘이 같이
 * 틀릴 때 못 잡는다({@code WebhookSignatureTest} 의 고정값 대조와 같은 이유).
 */
class WebhookSecretRotationTest {

    private static final long NOW = 1_700_000_000L;
    private static final String TS = Long.toString(NOW);
    private static final String BODY = "{\"eventId\":\"evt-rot\",\"amount\":10000}";
    private static final Clock FIXED = Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC);

    private static final String NEW = "new-secret";
    private static final String OLD = "old-secret";

    @Test
    void 현재키로_서명하면_CURRENT() {
        assertThat(verifier(NEW, OLD).verify(TS, hmacHex(NEW), BODY)).contains(VerifiedKey.CURRENT);
    }

    @Test
    void 교체기간엔_구키로_서명해도_PREVIOUS로_통과한다() {
        assertThat(verifier(NEW, OLD).verify(TS, hmacHex(OLD), BODY)).contains(VerifiedKey.PREVIOUS);
    }

    @Test
    void 교체가_끝나면_구키_서명은_실패한다() {
        assertThat(verifier(NEW, "").verify(TS, hmacHex(OLD), BODY)).isEmpty();
    }

    @Test
    void 어느_쪽도_아닌_키는_실패한다() {
        assertThat(verifier(NEW, OLD).verify(TS, hmacHex("other-secret"), BODY)).isEmpty();
    }

    /**
     * 빈 구 키는 "구 키 없음"이어야 한다. <b>틀린 서명</b>으로 재야 구 키 분기까지 간다 — 현재 키로 서명하면
     * 첫 비교에서 돌아와 구 키를 아예 안 만지므로, 필터가 없어도 초록불이다(가이드 원안이 그 모양이었다).
     *
     * <p>{@code ""} 와 {@code " "} 를 둘 다 보는 이유: 필터가 없을 때 망가지는 방식이 다르다.
     * {@code ""} 는 {@code SecretKeySpec} 예외(→ 500), {@code " "} 는 예외 없이 공백 한 글자가 키가 된다.
     */
    @Test
    void 빈_구키는_키로_취급하지_않는다_틀린서명이_예외없이_실패한다() {
        assertThat(verifier(NEW, "").verify(TS, hmacHex("other-secret"), BODY)).isEmpty();
        assertThat(verifier(NEW, " ").verify(TS, hmacHex("other-secret"), BODY)).isEmpty();
        assertThat(verifier(NEW, null).verify(TS, hmacHex("other-secret"), BODY)).isEmpty();
    }

    /** 공백 구 키가 필터 없이 키가 되면, 공백 한 글자로 서명한 요청이 PREVIOUS 로 통과한다. */
    @Test
    void 공백_구키로는_서명이_통과하지_않는다() {
        assertThat(verifier(NEW, " ").verify(TS, hmacHex(" "), BODY)).isEmpty();
    }

    @Test
    void 빈_구키여도_현재키는_그대로_통과한다() {
        assertThat(verifier(NEW, " ").verify(TS, hmacHex(NEW), BODY)).contains(VerifiedKey.CURRENT);
    }

    @Test
    void sign은_교체기간에도_현재키로_서명한다() {
        assertThat(verifier(NEW, OLD).sign(TS, BODY)).isEqualTo(hmacHex(NEW));
    }

    // ---------- helpers ----------

    private static WebhookSignatureVerifier verifier(String current, String previous) {
        return new WebhookSignatureVerifier(current, previous, 300, FIXED);
    }

    /** 검증기와 독립된 서명. 빈 키는 {@code SecretKeySpec} 이 거부하므로 여기선 쓰지 않는다. */
    private static String hmacHex(String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((TS + "." + BODY).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
