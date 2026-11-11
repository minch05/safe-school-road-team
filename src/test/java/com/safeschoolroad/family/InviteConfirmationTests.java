package com.safeschoolroad.family;
import com.safeschoolroad.member.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest @AutoConfigureMockMvc @Transactional
class InviteConfirmationTests {
 @Autowired MockMvc mvc;@Autowired MemberRepository members;@Autowired InviteCodeRepository invites;
 @Autowired GuardianLinkRepository links;@Autowired FamilyService family;
 Member member(){return members.saveAndFlush(new Member("iconfirm"+UUID.randomUUID().toString().substring(0,8),"unused","합성검수",true));}
 @Test void 상태조회는_현재회원의_연동만_반환하고_캐시하지않는다()throws Exception{
  var parent=member();var child=member();var other=member();
  links.saveAndFlush(new GuardianLink(parent,child,GuardianLink.Type.INVITED));
  mvc.perform(get("/api/invite/status").with(user(child.getUsername())))
   .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
   .andExpect(jsonPath("$.linked").value(true)).andExpect(jsonPath("$.guardian").value(parent.getNickname()));
  mvc.perform(get("/api/invite/status").with(user(other.getUsername())))
   .andExpect(status().isOk()).andExpect(jsonPath("$.linked").value(false)).andExpect(jsonPath("$.guardian").value(""));
  mvc.perform(get("/api/invite/status")).andExpect(status().isUnauthorized());
 }
 String preview(Member child,String code,MockHttpSession session)throws Exception{
  var result=mvc.perform(post("/api/invite/preview").session(session).with(user(child.getUsername())).with(csrf()).contentType("application/json").content("{\"code\":\""+code+"\"}"))
   .andExpect(status().isOk()).andReturn();
  return new com.fasterxml.jackson.databind.ObjectMapper().readTree(result.getResponse().getContentAsString()).path("previewToken").asText();
 }
 void redeem(Member child,String code,String token,MockHttpSession session,int status)throws Exception{
  mvc.perform(post("/api/invite/redeem").session(session).with(user(child.getUsername())).with(csrf()).contentType("application/json").content("{\"code\":\""+code+"\",\"previewToken\":\""+token+"\"}"))
   .andExpect(status().is(status));
 }
 @Test void 확인없는_직접연동은_거절한다()throws Exception{
  var parent=member();var child=member();var code=family.issueInvite(parent).getCode();
  redeem(child,code,"wrong",new MockHttpSession(),400);assertThat(links.findByChild(child)).isEmpty();
 }
 @Test void 확인후_같은코드가_겹치면_임의의보호자에게_연동하지않는다()throws Exception{
  var first=member();var second=member();var child=member();var invite=family.issueInvite(first);invites.flush();
  var session=new MockHttpSession();String token=preview(child,invite.getCode(),session);
  invites.saveAndFlush(new InviteCode(second,invite.getCode()));
  redeem(child,invite.getCode(),token,session,400);assertThat(links.findByChild(child)).isEmpty();
  mvc.perform(post("/api/invite/preview").session(session).with(user(child.getUsername())).with(csrf()).contentType("application/json").content("{\"code\":\""+invite.getCode()+"\"}"))
   .andExpect(status().isBadRequest());
  assertThat(invites.findUsable(invite.getCode(),java.time.LocalDateTime.now())).hasSize(2);
 }
 @Test void 같은코드가_다른초대로_재사용되면_이전확인으로_연동하지않는다()throws Exception{
  var first=member();var second=member();var child=member();var invite=family.issueInvite(first);String code=invite.getCode();invites.flush();
  var session=new MockHttpSession();String token=preview(child,code,session);
  invites.delete(invite);invites.flush();invites.saveAndFlush(new InviteCode(second,code));
  redeem(child,code,token,session,400);assertThat(links.findByChild(child)).isEmpty();
 }
 @Test void 정상확인으로_연동후_확인토큰을_다시쓰지못한다()throws Exception{
  var parent=member();var child=member();String code=family.issueInvite(parent).getCode();invites.flush();
  var session=new MockHttpSession();String token=preview(child,code,session);assertThat(token).isNotBlank();
  redeem(child,code,token,session,200);links.flush();assertThat(links.findByChild(child).orElseThrow().getGuardian().getId()).isEqualTo(parent.getId());
  redeem(child,code,token,session,400);
 }
}
