package com.safeschoolroad.trip;

import com.safeschoolroad.member.Member;
import com.safeschoolroad.family.FamilyAccess;
import com.safeschoolroad.push.PushNotifications;
import com.safeschoolroad.route.RouteService;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TripSafetyTests {
    final TripRepository repo = mock(TripRepository.class);
    final PushNotifications push = mock(PushNotifications.class);
    final FamilyAccess access = mock(FamilyAccess.class);
    final RouteService routes = mock(RouteService.class);
    final com.safeschoolroad.member.MemberRepository members=mock(com.safeschoolroad.member.MemberRepository.class);
    final TripService service = new TripService(repo, routes, access, push, members);
    Member member(long id) {
        Member m = new Member("triptest"+id,"unused","아이",true);
        ReflectionTestUtils.setField(m,"id",id); return m;
    }
    @Test void 다른_아이의_도착_기록은_변경할_수_없다() {
        Trip trip = new Trip(member(1),12L,"학교",600);
        when(repo.findById(9L)).thenReturn(Optional.of(trip));
        assertThatThrownBy(() -> service.arrive(member(2),9,false)).isInstanceOf(AccessDeniedException.class);
        assertThat(trip.getArrivedAt()).isNull(); verifyNoInteractions(push);
    }
    @Test void 도착_중복_이벤트는_기록과_푸시를_반복하지_않는다() {
        Member child = member(1); Trip trip = new Trip(child,12L,"학교",600); trip.arrive(false);
        var arrivedAt = trip.getArrivedAt();when(repo.findById(9L)).thenReturn(Optional.of(trip));
        service.arrive(child,9,true);
        assertThat(trip.getArrivedAt()).isEqualTo(arrivedAt);assertThat(trip.isArrivedAuto()).isFalse();verifyNoInteractions(push);
    }
    @Test void 도착_완료_화면은_본인과_해당_경로의_완료_기록만_확인한다() {
        Member child=member(1);Trip trip=new Trip(child,12L,"학교",600);
        when(repo.findById(9L)).thenReturn(Optional.of(trip));
        assertThat(service.arrivalRecorded(child,12,9L)).isFalse();trip.arrive(false);
        assertThat(service.arrivalRecorded(child,12,9L)).isTrue();
        assertThat(service.arrivalRecorded(member(2),12,9L)).isFalse();
        assertThat(service.arrivalRecorded(child,13,9L)).isFalse();
        assertThat(service.arrivalRecorded(child,12,null)).isFalse();
        assertThat(service.arrivalRecorded(child,12,99L)).isFalse();
    }
    @Test void 같은_출발_요청을_재시도해도_기록과_알림을_중복_생성하지_않는다() {
        Member child=member(1),parent=member(2);
        var route=new com.safeschoolroad.route.Route(child,parent,"학교","출발",37.59,127.08,"학교",37.6,127.09,0,com.safeschoolroad.route.Route.Tone.LOWER);
        ReflectionTestUtils.setField(route,"id",12L);
        String requestId="94cb4e38-778d-4e25-8915-6681b71f3952";
        Trip trip=new Trip(child,12L,"학교",600,requestId);
        when(members.lockForTripStart(1L)).thenReturn(Optional.of(child));
        when(routes.get(child,12)).thenReturn(route);
        when(access.guardianOf(child)).thenReturn(Optional.of(new com.safeschoolroad.family.GuardianLink(parent,child,com.safeschoolroad.family.GuardianLink.Type.CREATED)));
        when(repo.findByStartRequestId(requestId)).thenReturn(Optional.of(trip));
        assertThat(service.start(child,12,600,requestId)).isSameAs(trip);
        var order=inOrder(members,repo);
        order.verify(members).lockForTripStart(1L);
        order.verify(repo).findByStartRequestId(requestId);
        verify(repo,never()).save(any());verifyNoInteractions(push);
    }
    @Test void 동시에_다른요청이_먼저_도착했으면_알림을_반복하지_않는다() {
        Member child=member(1);Trip trip=new Trip(child,12L,"학교",600);
        when(repo.findById(9L)).thenReturn(Optional.of(trip));
        when(repo.recordArrival(eq(9L),eq(1L),any(),eq(true))).thenReturn(0);
        service.arrive(child,9,true);
        verify(repo).recordArrival(eq(9L),eq(1L),any(),eq(true));
        verifyNoInteractions(push,access);
    }
    @Test void 지연후보라도_다른처리가_먼저_도착했으면_알림을_보내지_않는다() {
        Trip trip=new Trip(member(1),12L,"검수",600);
        ReflectionTestUtils.setField(trip,"id",9L);
        ReflectionTestUtils.setField(trip,"startedAt",java.time.LocalDateTime.now().minusHours(1));
        when(repo.findOpen(any())).thenReturn(java.util.List.of(trip));
        service.checkDelays();
        verify(repo).recordDelayAlert(eq(9L),any(),any(),eq(600));
        verifyNoInteractions(push,access);
    }
}
