package com.safeschoolroad;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringBootTest
@AutoConfigureMockMvc
class SetupTests {
    @Autowired MockMvc mvc;
    @Test void setupEndpointsAreAvailable() throws Exception {
        mvc.perform(get("/")).andExpect(status().isOk()).andExpect(jsonPath("$.project").value("safe-school-road"));
        mvc.perform(get("/health/setup")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }
}
