package com.safeschoolroad.auth;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class AuthFlowTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;

    @Test void signupStoresHashRejectsDuplicateAndUnderage() throws Exception {
        String login = "user" + UUID.randomUUID().toString().substring(0, 8);
        String valid = "{\"loginId\":\""+login+"\",\"password\":\"verySecret123\",\"nickname\":\"보호자\",\"age14OrOlder\":true}";
        mvc.perform(post("/api/auth/signup").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(valid))
                .andExpect(status().isCreated());
        String stored = jdbc.queryForObject("SELECT password_hash FROM member WHERE login_id=?", String.class, login);
        org.junit.jupiter.api.Assertions.assertNotEquals("verySecret123", stored);
        org.junit.jupiter.api.Assertions.assertTrue(encoder.matches("verySecret123", stored));
        mvc.perform(post("/api/auth/signup").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(valid))
                .andExpect(status().isConflict());
        String child = valid.replace("\"age14OrOlder\":true", "\"age14OrOlder\":false").replace(login, login+"x");
        mvc.perform(post("/api/auth/signup").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(child))
                .andExpect(status().isBadRequest());
    }

    @Test void sessionLoginAndLogoutProtectRouteApi() throws Exception {
        String login = "user" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO member(login_id,password_hash,nickname,age_14_or_older) VALUES (?,?,?,?)",
                login, encoder.encode("verySecret123"), "보호자", true);
        mvc.perform(post("/api/auth/login").with(csrf()).param("username",login).param("password","wrong-password"))
                .andExpect(status().isUnauthorized());
        var session = mvc.perform(post("/api/auth/login").with(csrf()).param("username",login).param("password","verySecret123"))
                .andExpect(status().isOk()).andReturn().getRequest().getSession();
        mvc.perform(get("/api/auth/me").session((org.springframework.mock.web.MockHttpSession) session))
                .andExpect(jsonPath("$.authenticated").value(true));
        mvc.perform(post("/api/auth/logout").with(csrf()).session((org.springframework.mock.web.MockHttpSession) session))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/routes/candidates").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"start\":{\"lat\":37.59,\"lng\":127.08},\"end\":{\"lat\":37.6,\"lng\":127.09}}"))
                .andExpect(status().isUnauthorized());
    }
}
