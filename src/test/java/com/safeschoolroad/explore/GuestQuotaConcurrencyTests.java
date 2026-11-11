package com.safeschoolroad.explore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest
class GuestQuotaConcurrencyTests {
    @Autowired JdbcTemplate jdbc;@Autowired PlatformTransactionManager manager;
    static final LocalDate DAY=LocalDate.of(2077,10,10);
    GuestQuota instance(int deviceLimit,int totalLimit){
        var q=new GuestQuota(jdbc);ReflectionTestUtils.setField(q,"perDevice",deviceLimit);ReflectionTestUtils.setField(q,"total",totalLimit);
        ReflectionTestUtils.setField(q,"clock",Clock.fixed(DAY.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant(),ZoneId.of("Asia/Seoul")));return q;
    }
    void run(boolean sameDevice)throws Exception{
        var instances=List.of(instance(sameDevice?3:20,sameDevice?100:5),instance(sameDevice?3:20,sameDevice?100:5));
        var pool=Executors.newFixedThreadPool(6);var gate=new CountDownLatch(1);var futures=new ArrayList<Future<Boolean>>();
        try {
            for(int i=0;i<12;i++){final int n=i;futures.add(pool.submit(()->{gate.await();return new TransactionTemplate(manager).execute(status->instances.get(n%2).tryUse(sameDevice?"quota-race":"quota-race-"+n));}));}
            gate.countDown();int accepted=0;for(var f:futures)if(f.get(20,TimeUnit.SECONDS))accepted++;
            int limit=sameDevice?3:5;assertThat(accepted).isEqualTo(limit);
            assertThat(jdbc.queryForObject("SELECT calls FROM api_usage WHERE day=? AND api='GUEST'",Integer.class,DAY).intValue()).isEqualTo(limit);
            assertThat(jdbc.queryForObject("SELECT SUM(calls) FROM guest_quota WHERE day=?",Integer.class,DAY).intValue()).isEqualTo(limit);
        } finally {
            gate.countDown();pool.shutdown();if(!pool.awaitTermination(30,TimeUnit.SECONDS)){pool.shutdownNow();pool.awaitTermination(10,TimeUnit.SECONDS);}
            jdbc.update("DELETE FROM guest_quota WHERE day=?",DAY);jdbc.update("DELETE FROM api_usage WHERE day=? AND api='GUEST'",DAY);
        }
    }
    @Test void 두인스턴스_동시요청도_기기당한도를_넘지않는다()throws Exception{run(true);}
    @Test void 서로다른기기_동시요청도_전체한도를_넘지않는다()throws Exception{run(false);}
    void cleanup(LocalDate day){jdbc.update("DELETE FROM guest_quota WHERE day=?",day);jdbc.update("DELETE FROM api_usage WHERE day=? AND api='GUEST'",day);}
    @Test void 전체카운터_갱신실패는_기기카운터도_되돌린다(){
        var broken=new JdbcTemplate(jdbc.getDataSource()) {
            @Override public int update(String sql,Object...args){
                if(sql.startsWith("UPDATE api_usage"))throw new org.springframework.dao.DataAccessResourceFailureException("합성 갱신 실패");
                return super.update(sql,args);
            }
        };
        var q=instance(3,100);ReflectionTestUtils.setField(q,"jdbc",broken);
        try {
            assertThatThrownBy(()->new TransactionTemplate(manager).execute(status->q.tryUse("quota-failure")))
                .isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM guest_quota WHERE day=?",Integer.class,DAY).intValue()).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM api_usage WHERE day=? AND api='GUEST'",Integer.class,DAY).intValue()).isZero();
        } finally {cleanup(DAY);}
    }
    @Test void 한국시간_날짜가바뀌면_새한도를_사용한다(){
        var q=instance(1,100);var tx=new TransactionTemplate(manager);var zone=ZoneId.of("Asia/Seoul");
        try {
            ReflectionTestUtils.setField(q,"clock",Clock.fixed(DAY.atTime(23,59,59).atZone(zone).toInstant(),zone));
            boolean first=tx.execute(status->q.tryUse("quota-midnight"));boolean exhausted=tx.execute(status->q.tryUse("quota-midnight"));
            assertThat(first).isTrue();assertThat(exhausted).isFalse();
            ReflectionTestUtils.setField(q,"clock",Clock.fixed(DAY.plusDays(1).atStartOfDay(zone).toInstant(),zone));
            assertThat(q.left("quota-midnight")).isEqualTo(1);
            boolean next=tx.execute(status->q.tryUse("quota-midnight"));assertThat(next).isTrue();
            for(var day:List.of(DAY,DAY.plusDays(1))) {
                assertThat(jdbc.queryForObject("SELECT calls FROM guest_quota WHERE day=? AND device='quota-midnight'",Integer.class,day).intValue()).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT calls FROM api_usage WHERE day=? AND api='GUEST'",Integer.class,day).intValue()).isEqualTo(1);
            }
        } finally {cleanup(DAY);cleanup(DAY.plusDays(1));}
    }
}
