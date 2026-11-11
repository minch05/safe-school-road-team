package com.safeschoolroad.facility;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class FacilityApiTests {

    @Autowired
    MockMvc mvc;

    @Test
    void 공개_시설은_비로그인으로도_볼_수_있다() throws Exception {
        mvc.perform(get("/api/facilities").param("south", "37.56").param("west", "126.97")
                        .param("north", "37.57").param("east", "126.98"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser
    void 좁은_범위는_시설_목록을_준다() throws Exception {
        mvc.perform(get("/api/facilities").param("south", "37.56").param("west", "126.97")
                        .param("north", "37.57").param("east", "126.98"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tooWide").value(false))
                .andExpect(jsonPath("$.facilities").isArray());
    }

    @Test
    @WithMockUser
    void 너무_넓은_범위는_받지_않는다() throws Exception {
        mvc.perform(get("/api/facilities").param("south", "37.4").param("west", "126.8")
                        .param("north", "37.7").param("east", "127.2"))
                .andExpect(jsonPath("$.tooWide").value(true));
    }
    @Test void 잘못된_범위와_종류는_거부한다() throws Exception {
        for(String south: new String[]{"NaN","Infinity","36.0","37.58"}) {
            mvc.perform(get("/api/facilities").param("south",south).param("west","126.97")
                .param("north","37.57").param("east","126.98")).andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/facilities").param("south","37.56").param("west","126.97")
                .param("north","37.57").param("east","126.98").param("kinds","INVALID"))
                .andExpect(status().isBadRequest());
    }
    @Test void 시설_종류_필터와_사고영역을_제공한다() throws Exception {
        mvc.perform(get("/api/facilities").param("south","37.56").param("west","126.97")
                .param("north","37.57").param("east","126.98").param("kinds","CHILD_SAFETY_HOUSE")
                .param("accidents","true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.areas").isArray())
                .andExpect(jsonPath("$.truncated").isBoolean())
                .andExpect(jsonPath("$.facilities[?(@.kind != 'CHILD_SAFETY_HOUSE')]").isEmpty());
    }
}
