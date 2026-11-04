package com.safeschoolroad.saved;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.safeschoolroad.facility.SupportedDataImporter;
import com.safeschoolroad.route.Point;
import com.safeschoolroad.route.TmapGateway;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 10주차 산출물용 응답 예시 만들기 (RGN6-110·127). 기본 시험에서는 돌지 않고, 아래처럼 실행할 때만 파일을 쓴다.
 * <pre>./gradlew test --tests '*ResponseExampleTests' -Dexamples.out=docs/week10/examples</pre>
 * 공공자료는 실제 적재 파일(supported-districts.json.gz)을 쓰고, TMAP 경로선만 망우초 주변 모의 응답이다(외부 호출 없음).
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:response_examples;MODE=MySQL;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@EnabledIfSystemProperty(named = "examples.out", matches = ".+")
class ResponseExampleTests {
    // 기본(0)·최단(10): 서쪽에서 와서 망우초 앞 횡단보도를 건너고(211) 학교 옆에서 왼쪽(12)으로 꺾어 북쪽으로 간다
    static final String MAIN = route(new double[][]{{127.1000, 37.59867}, {127.10275, 37.59867}, {127.10360, 37.59867}, {127.10360, 37.59980}},
            new int[]{200, 211, 12, 201}, 420, 330);
    // 대로우선(4): 먼저 북쪽 큰길로 올라갔다가 동쪽으로 간다(건넘 정보 없음)
    static final String DETOUR = route(new double[][]{{127.1000, 37.59867}, {127.1000, 37.59980}, {127.10360, 37.59980}},
            new int[]{200, 13, 201}, 445, 350);
    static final String REQUEST = "{\"start\":{\"lat\":37.59867,\"lng\":127.1},\"end\":{\"lat\":37.5998,\"lng\":127.1036}}";

    static String route(double[][] pts, int[] turns, int distance, int time) {
        StringBuilder sb = new StringBuilder("{\"features\":[");
        for (int i = 0; i < pts.length; i++) {
            if (i > 0) sb.append(",{\"geometry\":{\"type\":\"LineString\",\"coordinates\":[[%s,%s],[%s,%s]]},\"properties\":{}},"
                    .formatted(pts[i - 1][0], pts[i - 1][1], pts[i][0], pts[i][1]));
            String props = i == 0 ? "\"totalDistance\":%d,\"totalTime\":%d,\"turnType\":%d".formatted(distance, time, turns[i])
                    : "\"turnType\":%d".formatted(turns[i]);
            sb.append("{\"geometry\":{\"type\":\"Point\",\"coordinates\":[%s,%s]},\"properties\":{%s}}".formatted(pts[i][0], pts[i][1], props));
        }
        return sb.append("]}").toString();
    }

    @TestConfiguration static class MockTmap {
        @Bean @Primary TmapGateway mockTmapGateway(ObjectMapper mapper) {
            return new TmapGateway(mapper, null, "example-key", 950) {
                @Override public RouteOption walking(Point start, Point end, int option) { return parse(option == 4 ? DETOUR : MAIN, option); }
            };
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired SupportedDataImporter importer;

    @Test void writeExamples() throws Exception {
        importer.importAll();
        jdbc.update("DELETE FROM saved_route_point");
        jdbc.update("DELETE FROM saved_route");
        jdbc.update("DELETE FROM member WHERE login_id IN ('example_parent','example_other')");
        for (String login : new String[]{"example_parent", "example_other"})
            jdbc.update("INSERT INTO member(login_id,password_hash,nickname,age_14_or_older) VALUES (?,?,?,?)", login, "x", "예시 보호자", true);
        Path out = Path.of(System.getProperty("examples.out"));
        Files.createDirectories(out);

        JsonNode evidence = call(post("/api/routes/evidence").with(user("example_parent")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(REQUEST), 200);
        assertEquals("제공하지 않음", evidence.path("safetyScore").asText());
        write(out, "20261110_근거분석응답예시_v1.json", "POST /api/routes/evidence", REQUEST, evidence);
        write(out, "20261110_적재현황응답예시_v1.json", "GET /api/data/coverage", null, call(get("/api/data/coverage").with(user("example_parent")), 200));

        String save = "{\"start\":{\"lat\":37.59867,\"lng\":127.1},\"end\":{\"lat\":37.5998,\"lng\":127.1036},\"candidateId\":\"candidate-0\",\"name\":\"망우초 가는 길\"}";
        JsonNode saved = call(post("/api/routes").with(user("example_parent")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(save), 201);
        long id = saved.path("id").asLong();
        ObjectNode bundle = mapper.createObjectNode();
        bundle.set("1_save", wrap("POST /api/routes", save, 201, saved));
        bundle.set("2_list", wrap("GET /api/routes", null, 200, call(get("/api/routes").with(user("example_parent")), 200)));
        bundle.set("3_detail", wrap("GET /api/routes/" + id, null, 200, call(get("/api/routes/" + id).with(user("example_parent")), 200)));
        bundle.set("4_todayLine", wrap("GET /api/routes/" + id + "/line", null, 200, call(get("/api/routes/" + id + "/line").with(user("example_parent")), 200)));
        bundle.set("5_otherUser", wrap("GET /api/routes/" + id + " (다른 회원)", null, 404, call(get("/api/routes/" + id).with(user("example_other")), 404)));
        String forged = save.replace("candidate-0", "candidate-99");
        bundle.set("6_forgedCandidate", wrap("POST /api/routes (꾸민 후보)", forged, 400,
                call(post("/api/routes").with(user("example_parent")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(forged), 400)));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM saved_route", Integer.class), "거부된 요청은 저장되지 않는다");
        ObjectNode root = header("저장 경로 확정·목록·상세·오늘 경로선·거부 예시");
        root.set("calls", bundle);
        Files.writeString(out.resolve("20261110_저장경로응답예시_v1.json"), pretty(root), StandardCharsets.UTF_8);
    }

    private JsonNode call(org.springframework.test.web.servlet.RequestBuilder req, int expected) throws Exception {
        var res = mvc.perform(req).andExpect(status().is(expected)).andReturn().getResponse();
        res.setCharacterEncoding("UTF-8");
        return mapper.readTree(res.getContentAsString());
    }

    private ObjectNode header(String what) {
        ObjectNode root = mapper.createObjectNode();
        root.put("_example", "응답 예시 (" + what + "). 공공자료는 실제 적재 파일(2026-11-04 생성), TMAP 경로선은 서울망우초등학교 주변 모의 응답이라 실제 길과 다를 수 있어요.");
        root.put("_howToRegenerate", "./gradlew test --tests '*ResponseExampleTests' -Dexamples.out=docs/week10/examples");
        return root;
    }

    private ObjectNode wrap(String endpoint, String body, int status, JsonNode response) throws Exception {
        ObjectNode n = mapper.createObjectNode();
        n.put("request", endpoint);
        if (body != null) n.set("requestBody", mapper.readTree(body));
        n.put("status", status);
        n.set("response", response);
        return n;
    }

    private void write(Path out, String file, String endpoint, String body, JsonNode response) throws Exception {
        ObjectNode root = header(endpoint);
        root.setAll(wrap(endpoint, body, 200, response));
        Files.writeString(out.resolve(file), pretty(root), StandardCharsets.UTF_8);
    }

    private String pretty(JsonNode n) throws Exception {
        return mapper.copy().enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(n) + "\n";
    }
}
