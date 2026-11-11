package com.safeschoolroad.facility;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// 보호구역 대상 시설 좌표와 학교 좌표 교차 검사
class SchoolZoneCheckTests {

    // 서울개원초등학교 실제 위치 (학교 표준데이터 = VWorld 검색과 같음)
    private final Map<String, List<double[]>> schools = Map.of("개원초등학교", List.of(new double[]{37.48137, 127.05906}));

    @Test
    void 이름_앞의_서울과_빈칸은_무시한다() {
        assertThat(SchoolZoneCheck.elementaryKey("서울개원초등학교")).isEqualTo("개원초등학교");
        assertThat(SchoolZoneCheck.elementaryKey("개원 초등학교")).isEqualTo("개원초등학교");
        assertThat(SchoolZoneCheck.elementaryKey("송파초교병설유치원")).isNull();
        assertThat(SchoolZoneCheck.elementaryKey("초등학교")).isNull();
    }

    @Test
    void 학교에서_300m_넘게_떨어진_보호구역_좌표는_의심() {
        // 원본 보호구역 좌표 (3.7km 떨어짐)
        assertThat(SchoolZoneCheck.misplaced("개원초등학교", 37.47219, 127.0996, schools)).isTrue();
        // 정문 쪽 100m 안
        assertThat(SchoolZoneCheck.misplaced("개원초등학교", 37.4820, 127.0600, schools)).isFalse();
    }

    @Test
    void 비교할_학교가_없거나_초등학교가_아니면_그대로_둔다() {
        assertThat(SchoolZoneCheck.misplaced("중대부속초등학교", 37.5, 127.0, schools)).isFalse();
        assertThat(SchoolZoneCheck.misplaced("햇살어린이집", 37.5, 127.0, schools)).isFalse();
    }
    @Test void 서버_재시작없이_일분후_새_학교좌표를_사용하고_reload는_즉시_반영한다() throws Exception {
        var jdbc=org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var clock=new java.util.concurrent.atomic.AtomicLong();
        var schoolLat=new java.util.concurrent.atomic.AtomicReference<Double>(37.5);
        org.mockito.Mockito.doAnswer(inv->{
            org.springframework.jdbc.core.RowCallbackHandler handler=inv.getArgument(1);
            var rs=org.mockito.Mockito.mock(java.sql.ResultSet.class);
            org.mockito.Mockito.when(rs.getString(1)).thenReturn("서울테스트초등학교");
            org.mockito.Mockito.when(rs.getDouble(2)).thenReturn(schoolLat.get());
            org.mockito.Mockito.when(rs.getDouble(3)).thenReturn(127.0);
            handler.processRow(rs);return null;
        }).when(jdbc).query(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(org.springframework.jdbc.core.RowCallbackHandler.class));
        var check=new SchoolZoneCheck(jdbc,clock::get);
        var zone=new Facility(1,FacilityKind.SCHOOL_ZONE,"테스트초등학교",37.5,127.0,null,"test","test");
        assertThat(check.misplaced(zone)).isFalse();schoolLat.set(37.6);
        assertThat(check.misplaced(zone)).isFalse(); // 아직 유효한 한 번의 스냅샷
        clock.set(SchoolZoneCheck.CACHE_NANOS);
        assertThat(check.misplaced(zone)).isTrue();
        schoolLat.set(37.5);check.reload();assertThat(check.misplaced(zone)).isFalse();
        org.mockito.Mockito.verify(jdbc,org.mockito.Mockito.times(3)).query(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(org.springframework.jdbc.core.RowCallbackHandler.class));
    }
    @Test void 만료후_DB조회실패를_옛좌표로_숨기지_않는다(){
        var jdbc=org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var clock=new java.util.concurrent.atomic.AtomicLong();
        org.mockito.Mockito.doNothing().doThrow(new org.springframework.dao.DataAccessResourceFailureException("test"))
            .when(jdbc).query(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(org.springframework.jdbc.core.RowCallbackHandler.class));
        var check=new SchoolZoneCheck(jdbc,clock::get);
        var zone=new Facility(1,FacilityKind.SCHOOL_ZONE,"테스트초등학교",37.5,127.0,null,"test","test");
        assertThat(check.misplaced(zone)).isFalse();clock.set(SchoolZoneCheck.CACHE_NANOS);
        org.assertj.core.api.Assertions.assertThatThrownBy(()->check.misplaced(zone)).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
}
