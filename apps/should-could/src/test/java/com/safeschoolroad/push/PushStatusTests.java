package com.safeschoolroad.push;
import com.safeschoolroad.member.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class PushStatusTests {
 @Test void 서버등록은_현재회원과_기기주소가_함께_일치해야한다(){
  var repo=mock(PushSubscriptionRepository.class);var me=mock(Member.class);var other=mock(Member.class);
  when(repo.existsByMemberAndEndpoint(me,"device")).thenReturn(true);
  var service=new PushService(repo,new ObjectMapper());
  assertThat(service.registered(me,"device")).isTrue();assertThat(service.registered(other,"device")).isFalse();
  verify(repo).existsByMemberAndEndpoint(me,"device");verify(repo).existsByMemberAndEndpoint(other,"device");
 }
 @Test void 상태응답은_등록여부만_반환하고_기기주소는_반환하지않는다(){
  var current=mock(CurrentMember.class);var service=mock(PushService.class);var me=mock(Member.class);
  when(current.get(null)).thenReturn(me);when(service.registered(me,"device")).thenReturn(true);
  var response=new PushApiController(current,service).status(new PushApiController.Endpoint("device"),null);
  assertThat(response.getBody()).containsOnly(entry("registered",true));assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
 }
}
