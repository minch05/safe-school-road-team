package com.safeschoolroad.family;

import com.safeschoolroad.member.Member;
import com.safeschoolroad.member.MemberRepository;
import com.safeschoolroad.route.Route;
import com.safeschoolroad.route.RouteRepository;
import com.safeschoolroad.place.Place;
import com.safeschoolroad.place.PlaceRepository;
import com.safeschoolroad.trip.Trip;
import com.safeschoolroad.trip.TripRepository;
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

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class FamilyBoundaryTests {
    @Autowired MockMvc mvc;
    @Autowired MemberRepository members;
    @Autowired FamilyService family;
    @Autowired RouteRepository routes;
    @Autowired PlaceRepository places;
    @Autowired TripRepository trips;
    Member adult(String name){return members.save(new Member(name,"unused","검수",true));}
    @Test void 다른_가족은_경로와_장소와_출발도착_기록에_접근하지_못한다() throws Exception {
        Member parent=adult("boundaryparent"),stranger=adult("boundaryother");
        Member child=family.createChild(parent,"boundarychild","password1","아이");
        Route route=routes.save(new Route(child,parent,"학교","출발",37.59,127.08,"학교",37.6,127.09,0,Route.Tone.LOWER));
        Place place=places.save(new Place(child,"학교",37.6,127.09));
        Trip trip=trips.save(new Trip(child,route.getId(),"학교",600));
        for(String suffix:new String[]{"","/navigate","/arrived","/points","/line"}) {
            String url=suffix.equals("/points")||suffix.equals("/line")?"/api/routes/":"/routes/";
            mvc.perform(get(url+route.getId()+suffix).with(user(stranger.getUsername()))).andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/places").param("ownerId",child.getId().toString()).with(user(stranger.getUsername()))).andExpect(status().isForbidden());
        mvc.perform(post("/places/"+place.getId()+"/delete").with(user(stranger.getUsername())).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/api/trips/"+trip.getId()+"/arrive").with(user(stranger.getUsername())).with(csrf()).contentType("application/json").content("{\"auto\":false}")).andExpect(status().isForbidden());
        assertThat(places.findById(place.getId())).isPresent();assertThat(trip.getArrivedAt()).isNull();
        mvc.perform(get("/api/routes/"+route.getId()+"/points").with(user(parent.getUsername()))).andExpect(status().isOk());
    }
    @Test void 도착_URL_값만으로_성공을_표시하지_않는다() throws Exception {
        Member member=adult("boundaryarrival");
        Route route=routes.save(new Route(member,member,"학교","출발",37.59,127.08,"학교",37.6,127.09,0,Route.Tone.LOWER));
        mvc.perform(get("/routes/"+route.getId()+"/arrived?notified=true&recorded=true").with(user(member.getUsername())))
                .andExpect(status().isOk()).andExpect(model().attribute("recorded",false));
    }
}
