package com.safeschoolroad.trip;

import com.safeschoolroad.member.CurrentMember;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

// 출발·도착 이벤트 (N-01). 위치는 받지 않는다.
@RestController
public class TripApiController {

    private final CurrentMember current;
    private final TripService trips;

    public TripApiController(CurrentMember current, TripService trips) {
        this.current = current;
        this.trips = trips;
    }

    public record StartRequest(long routeId, int expectedSeconds, String requestId) {
    }

    public record ArriveRequest(boolean auto) {
    }

    @PostMapping("/api/trips/start")
    public Map<String, Object> start(@RequestBody StartRequest req, Authentication auth) {
        Trip trip = trips.start(current.get(auth), req.routeId(), req.expectedSeconds(), req.requestId());
        return Map.of("tripId", trip.getId());
    }

    @PostMapping("/api/trips/{id}/arrive")
    public Map<String, Object> arrive(@PathVariable long id, @RequestBody(required = false) ArriveRequest req,
                                      Authentication auth) {
        trips.arrive(current.get(auth), id, req != null && req.auto());
        return Map.of("ok", true);
    }
}
