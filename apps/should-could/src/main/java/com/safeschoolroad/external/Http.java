package com.safeschoolroad.external;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

// 외부 API 호출용 RestClient. 시간 제한이 없으면 상대 서버가 멈췄을 때 화면이 끝없이 기다린다.
public final class Http {

    private Http() {
    }

    public static RestClient client(Duration readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(readTimeout);
        return RestClient.builder().requestFactory(factory).build();
    }
}
