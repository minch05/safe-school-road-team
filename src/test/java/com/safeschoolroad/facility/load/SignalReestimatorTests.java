package com.safeschoolroad.facility.load;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// 저장용 신호 다시 계산이 경로 분석과 같은 규칙인지 (보행등은 15m 안 가장 가까운 횡단보도 하나에만)
class SignalReestimatorTests {

    private static final double LAT = 37.5, LNG = 127.0, M_LNG = 1 / (111320 * Math.cos(Math.toRadians(37.5)));

    private static SignalReestimator.Point at(long id, double eastM) {
        return new SignalReestimator.Point(id, LAT, LNG + eastM * M_LNG);
    }

    @Test
    void 보행등은_가장_가까운_횡단보도에만_연결() {
        var crosswalks = List.of(at(1, 0), at(2, 6));
        var signals = List.of(at(100, 5)); // 1번과 5m, 2번과 1m
        assertThat(SignalReestimator.linked(crosswalks, signals)).containsExactly(2L);
    }

    @Test
    void 연결_거리_15m를_넘으면_연결하지_않는다() {
        assertThat(SignalReestimator.linked(List.of(at(1, 0)), List.of(at(100, 16)))).isEmpty();
        assertThat(SignalReestimator.linked(List.of(at(1, 0)), List.of(at(100, 14)))).containsExactly(1L);
    }
    @Test void 같은거리의_연결은_입력순서에_영향받지_않는다(){
        var lights=List.of(at(100,5));
        assertThat(SignalReestimator.linked(List.of(at(2,0),at(1,0)),lights)).containsExactly(1L);
        assertThat(SignalReestimator.linked(List.of(at(1,0),at(2,0)),lights)).containsExactly(1L);
    }
    @Test void 공간색인_계산은_경로의_신호규칙과_같다(){
        var random=new java.util.Random(20261010);
        var crossings=new java.util.ArrayList<SignalReestimator.Point>();
        var lights=new java.util.ArrayList<SignalReestimator.Point>();
        for(int i=1;i<=200;i++)crossings.add(new SignalReestimator.Point(i,37.5+random.nextDouble()*.004,127+random.nextDouble()*.004));
        for(int i=1;i<=120;i++)lights.add(new SignalReestimator.Point(i+1000,37.5+random.nextDouble()*.004,127+random.nextDouble()*.004));
        java.util.function.Function<SignalReestimator.Point,com.safeschoolroad.facility.Facility> crossing=p->new com.safeschoolroad.facility.Facility(p.id(),com.safeschoolroad.facility.FacilityKind.CROSSWALK,"",p.lat(),p.lng(),com.safeschoolroad.facility.SignalStatus.UNKNOWN,"test","test");
        var expected=com.safeschoolroad.route.analysis.SignalLinks.linkedCrosswalks(crossings.stream().map(crossing).toList(),lights.stream().map(crossing).toList());
        assertThat(SignalReestimator.linked(crossings,lights)).isEqualTo(expected);
    }
    @Test void 확정신호도_최근접_선정에_포함하고_확정값은_변경하지_않는다(){
        var jdbc=org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var tx=org.mockito.Mockito.mock(org.springframework.transaction.support.TransactionTemplate.class);
        var active=new java.util.concurrent.atomic.AtomicBoolean();
        org.mockito.Mockito.when(jdbc.query(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<SignalReestimator.Point>>any()))
            .thenAnswer(inv->{assertThat(active.get()).as("자료 조회도 저장 트랜잭션 안에서 실행").isTrue();String sql=inv.getArgument(0);assertThat(sql).doesNotContain("signal_status");return sql.contains("PEDESTRIAN_SIGNAL")?List.of(at(100,1)):List.of(at(1,0),at(2,8));});
        org.mockito.Mockito.doAnswer(inv->{org.springframework.transaction.support.TransactionCallback<Integer> callback=inv.getArgument(0);active.set(true);try{return callback.doInTransaction(org.mockito.Mockito.mock(org.springframework.transaction.TransactionStatus.class));}finally{active.set(false);}})
            .when(tx).execute(org.mockito.ArgumentMatchers.any());
        var calculator=new SignalReestimator(jdbc,tx,org.mockito.Mockito.mock(org.springframework.context.ConfigurableApplicationContext.class));
        assertThat(calculator.refresh()).isEqualTo(1);
        org.mockito.Mockito.verify(jdbc).batchUpdate(org.mockito.ArgumentMatchers.contains("signal_status <> 'YES'"),org.mockito.ArgumentMatchers.<java.util.List<Object[]>>any());
    }
}
