package com.safeschoolroad.family;

import com.safeschoolroad.saved.SavedRouteStore;
import jakarta.servlet.http.HttpSession;
import java.io.Serializable;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class FamilyApi {
    private static final String PREVIEW = "familyInvitePreview";
    private final FamilyService family;
    private final SavedRouteStore routes;
    private final FamilyTrips trips;

    public FamilyApi(FamilyService family, SavedRouteStore routes, FamilyTrips trips) {
        this.family = family;
        this.routes = routes;
        this.trips = trips;
    }

    public record Child(String loginId, String password, String nickname) {}
    public record Password(String password) {}
    public record DeleteChild(String nickname) {}
    public record Code(String code, String previewToken) {}
    private record Preview(long child, long guardian, String code, String nonce) implements Serializable {}

    @GetMapping("/api/family")
    FamilyService.Family show(Authentication auth) {
        return family.family(member(auth));
    }

    @GetMapping("/api/family/children/{id}/routes")
    Map<String, Object> childRoutes(@PathVariable long id, Authentication auth) {
        long guardian = member(auth);
        if (!family.linked(guardian, id)) throw FamilyService.missing();
        return Map.of("routes", routes.list(id), "trips", trips.recent(guardian, id));
    }

    @PostMapping("/api/family/children")
    @ResponseStatus(HttpStatus.CREATED)
    void create(@RequestBody Child child, Authentication auth) {
        family.createChild(member(auth), child.loginId(), child.password(), child.nickname());
    }

    @PostMapping("/api/family/children/{id}/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void reset(@PathVariable long id, @RequestBody Password body, Authentication auth) {
        family.reset(member(auth), id, body.password());
    }

    @DeleteMapping("/api/family/children/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable long id, @RequestBody DeleteChild body, Authentication auth) {
        family.delete(member(auth), id, body.nickname());
    }

    @DeleteMapping("/api/family/links/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void unlink(@PathVariable long id, Authentication auth) {
        family.unlink(member(auth), id);
    }

    @PostMapping("/api/invite/issue")
    FamilyService.Invite issue(Authentication auth) {
        return family.issue(member(auth));
    }

    @PostMapping("/api/invite/preview")
    Map<String, String> preview(@RequestBody Code body, Authentication auth, HttpSession session) {
        session.removeAttribute(PREVIEW);
        long child = member(auth);
        String code = normalize(body.code());
        var guardian = family.preview(child, code);
        String nonce = UUID.randomUUID().toString();
        session.setAttribute(PREVIEW, new Preview(child, guardian.id(), code, nonce));
        return Map.of("guardian", guardian.nickname(), "previewToken", nonce);
    }

    @PostMapping("/api/invite/redeem")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void redeem(@RequestBody Code body, Authentication auth, HttpSession session) {
        long child = member(auth);
        if (!(session.getAttribute(PREVIEW) instanceof Preview preview) || preview.child() != child
                || !preview.nonce().equals(body.previewToken()) || !preview.code().equals(normalize(body.code()))) {
            throw FamilyService.bad("보호자를 다시 확인해 주세요.");
        }
        family.redeem(child, preview.guardian(), preview.code());
        session.removeAttribute(PREVIEW);
    }

    @PostMapping("/api/trips/start")
    Map<String, Long> start(@RequestBody FamilyTrips.Start body, Authentication auth) {
        return Map.of("tripId", trips.start(member(auth), body));
    }

    @PostMapping("/api/trips/{id}/arrive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void arrive(@PathVariable long id, Authentication auth) {
        trips.arrive(member(auth), id);
    }

    private long member(Authentication auth) {
        return family.memberId(auth.getName()).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }

    private static String normalize(String code) {
        return code == null ? "" : code.strip().toUpperCase(Locale.ROOT);
    }
}
