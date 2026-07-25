package com.settleflow.support;

/**
 * 테스트용 컨테이너 이미지 태그를 한곳에서 관리한다.
 * 테스트마다 버전이 갈리면 "16에선 되고 17에선 안 되는" 차이가 났을 때 원인 추적이 안 된다.
 */
public final class TestImages {

    public static final String POSTGRES = "postgres:17";
    public static final String REDIS = "redis:7";
    public static final String KAFKA = "apache/kafka:3.9.1";   // compose.yaml 과 동일 태그

    private TestImages() {
    }
}
