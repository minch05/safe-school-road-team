package com.safeschoolroad.family;
import com.safeschoolroad.member.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest
class InviteUseConcurrencyTests {
 @Autowired MemberRepository members;@Autowired InviteCodeRepository invites;@Autowired GuardianLinkRepository links;
 @Autowired FamilyService family;@Autowired TransactionTemplate tx;
 Member create(){return tx.execute(s->members.saveAndFlush(new Member("inviterace"+UUID.randomUUID().toString().substring(0,8),"unused","합성검수",true)));}
 @Test void 먼저자녀로_연동된계정은_동시_아이생성을_거절한다()throws Exception{
  var parent=create();var teen=create();var invite=family.issueInvite(parent);
  String childName="racekid"+UUID.randomUUID().toString().substring(0,8);
  var pool=Executors.newFixedThreadPool(2);var held=new CountDownLatch(1);var release=new CountDownLatch(1);
  try{
   var first=pool.submit(()->tx.executeWithoutResult(s->{family.redeemInvite(teen,invite.getCode(),invite.getId());held.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("검사 대기 실패");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}));
   assertThat(held.await(10,TimeUnit.SECONDS)).isTrue();var started=new CountDownLatch(1);
   var second=pool.submit(()->{started.countDown();try{family.createChild(teen,childName,"testpassword1","합성아이");return "생성됨";}catch(IllegalStateException e){return e.getMessage();}});
   assertThat(started.await(10,TimeUnit.SECONDS)).isTrue();assertThatThrownBy(()->second.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
   release.countDown();first.get(20,TimeUnit.SECONDS);
   assertThat(second.get(20,TimeUnit.SECONDS)).contains("보호자와 연동된 계정");
   assertThat(members.findByUsername(childName)).isEmpty();
   assertThat(links.findByChild(teen).orElseThrow().getGuardian().getId()).isEqualTo(parent.getId());
  }finally{
   release.countDown();pool.shutdown();if(!pool.awaitTermination(30,TimeUnit.SECONDS)){pool.shutdownNow();pool.awaitTermination(10,TimeUnit.SECONDS);}
   tx.executeWithoutResult(s->{members.findByUsername(childName).ifPresent(child->{links.findByChild(child).ifPresent(links::delete);links.flush();members.delete(child);});links.findByChild(teen).ifPresent(links::delete);links.flush();invites.deleteAll(invites.findByGuardian(parent));invites.flush();members.deleteAll(List.of(teen,parent));});
  }
 }
 @Test void 아이생성중인_보호자는_동시에_다른보호자의_자녀가되지않는다()throws Exception{
  var parent=create();var other=create();var invite=family.issueInvite(other);
  String childName="racekid"+UUID.randomUUID().toString().substring(0,8);
  var pool=Executors.newFixedThreadPool(2);var held=new CountDownLatch(1);var release=new CountDownLatch(1);
  try{
   var first=pool.submit(()->tx.execute(s->{var child=family.createChild(parent,childName,"testpassword1","합성아이");held.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("검사 대기 실패");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}return child.getId();}));
   assertThat(held.await(10,TimeUnit.SECONDS)).isTrue();var started=new CountDownLatch(1);
   var second=pool.submit(()->{started.countDown();try{family.redeemInvite(parent,invite.getCode(),invite.getId());return "연동됨";}catch(IllegalStateException e){return e.getMessage();}});
   assertThat(started.await(10,TimeUnit.SECONDS)).isTrue();assertThatThrownBy(()->second.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
   release.countDown();var childId=first.get(20,TimeUnit.SECONDS);
   assertThat(second.get(20,TimeUnit.SECONDS)).contains("자녀와 연동된 보호자 계정");
   assertThat(links.findByChild(parent)).isEmpty();
   assertThat(links.findByChild(members.findById(childId).orElseThrow()).orElseThrow().getGuardian().getId()).isEqualTo(parent.getId());
  }finally{
   release.countDown();pool.shutdown();if(!pool.awaitTermination(30,TimeUnit.SECONDS)){pool.shutdownNow();pool.awaitTermination(10,TimeUnit.SECONDS);}
   tx.executeWithoutResult(s->{members.findByUsername(childName).ifPresent(child->{links.findByChild(child).ifPresent(links::delete);links.flush();members.delete(child);});invites.deleteAll(invites.findByGuardian(other));invites.flush();members.deleteAll(List.of(parent,other));});
  }
 }
 @Test void 같은보호자의_동시발급은_마지막초대만_남긴다()throws Exception{
  var parent=create();var pool=Executors.newFixedThreadPool(2);
  var held=new CountDownLatch(1);var release=new CountDownLatch(1);
  try{
   var first=pool.submit(()->tx.execute(s->{var invite=family.issueInvite(parent);held.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("검사 대기 실패");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}return invite.getId();}));
   assertThat(held.await(10,TimeUnit.SECONDS)).isTrue();var started=new CountDownLatch(1);
   var second=pool.submit(()->{started.countDown();return family.issueInvite(parent).getId();});
   assertThat(started.await(10,TimeUnit.SECONDS)).isTrue();assertThatThrownBy(()->second.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
   release.countDown();var firstId=first.get(20,TimeUnit.SECONDS);var secondId=second.get(20,TimeUnit.SECONDS);
   assertThat(invites.findById(firstId)).isEmpty();
   assertThat(invites.findByGuardian(parent)).extracting(InviteCode::getId).containsExactly(secondId);
  }finally{
   release.countDown();pool.shutdown();if(!pool.awaitTermination(30,TimeUnit.SECONDS)){pool.shutdownNow();pool.awaitTermination(10,TimeUnit.SECONDS);}
   tx.executeWithoutResult(s->{invites.deleteAll(invites.findByGuardian(parent));invites.flush();members.delete(parent);});
  }
 }
 @Test void 한자녀가_서로다른초대를_동시에써도_첫보호자만_연동된다()throws Exception{
  var firstParent=create();var secondParent=create();var child=create();
  var firstInvite=family.issueInvite(firstParent);var secondInvite=family.issueInvite(secondParent);
  var pool=Executors.newFixedThreadPool(2);var held=new CountDownLatch(1);var release=new CountDownLatch(1);
  try{
   var first=pool.submit(()->tx.executeWithoutResult(s->{family.redeemInvite(child,firstInvite.getCode(),firstInvite.getId());held.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("검사 대기 실패");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}));
   assertThat(held.await(10,TimeUnit.SECONDS)).isTrue();var started=new CountDownLatch(1);
   var second=pool.submit(()->{started.countDown();try{family.redeemInvite(child,secondInvite.getCode(),secondInvite.getId());return "연동됨";}catch(IllegalStateException e){return e.getMessage();}});
   assertThat(started.await(10,TimeUnit.SECONDS)).isTrue();assertThatThrownBy(()->second.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
   release.countDown();first.get(20,TimeUnit.SECONDS);
   assertThat(second.get(20,TimeUnit.SECONDS)).isEqualTo("이미 보호자와 연동돼 있어요.");
   assertThat(links.findByChild(child).orElseThrow().getGuardian().getId()).isEqualTo(firstParent.getId());
   assertThat(invites.findUsable(secondInvite.getCode(),java.time.LocalDateTime.now())).hasSize(1);
  }finally{
   release.countDown();pool.shutdown();if(!pool.awaitTermination(30,TimeUnit.SECONDS)){pool.shutdownNow();pool.awaitTermination(10,TimeUnit.SECONDS);}
   tx.executeWithoutResult(s->{links.findByChild(child).ifPresent(links::delete);links.flush();invites.deleteAll(invites.findByGuardian(firstParent));invites.deleteAll(invites.findByGuardian(secondParent));invites.flush();members.deleteAll(List.of(child,firstParent,secondParent));});
  }
 }
 @Test void 두자녀가_같은초대를_사용해도_한명만_연동된다()throws Exception{
  var parent=create();var firstChild=create();var secondChild=create();var invite=family.issueInvite(parent);
  var pool=Executors.newFixedThreadPool(2);var held=new CountDownLatch(1);var release=new CountDownLatch(1);
  try {
   var first=pool.submit(()->tx.executeWithoutResult(s->{family.redeemInvite(firstChild,invite.getCode(),invite.getId());held.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("검사 대기 실패");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}));
   assertThat(held.await(10,TimeUnit.SECONDS)).isTrue();var started=new CountDownLatch(1);
   var second=pool.submit(()->{started.countDown();try{family.redeemInvite(secondChild,invite.getCode(),invite.getId());return true;}catch(IllegalArgumentException e){return false;}});
   assertThat(started.await(10,TimeUnit.SECONDS)).isTrue();assertThatThrownBy(()->second.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
   release.countDown();first.get(20,TimeUnit.SECONDS);assertThat(second.get(20,TimeUnit.SECONDS)).isFalse();
   assertThat(links.findByChild(firstChild)).isPresent();assertThat(links.findByChild(secondChild)).isEmpty();
   assertThat(invites.findUsable(invite.getCode(),java.time.LocalDateTime.now())).isEmpty();
  } finally {
   release.countDown();pool.shutdown();if(!pool.awaitTermination(30,TimeUnit.SECONDS)){pool.shutdownNow();pool.awaitTermination(10,TimeUnit.SECONDS);}
   tx.executeWithoutResult(s->{links.findByChild(firstChild).ifPresent(links::delete);links.findByChild(secondChild).ifPresent(links::delete);links.flush();invites.deleteAll(invites.findByGuardian(parent));invites.flush();members.deleteAll(List.of(firstChild,secondChild,parent));});
  }
 }
}
