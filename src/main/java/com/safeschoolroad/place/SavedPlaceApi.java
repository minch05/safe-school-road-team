package com.safeschoolroad.place;

import com.safeschoolroad.route.Point;
import com.safeschoolroad.saved.SavedRouteStore;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/places/mine")
public class SavedPlaceApi {
    private final SavedPlaceStore places;
    private final SavedRouteStore routes;

    public SavedPlaceApi(SavedPlaceStore places, SavedRouteStore routes) {
        this.places = places;
        this.routes = routes;
    }

    public record SavePlace(String name, Point point) { }

    @GetMapping
    ResponseEntity<?> list(Authentication auth) {
        return routes.memberId(auth.getName()).map(id -> ResponseEntity.ok(places.list(id)))
                .orElseGet(() -> ResponseEntity.status(401).build());
    }

    @PostMapping
    ResponseEntity<?> save(@RequestBody SavePlace request, Authentication auth) {
        String name = request.name() == null ? "" : request.name().strip();
        if (name.isEmpty() || name.length() > 30 || request.point() == null || !request.point().inSupportedArea()) {
            return ResponseEntity.badRequest().body(Map.of("error", "장소 이름과 서울 범위의 좌표를 확인해 주세요."));
        }
        var member = routes.memberId(auth.getName());
        if (member.isEmpty()) return ResponseEntity.status(401).build();
        places.save(member.get(), name, request.point());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    ResponseEntity<?> delete(@PathVariable long id, Authentication auth) {
        var member = routes.memberId(auth.getName());
        if (member.isEmpty()) return ResponseEntity.status(401).build();
        return places.delete(member.get(), id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }
}
