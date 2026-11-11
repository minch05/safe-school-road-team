package com.safeschoolroad.saved;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safeschoolroad.route.Point;
import com.safeschoolroad.route.TmapGateway;
import java.util.List;
import jakarta.servlet.http.Cookie;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.safeschoolroad.guide.GuideAiClient ai;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    @BeforeEach void data() {
        jdbc.update("DELETE FROM guide_log");
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

    @Test void validAiIsSavedAndReadWithItsSource() throws Exception {
        org.mockito.Mockito.when(ai.configured()).thenReturn(true);
        org.mockito.Mockito.when(ai.generate(org.mockito.ArgumentMatchers.anyList())).thenAnswer(invocation -> {
            List<com.safeschoolroad.guide.GuideWriter.Input> inputs = invocation.getArgument(0);
            return mapper.writeValueAsString(java.util.Map.of("points", inputs.stream()
                    .map(p -> java.util.Map.of("seq", p.seq(), "text", p.templateText())).toList()));
        });
        JsonNode saved = json(mvc.perform(post("/api/routes").with(user("routeowner")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(BODY.formatted("candidate-0", "AI 시험")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        assertEquals("AI", saved.path("guideSource").asText());
        JsonNode detail = json(mvc.perform(get("/api/routes/" + saved.path("id").asLong()).with(user("routeowner")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        for (JsonNode point : detail.path("points")) {
            assertEquals("AI", point.path("guideSource").asText());
            assertFalse(point.path("guideText").asText().isBlank());
        }
    }

    @Test void invalidAiFallsBackAndPersistsTemplateSource() throws Exception {
        org.mockito.Mockito.when(ai.configured()).thenReturn(true);
        org.mockito.Mockito.when(ai.generate(org.mockito.ArgumentMatchers.anyList())).thenReturn(
                "{\"points\":[{\"seq\":1,\"text\":\"초록불이면 오른쪽으로 가요.\"}]}");
        JsonNode saved = json(mvc.perform(post("/api/routes").with(user("routeowner")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(BODY.formatted("candidate-0", "대체 문장 시험")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        assertEquals("TEMPLATE", saved.path("guideSource").asText());
        JsonNode detail = json(mvc.perform(get("/api/routes/" + saved.path("id").asLong()).with(user("routeowner")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        for (JsonNode point : detail.path("points")) assertEquals("TEMPLATE", point.path("guideSource").asText());
    }

    @Test void preservesOldRouteOnInvalidReplacementAndRejectsAnotherOwner() throws Exception {
        String body = BODY.formatted("candidate-0", "기존 경로");
        long oldId = json(mvc.perform(post("/api/routes").with(user("routeowner")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).path("id").asLong();
        String replaced = body.replace("기존 경로", "새 경로").replace("\"}", "\",\"replaceRouteId\":" + oldId + "}");
        mvc.perform(post("/api/routes").with(user("otheruser")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(replaced)).andExpect(status().isNotFound());
        mvc.perform(delete("/api/routes/" + oldId).with(user("otheruser")).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(post("/api/routes").with(user("routeowner")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(replaced.replace("candidate-0", "candidate-4")))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/routes/" + oldId).with(user("routeowner"))).andExpect(status().isOk());
        long newId = json(mvc.perform(post("/api/routes").with(user("routeowner")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(replaced)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).path("id").asLong();
        assertNotEquals(oldId, newId);
        mvc.perform(get("/api/routes/" + oldId).with(user("routeowner"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/routes/" + newId).with(user("routeowner"))).andExpect(status().isOk());
        mvc.perform(delete("/api/routes/" + newId).with(user("routeowner")).with(csrf())).andExpect(status().isNoContent());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM saved_route_point", Integer.class));
    }

    @Test void savedPlaceAndEditedGuideArePrivateAndUnsafeCrossingEditIsRejected() throws Exception {
        String place = """
                {"name":"학교 정문","point":{"lat":37.59,"lng":127.09}}
                """;
        mvc.perform(post("/api/places/mine").with(user("routeowner")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(place)).andExpect(status().isNoContent());
        JsonNode savedPlaces = json(mvc.perform(get("/api/places/mine").with(user("routeowner")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals("학교 정문", savedPlaces.get(0).path("name").asText());
        long placeId = savedPlaces.get(0).path("id").asLong();
        mvc.perform(delete("/api/places/mine/" + placeId).with(user("otheruser")).with(csrf()))
                .andExpect(status().isNotFound());

        long routeId = json(mvc.perform(post("/api/routes").with(user("routeowner")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(BODY.formatted("candidate-0", "검수")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("id").asLong();
        String edit = "/api/routes/" + routeId + "/points/1/guide";
        String valid = "{\"text\":\"횡단보도 앞에 멈춰서 신호와 양쪽을 확인해요.\"}";
        mvc.perform(post(edit).with(user("otheruser")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(valid)).andExpect(status().isNotFound());
        mvc.perform(post(edit).with(user("routeowner")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"횡단보도에서 신호를 무시해요.\"}")).andExpect(status().isBadRequest());
        assertEquals("TEMPLATE", jdbc.queryForObject(
                "SELECT guide_source FROM saved_route_point WHERE route_id=? AND seq=1", String.class, routeId));
        mvc.perform(post(edit).with(user("routeowner")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(valid)).andExpect(status().isNoContent());
        assertEquals("EDITED", jdbc.queryForObject(
                "SELECT guide_source FROM saved_route_point WHERE route_id=? AND seq=1", String.class, routeId));
    }

    @Test void guestQuotaRejectsFourthSearchWithoutChangingProtectedEndpoint() throws Exception {
        String points = """
                {"start":{"lat":37.59,"lng":127.08},"end":{"lat":37.59,"lng":127.09}}
                """;
        Cookie cookie = new Cookie("team_guest", java.util.UUID.randomUUID().toString());
        mvc.perform(post("/api/guest/candidates").cookie(cookie).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
        for (int expected = 2; expected >= 0; expected--) {
            var result = json(mvc.perform(post("/api/guest/candidates").cookie(cookie).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content(points)).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString());
            assertEquals(expected, result.path("left").asInt());
            assertEquals(result.path("candidates").get(0).path("candidateId"), result.path("evidence").get(0).path("candidateId"));
        }
        mvc.perform(post("/api/guest/candidates").cookie(cookie).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(points)).andExpect(status().isTooManyRequests());
        mvc.perform(post("/api/routes/candidates").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(points)).andExpect(status().isUnauthorized());
    }

    @Test void recordsGuideToneAndDistinguishesTemplateFromProviderAttempt() throws Exception {
        String base = BODY.formatted("candidate-0", "고학년 길");
        String body = base.substring(0, base.length() - 1) + ",\"tone\":\"UPPER\"}";
        long id = json(mvc.perform(post("/api/routes").with(user("routeowner")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).path("id").asLong();
        assertEquals("UPPER", jdbc.queryForObject("SELECT tone FROM guide_log WHERE route_id=?", String.class, id));
        assertEquals("TEMPLATE", jdbc.queryForObject("SELECT guide_source FROM guide_log WHERE route_id=?", String.class, id));
        assertEquals(false, jdbc.queryForObject("SELECT attempted FROM guide_log WHERE route_id=?", Boolean.class, id));
    }

    private JsonNode json(String body) throws Exception { return mapper.readTree(body); }
}
