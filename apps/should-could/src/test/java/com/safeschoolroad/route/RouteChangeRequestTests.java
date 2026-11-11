package com.safeschoolroad.route;

import com.safeschoolroad.external.TmapClient;
import com.safeschoolroad.external.TmapRoute;
import com.safeschoolroad.family.FamilyAccess;
import com.safeschoolroad.geo.LatLng;
import com.safeschoolroad.member.Member;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RouteChangeRequestTests {
    private final RouteRepository repository = mock(RouteRepository.class);
    private final FamilyAccess access = mock(FamilyAccess.class);
    private final TmapClient tmap = mock(TmapClient.class);
    private final Member viewer = mock(Member.class);
    private final Route route = mock(Route.class);
    private final RouteService service = new RouteService(null, repository, null, null, access, null, null, tmap);

    private void prepare() {
        when(repository.findById(1L)).thenReturn(Optional.of(route));
        when(route.getOwner()).thenReturn(viewer);
        when(access.canManage(viewer, viewer)).thenReturn(true);
        when(route.getPoints()).thenReturn(List.of());
        when(route.getStartLat()).thenReturn(37.59);
        when(route.getStartLng()).thenReturn(127.08);
        when(route.getEndLat()).thenReturn(37.60);
        when(route.getEndLng()).thenReturn(127.09);
    }

    @Test void viewingLineDoesNotUpdateChangeCheck() {
        prepare();
        when(tmap.pedestrian(any(), any(), anyInt(), anyList())).thenReturn(
                new TmapRoute(List.of(new LatLng(37.59, 127.08), new LatLng(37.60, 127.09)), List.of(), 1400, 900));
        service.line(viewer, 1L, false);
        verify(route, never()).recordChangeCheck(anyBoolean());
        service.line(viewer, 1L, true);
        verify(route).recordChangeCheck(false);
    }

    @Test void failedCheckDoesNotRecordSameOrChanged() {
        prepare();
        when(tmap.pedestrian(any(), any(), anyInt(), anyList())).thenThrow(new IllegalStateException("조회 실패"));
        assertThatThrownBy(() -> service.line(viewer, 1L, true)).isInstanceOf(IllegalStateException.class);
        verify(route, never()).recordChangeCheck(anyBoolean());
    }

    @Test void unrelatedMemberCannotCheckOrCallProvider() {
        prepare();
        when(access.canManage(viewer, viewer)).thenReturn(false);
        assertThatThrownBy(() -> service.line(viewer, 1L, true))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verifyNoInteractions(tmap);
    }
}
