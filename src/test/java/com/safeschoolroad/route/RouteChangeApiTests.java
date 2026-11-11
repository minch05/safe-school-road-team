package com.safeschoolroad.route;

import com.safeschoolroad.external.TmapRoute;
import com.safeschoolroad.family.FamilyAccess;
import com.safeschoolroad.family.GuardianLink;
import com.safeschoolroad.geo.LatLng;
import com.safeschoolroad.member.CurrentMember;
import com.safeschoolroad.member.Member;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RouteChangeApiTests {
    private final CurrentMember current = mock(CurrentMember.class);
    private final FamilyAccess access = mock(FamilyAccess.class);
    private final RouteService service = mock(RouteService.class);
    private final Authentication auth = mock(Authentication.class);
    private final Member viewer = mock(Member.class);
    private final Route route = mock(Route.class);
    private final RouteApiController controller = new RouteApiController(current, access, null, service, null, null);

    private void prepare(boolean check) {
        when(current.get(auth)).thenReturn(viewer);
        when(viewer.getId()).thenReturn(3L);
        when(route.getOwner()).thenReturn(viewer);
        when(route.getPoints()).thenReturn(List.of());
        when(service.get(viewer, 1L)).thenReturn(route);
        when(service.line(viewer, 1L, check)).thenReturn(new RouteService.LineResult(
                new TmapRoute(List.of(new LatLng(37.59, 127.08), new LatLng(37.60, 127.09)), List.of(), 1400, 900),
                new ChangeDetector.Result(check, check ? 2 : 0, 1)));
    }

    @Test void ordinaryLineRequestDoesNotAskForChangeCheck() {
        prepare(false);
        assertThat(controller.line(1L, auth)).containsEntry("changed", false);
        verify(service).line(viewer, 1L, false);
        verify(service, never()).line(viewer, 1L, true);
    }

    @Test void explicitRequestReturnsCheckedLineAndChangeResult() {
        prepare(true);
        assertThat(controller.check(1L, auth)).containsEntry("changed", true)
                .containsEntry("distance", 1400).containsEntry("time", 900);
        verify(service).line(viewer, 1L, true);
    }

    @Test void linkedChildDoesNotReceiveChangeWarning() {
        prepare(true);
        when(access.guardianOf(viewer)).thenReturn(Optional.of(mock(GuardianLink.class)));
        assertThat(controller.check(1L, auth)).containsEntry("changed", false);
    }
}
