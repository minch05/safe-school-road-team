package com.safeschoolroad.place;

import com.safeschoolroad.member.*;
import com.safeschoolroad.route.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest @AutoConfigureMockMvc @Transactional
class PlaceManagementTests {
    @Autowired MockMvc mvc;
    @Autowired MemberRepository members;
    @Autowired PlaceRepository places;
    @Autowired RouteRepository routes;
    @Autowired EntityManager em;
    Member owner(){return members.saveAndFlush(new Member("placemanage","unused","검수",true));}
    Place place(Member owner,String name){return places.saveAndFlush(new Place(owner,name,37.59,127.08));}
    String storedName(long id){em.flush();em.clear();return places.findById(id).orElseThrow().getName();}
    @Test void 이름의_앞뒤공백을_제거해_저장하고_좌표는_유지한다() throws Exception {
        var owner=owner();var place=place(owner,"집");long id=place.getId();
        mvc.perform(post("/places/"+id+"/rename").with(user(owner.getUsername())).with(csrf()).param("name","  학교 정문  "))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/places")).andExpect(flash().attribute("toast","이름을 바꿨어요."));
        assertThat(storedName(id)).isEqualTo("학교 정문");var stored=places.findById(id).orElseThrow();
        assertThat(stored.getLat()).isEqualTo(37.59);assertThat(stored.getLng()).isEqualTo(127.08);
    }
    @Test void 중복이름과_빈이름과_너무긴이름은_저장하지_않는다() throws Exception {
        var owner=owner();var place=place(owner,"집");place(owner,"학교");long id=place.getId();
        for(String name:new String[]{"학교","   ","a".repeat(31)}) {
            mvc.perform(post("/places/"+id+"/rename").with(user(owner.getUsername())).with(csrf()).param("name",name))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("toast"));
            assertThat(storedName(id)).isEqualTo("집");
        }
    }
    @Test void 다른사람은_이름변경과_삭제를_할수없다() throws Exception {
        var owner=owner();var place=place(owner,"집");long id=place.getId();
        var other=members.saveAndFlush(new Member("placeother","unused","검수",true));
        mvc.perform(post("/places/"+id+"/rename").with(user(other.getUsername())).with(csrf()).param("name","변경"))
            .andExpect(status().isForbidden());
        mvc.perform(post("/places/"+id+"/delete").with(user(other.getUsername())).with(csrf())).andExpect(status().isForbidden());
        assertThat(storedName(id)).isEqualTo("집");
    }
    @Test void 장소삭제후에도_기존경로의_출발정보는_유지된다() throws Exception {
        var owner=owner();var place=place(owner,"집");long placeId=place.getId();
        var route=routes.saveAndFlush(new Route(owner,owner,"통학","집",37.59,127.08,"학교",37.6,127.09,0,Route.Tone.LOWER));long routeId=route.getId();
        mvc.perform(post("/places/"+placeId+"/delete").with(user(owner.getUsername())).with(csrf()))
            .andExpect(status().is3xxRedirection()).andExpect(flash().attribute("toast","장소를 지웠어요."));
        em.flush();em.clear();assertThat(places.findById(placeId)).isEmpty();
        var stored=routes.findById(routeId).orElseThrow();assertThat(stored.getStartName()).isEqualTo("집");
        assertThat(stored.getStartLat()).isEqualTo(37.59);assertThat(stored.getStartLng()).isEqualTo(127.08);
    }
}
