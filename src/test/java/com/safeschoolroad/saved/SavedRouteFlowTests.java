package com.safeschoolroad.saved;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safeschoolroad.route.Point;
import com.safeschoolroad.route.TmapGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

/** 근거 분석 → 확정·저장 → 재조회 → 오늘 경로선 흐름. TMAP은 합성 응답으로 바꾸고 외부 호출을 하지 않는다. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:saved_route_flow;MODE=MySQL;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class SavedRouteFlowTests {
    // 출발(127.080) → 건넘(127.083, turnType 211) → 좌회전(127.087) → 도착(127.090), 위도 37.59 직선
    static final String TMAP_SAMPLE = """
      {"features":[
        {"geometry":{"type":"Point","coordinates":[127.080,37.590]},"properties":{"totalDistance":900,"totalTime":700,"turnType":200}},
        {"geometry":{"type":"LineString","coordinates":[[127.080,37.590],[127.083,37.590]]},"properties":{}},
        {"geometry":{"type":"Point","coordinates":[127.083,37.590]},"properties":{"turnType":211}},
        {"geometry":{"type":"LineString","coordinates":[[127.083,37.590],[127.087,37.590]]},"properties":{}},
        {"geometry":{"type":"Point","coordinates":[127.087,37.590]},"properties":{"turnType":12}},
        {"geometry":{"type":"LineString","coordinates":[[127.087,37.590],[127.090,37.590]]},"properties":{}},
        {"geometry":{"type":"Point","coordinates":[127.090,37.590]},"properties":{"turnType":201}}
      ]}
      """;
    static final String BODY = "{\"start\":{\"lat\":37.59,\"lng\":127.08},\"end\":{\"lat\":37.59,\"lng\":127.09},"
            + "\"candidateId\":\"%s\",\"name\":\"%s\"}";

    @TestConfiguration static class FakeTmap {
        @Bean @Primary TmapGateway fakeTmapGateway(ObjectMapper mapper) {
            return new TmapGateway(mapper, null, "test-key", 950) {
                @Override public RouteOption walking(Point start, Point end, int option) { return parse(TMAP_SAMPLE, option); }
            };
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    @BeforeEach void data() {
        jdbc.update("DELETE FROM saved_route_point");
        jdbc.update("DELETE FROM saved_route");
        jdbc.update("DELETE FROM member WHERE login_id IN ('routeowner','otheruser')");
        jdbc.update("DELETE FROM facility WHERE source='시험 출처'");
        for (String login : new String[]{"routeowner", "otheruser"})
            jdbc.update("INSERT INTO member(login_id,password_hash,nickname,age_14_or_older) VALUES (?,?,?,?)", login, "x", "보호자", true);
        insert("cw-1", "CROSSWALK", 37.59001, 127.08301, "ESTIMATED", "");
        insert("cctv-1", "CCTV", 37.59005, 127.08700, null, "");
        insert("school-1", "SCHOOL", 37.59020, 127.08850, null, "테스트초등학교");
    }

    void insert(String id, String kind, double lat, double lng, String signal, String name) {
        jdbc.update("INSERT INTO facility(source,original_id,kind,district,lat,lng,source_downloaded,source_reference,name,signal_status) "
                + "VALUES ('시험 출처',?,?,'중랑구',?,?,DATE '2026-10-02','시험',?,?)", id, kind, lat, lng, name, signal);
    }

    @Test void evidenceThenSaveThenOwnerOnlyReadAndTodayLine() throws Exception {
        JsonNode evidence = json(mvc.perform(post("/api/routes/evidence").with(user("routeowner")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"start\":{\"lat\":37.59,\"lng\":127.08},\"end\":{\"lat\":37.59,\"lng\":127.09}}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals("제공하지 않음", evidence.path("safetyScore").asText());
        assertEquals("candidate-0", evidence.path("evidence").get(0).path("candidateId").asText());
        assertEquals(1, evidence.path("evidence").get(0).path("crossings").path("signalEstimated").asInt());

        JsonNode saved = json(mvc.perform(post("/api/routes").with(user("routeowner")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(BODY.formatted("candidate-0", "학교 가는 길")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        long id = saved.path("id").asLong();
        assertTrue(saved.path("pointCount").asInt() >= 2);

        JsonNode list = json(mvc.perform(get("/api/routes").with(user("routeowner")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(1, list.path("routes").size());

        JsonNode detail = json(mvc.perform(get("/api/routes/" + id).with(user("routeowner")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode first = detail.path("points").get(0);
        assertEquals("CROSSWALK", first.path("kind").asText());
        assertEquals(37.59001, first.path("lat").asDouble(), 1e-9, "저장 좌표는 공공자료 좌표");
        assertTrue(first.path("guideText").asText().contains("멈춰"), "횡단보도 안내에는 멈춤 확인이 있어야 한다");
        assertFalse(first.path("guideText").asText().contains("초록불"), "신호 확정 자료가 없으니 초록불을 가정하지 않는다");
        assertEquals("TEMPLATE", first.path("guideSource").asText());

        mvc.perform(get("/api/routes/" + id).with(user("otheruser"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/routes/" + id + "/line").with(user("otheruser"))).andExpect(status().isNotFound());
        JsonNode line = json(mvc.perform(get("/api/routes/" + id + "/line").with(user("routeowner")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertTrue(line.path("line").size() >= 2);

        Integer columnsWithLine = jdbc.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE LOWER(TABLE_NAME) IN ('saved_route','saved_route_point') "
                + "AND LOWER(COLUMN_NAME) IN ('line','line_json','distance_m','duration_sec')", Integer.class);
        assertEquals(0, columnsWithLine, "TMAP 경로선·거리·시간은 저장하지 않는다");
    }

    @Test void rejectsForgedCandidateBadNameAndAnonymous() throws Exception {
        // 합성 응답은 세 옵션이 같은 선이라 candidate-0 하나로 합쳐진다. candidate-4는 존재하지 않는 후보
        mvc.perform(post("/api/routes").with(user("routeowner")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(BODY.formatted("candidate-4", "학교 가는 길"))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/routes").with(user("routeowner")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(BODY.formatted("candidate-0", " "))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/routes").with(user("routeowner")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(BODY.formatted("../etc", "학교"))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/routes").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(BODY.formatted("candidate-0", "학교"))).andExpect(status().isUnauthorized());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM saved_route", Integer.class));
    }

    @Test void noPublicDataNearRouteMeansNothingSaved() throws Exception {
        jdbc.update("DELETE FROM facility WHERE source='시험 출처'");
        mvc.perform(post("/api/routes").with(user("routeowner")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(BODY.formatted("candidate-0", "학교 가는 길"))).andExpect(status().isUnprocessableEntity());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM saved_route", Integer.class));
    }

    private JsonNode json(String body) throws Exception { return mapper.readTree(body); }
}
