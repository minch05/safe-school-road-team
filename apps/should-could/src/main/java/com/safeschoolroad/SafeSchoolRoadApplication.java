package com.safeschoolroad;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.Arrays;
import java.util.TimeZone;
import java.util.stream.Stream;

@SpringBootApplication
@EnableScheduling // 당일 캐시 정리, 도착 지연 알림
public class SafeSchoolRoadApplication {

    public static void main(String[] args) {
        // 날짜 계산(당일 캐시, 출발·도착 기록)은 한국 시간 기준. 서버(EC2)는 UTC 일 수 있어 맞춰 둔다
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
        // 공공데이터 적재(--import-...)만 할 때는 빈 포트를 아무거나 쓴다 (켜 둔 앱의 8080 과 겹치지 않게)
        // (devtools 가 main 을 한 번 더 부르므로 이미 붙어 있으면 다시 붙이지 않는다)
        if (Arrays.stream(args).anyMatch(a -> a.startsWith("--import-"))
                && Arrays.stream(args).noneMatch(a -> a.startsWith("--server.port="))) {
            args = Stream.concat(Arrays.stream(args), Stream.of("--server.port=0")).toArray(String[]::new);
        }
        SpringApplication.run(SafeSchoolRoadApplication.class, args);
    }
}
