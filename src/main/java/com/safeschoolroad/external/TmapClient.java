package com.safeschoolroad.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safeschoolroad.geo.LatLng;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

// TMAP 보행자 경로 (서버에서만 부른다). 같은 요청은 당일 캐시를 쓴다(HANDOFF 5-1).
@Component
public class TmapClient {

    private static final String API = "TMAP";
    private static final String URL = "https://apis.openapi.sk.com/tmap/routes/pedestrian?version=1&format=json";

    // 탐색 옵션: 추천(0), 추천+대로우선(4), 최단(10) (HANDOFF 5-1)
    public static final List<Integer> OPTIONS = List.of(0, 4, 10);

    private final ApiCache cache;
    private final ObjectMapper objectMapper;
    private final RestClient http = Http.client(Duration.ofSeconds(20));

    @Value("${TMAP_APP_KEY:}")
    private String appKey;

    // 하루 1,000건 한도. 여유를 두고 이 수에서 멈춘다
    @Value("${tmap.daily-limit:950}")
    private int dailyLimit;

    public TmapClient(ApiCache cache, ObjectMapper objectMapper) {
        this.cache = cache;
        this.objectMapper = objectMapper;
    }

    public static String optionLabel(int option) {
        return switch (option) {
            case 0 -> "기본";
            case 4 -> "대로우선";
            case 10 -> "최단";
            default -> "옵션 " + option;
        };
    }

    /** 오늘 캐시에 이 요청이 있는지 (비로그인 조회 횟수 계산용) */
    public boolean isCachedToday(LatLng start, LatLng end, int option, List<LatLng> passList) {
        return cache.getToday(API, requestKey(start, end, option, passList)).isPresent();
    }

    public TmapRoute pedestrian(LatLng start, LatLng end, int option, List<LatLng> passList) {
        String key = requestKey(start, end, option, passList);
        String body = cache.getToday(API, key).orElse(null);
        if (body == null) {
            body = call(start, end, option, passList);
            cache.put(API, key, body);
        }
        return parse(body);
    }

    private String call(LatLng start, LatLng end, int option, List<LatLng> passList) {
        if (appKey.isBlank()) {
            throw new TmapException("TMAP 키(TMAP_APP_KEY)가 설정되지 않았어요.");
        }
        if (cache.callsToday(API) >= dailyLimit) {
            throw new TmapException("오늘 경로 찾기 한도를 다 썼어요. 내일 다시 시도해 주세요.");
        }
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("startX", fmt(start.lng()));
        req.put("startY", fmt(start.lat()));
        req.put("endX", fmt(end.lng()));
        req.put("endY", fmt(end.lat()));
        req.put("startName", "출발");
        req.put("endName", "도착");
        req.put("searchOption", String.valueOf(option));
        req.put("reqCoordType", "WGS84GEO");
        req.put("resCoordType", "WGS84GEO");
        if (passList != null && !passList.isEmpty()) {
            // 경유지 형식: "경도,위도_경도,위도" (최대 5곳)
            req.put("passList", passList.stream().map(p -> fmt(p.lng()) + "," + fmt(p.lat()))
                    .collect(Collectors.joining("_")));
        }
        cache.countCall(API);
        try {
            return http.post().uri(URL)
                    .header("appKey", appKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(req)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            throw new TmapException(describeError(e.getResponseBodyAsString()), e);
        } catch (Exception e) {
            throw new TmapException("경로를 받아 오지 못했어요. 잠시 뒤 다시 시도해 주세요.", e);
        }
    }

    private String describeError(String body) {
        try {
            JsonNode err = objectMapper.readTree(body).path("error");
            String code = err.path("code").asText("");
            // 출발지와 도착지가 너무 가깝거나 같은 경우 등
            if (code.startsWith("31")) {
                return "이 출발지·도착지로는 걸어가는 길을 찾지 못했어요. 위치를 조금 옮겨 보세요. (" + err.path("message").asText(code) + ")";
            }
            return "경로를 받아 오지 못했어요: " + err.path("message").asText(code);
        } catch (Exception ignore) {
            return "경로를 받아 오지 못했어요.";
        }
    }

    TmapRoute parse(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            List<LatLng> line = new ArrayList<>();
            List<TmapRoute.Turn> turns = new ArrayList<>();
            int distance = 0, time = 0;
            for (JsonNode f : root.path("features")) {
                JsonNode g = f.path("geometry"), p = f.path("properties");
                if ("LineString".equals(g.path("type").asText())) {
                    for (JsonNode c : g.path("coordinates")) {
                        LatLng pt = new LatLng(c.get(1).asDouble(), c.get(0).asDouble());
                        if (line.isEmpty() || !line.get(line.size() - 1).equals(pt)) {
                            line.add(pt);
                        }
                    }
                } else {
                    if (p.has("totalDistance")) {
                        distance = p.path("totalDistance").asInt();
                        time = p.path("totalTime").asInt();
                    }
                    JsonNode c = g.path("coordinates");
                    turns.add(new TmapRoute.Turn(c.get(1).asDouble(), c.get(0).asDouble(),
                            p.path("turnType").asInt(0), p.path("description").asText("")));
                }
            }
            if (line.size() < 2) {
                throw new TmapException("걸어가는 길을 찾지 못했어요.");
            }
            return new TmapRoute(line, turns, distance, time);
        } catch (TmapException e) {
            throw e;
        } catch (Exception e) {
            throw new TmapException("경로 응답을 읽지 못했어요.", e);
        }
    }

    private static String requestKey(LatLng start, LatLng end, int option, List<LatLng> passList) {
        StringBuilder sb = new StringBuilder("ped|").append(fmt(start.lat())).append(',').append(fmt(start.lng()))
                .append('|').append(fmt(end.lat())).append(',').append(fmt(end.lng())).append('|').append(option);
        if (passList != null) {
            passList.forEach(p -> sb.append('|').append(fmt(p.lat())).append(',').append(fmt(p.lng())));
        }
        return sb.toString();
    }

    // 소수점 6자리(약 10cm)로 맞춰 같은 장소는 같은 요청이 되게 한다
    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.6f", v);
    }

    public static class TmapException extends RuntimeException {
        public TmapException(String message) {
            super(message);
        }

        public TmapException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
