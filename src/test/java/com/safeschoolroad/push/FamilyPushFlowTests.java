package com.safeschoolroad.push;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@AutoConfigureMockMvc
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:family_push_tests;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "app.push.poll-ms=600000"
})
class FamilyPushFlowTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PasswordEncoder passwords;
    @Autowired WebPush push;
    @MockitoBean PushTransport transport;
    private static final String PW = "test-password-123";
    private static final String ENDPOINT = "https://fcm.googleapis.com/fcm/send/test";
    private static final String OTHER_ENDPOINT = "https://fcm.googleapis.com/fcm/send/second-test";

    @BeforeEach void prepare() {
        jdbc.update("DELETE FROM saved_route");
        jdbc.update("DELETE FROM persistent_logins");
        jdbc.update("DELETE FROM member");
        for (String login : new String[]{"guardian1", "candidate1", "candidate2", "stranger1"}) {
            jdbc.update("INSERT INTO member(login_id,password_hash,nickname,age_14_or_older) VALUES(?,?,?,TRUE)",
                    login, passwords.encode(PW), login);
        }
        when(transport.publicKey()).thenReturn("configured");
    }

    @Test void guardianCreatesChildAndManagesOnlyLinkedRoutesAndSessions() throws Exception {
        jsonPost("/api/family/children", "guardian1",
                "{\"loginId\":\"createdkid\",\"password\":\"old-password-123\",\"nickname\":\"아이\"}")
                .andExpect(status().isCreated());
        long child = id("createdkid");
        long route = route(child);
        mvc.perform(get("/api/routes/" + route).with(user("guardian1"))).andExpect(status().isOk());
        mvc.perform(get("/api/routes/" + route).with(user("stranger1"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/family/children/" + child + "/routes").with(user("guardian1")))
                .andExpect(jsonPath("$.routes[0].id").value(route));
        mvc.perform(get("/api/family/children/" + child + "/routes").with(user("stranger1")))
                .andExpect(status().isNotFound());
        var session = (MockHttpSession) mvc.perform(post("/api/auth/login").with(csrf())
                .param("username", "createdkid").param("password", "old-password-123").param("remember-me", "true"))
                .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
        mvc.perform(get("/api/routes").session(session)).andExpect(jsonPath("$.routes[0].id").value(route));
        jsonPost("/api/family/children/" + child + "/password", "stranger1", "{\"password\":\"new-password-123\"}")
                .andExpect(status().isNotFound());
        jsonPost("/api/family/children/" + child + "/password", "guardian1", "{\"password\":\"new-password-123\"}")
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/routes").session(session)).andExpect(status().isUnauthorized());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM persistent_logins WHERE username='createdkid'", Integer.class));
        mvc.perform(delete("/api/family/links/" + child).with(user("guardian1")).with(csrf()))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/family/children/" + child).with(user("guardian1")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"nickname\":\"아이\"}"))
                .andExpect(status().isNoContent());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM saved_route WHERE id=?", Integer.class, route));
    }

    @Test void invitationRequiresMatchingPreviewAndOnlyOneConcurrentRedemptionSucceeds() throws Exception {
        String code = payload(jsonPost("/api/invite/issue", "guardian1", null)).get("code").asText();
        var first = new MockHttpSession();
        var second = new MockHttpSession();
        String preview1 = preview("candidate1", first, code);
        String preview2 = preview("candidate2", second, code);
        redeem("candidate1", second, code, preview1).andExpect(status().isBadRequest());

        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> redeem("candidate1", first, code, preview1).andReturn().getResponse().getStatus());
            var b = pool.submit(() -> redeem("candidate2", second, code, preview2).andReturn().getResponse().getStatus());
            assertEquals(java.util.Set.of(204, 400), java.util.Set.of(a.get(), b.get()));
        }
        long child = jdbc.queryForObject("SELECT child_id FROM family_link WHERE guardian_id=?", Long.class, id("guardian1"));
        long route = route(child);
        mvc.perform(get("/api/routes/" + route).with(user("guardian1"))).andExpect(status().isOk());
        jsonPost("/api/family/children/" + child + "/password", "guardian1", "{\"password\":\"new-password-123\"}")
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/family/links/" + child).with(user("guardian1")).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/routes/" + route).with(user("guardian1"))).andExpect(status().isNotFound());
    }

    @Test void startAndArrivalQueueExactlyOncePerGuardianDeviceAndRetriesDelivery() throws Exception {
        jdbc.update("INSERT INTO family_link(child_id,guardian_id,link_type) VALUES(?,?,'INVITED')", id("candidate1"), id("guardian1"));
        long route = route(id("candidate1"));
        byte[] point = new byte[65];
        point[0] = 4;
        String key = Base64.getUrlEncoder().withoutPadding().encodeToString(point);
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[16]);
        subscribe("guardian1", ENDPOINT, key, secret);
        subscribe("guardian1", OTHER_ENDPOINT, key, secret);
        jsonPost("/api/push/status", "stranger1", json.writeValueAsString(java.util.Map.of("endpoint", ENDPOINT)))
                .andExpect(jsonPath("$.registered").value(false));
        jsonPost("/api/push/unsubscribe", "stranger1", json.writeValueAsString(java.util.Map.of("endpoint", ENDPOINT)))
                .andExpect(status().isNoContent());
        String start = "{\"routeId\":" + route + ",\"requestId\":\"" + UUID.randomUUID() + "\"}";
        jsonPost("/api/trips/start", "stranger1", start).andExpect(status().isNotFound());
        long trip = payload(jsonPost("/api/trips/start", "candidate1", start)).get("tripId").asLong();
        assertEquals(trip, payload(jsonPost("/api/trips/start", "candidate1", start)).get("tripId").asLong());
        jsonPost("/api/trips/" + trip + "/arrive", "guardian1", null).andExpect(status().isNotFound());
        jsonPost("/api/trips/" + trip + "/arrive", "candidate1", null).andExpect(status().isNoContent());
        jsonPost("/api/trips/" + trip + "/arrive", "candidate1", null).andExpect(status().isNoContent());
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM web_push_pending", Integer.class));
        when(transport.send(any(), anyString(), anyString())).thenReturn(PushTransport.Result.RETRY);
        push.deliverPending();
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM web_push_pending WHERE attempts=1", Integer.class));

        subscribe("stranger1", ENDPOINT, key, secret);
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM web_push_pending", Integer.class));
        jdbc.update("UPDATE web_push_pending SET due_at=CURRENT_TIMESTAMP");
        when(transport.send(any(), anyString(), anyString())).thenReturn(PushTransport.Result.ACCEPTED);
        push.deliverPending();
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM web_push_pending", Integer.class));
        verify(transport, times(6)).send(any(), anyString(), anyString());
    }

    private void subscribe(String user, String endpoint, String key, String secret) throws Exception {
        jsonPost("/api/push/subscribe", user, json.writeValueAsString(java.util.Map.of(
                "endpoint", endpoint, "keys", java.util.Map.of("p256dh", key, "auth", secret))))
                .andExpect(status().isNoContent());
    }

    private long id(String login) {
        return jdbc.queryForObject("SELECT id FROM member WHERE login_id=?", Long.class, login);
    }

    private long route(long owner) {
        jdbc.update("""
                INSERT INTO saved_route(member_id,name,start_lat,start_lng,end_lat,end_lng,candidate_id,search_option,point_count)
                VALUES(?,'가족 시험',37.59,127.08,37.60,127.09,'candidate-0',0,1)
                """, owner);
        return jdbc.queryForObject("SELECT id FROM saved_route WHERE member_id=?", Long.class, owner);
    }

    private String preview(String login, MockHttpSession session, String code) throws Exception {
        return payload(mvc.perform(post("/api/invite/preview").with(user(login)).with(csrf()).session(session)
                .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + code + "\"}"))).get("previewToken").asText();
    }

    private ResultActions redeem(String login, MockHttpSession session, String code, String preview) throws Exception {
        return mvc.perform(post("/api/invite/redeem").with(user(login)).with(csrf()).session(session)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(
                        java.util.Map.of("code", code, "previewToken", preview))));
    }

    private ResultActions jsonPost(String target, String login, String body) throws Exception {
        var post = post(target).with(user(login)).with(csrf());
        if (body != null) post.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(post);
    }

    private JsonNode payload(ResultActions response) throws Exception {
        return json.readTree(response.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
}
