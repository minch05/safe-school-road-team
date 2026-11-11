package com.safeschoolroad.facility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest
@Transactional
class DataQualityAuditTests {
    @Autowired DataQualityAudit audit;
    @Autowired JdbcTemplate jdbc;
    @Test void 날짜_누락과_공간_좌표_불일치를_집계하고_원본을_수정하지_않는다() {
        var before=audit.inspect();
        jdbc.update("""
            INSERT INTO facility(kind,name,lat,lng,location,source,source_id,base_date,district,imported_at)
            VALUES ('SCHOOL','검수',37.6,127.09,ST_GeomFromText('POINT(127.08 37.6)',4326,'axis-order=long-lat'),
                    'AUDIT_TEST','test',NULL,'',NOW())
            """);
        var result=audit.inspect();
        assertThat(result.coordinateMismatch()).isEqualTo(before.coordinateMismatch()+1);
        assertThat(result.unknownDistrict()).isEqualTo(before.unknownDistrict()+1);
        var count=jdbc.queryForObject("SELECT COUNT(*) FROM facility WHERE source='AUDIT_TEST' AND source_id='test' AND base_date IS NULL",Long.class);
        assertThat(count).isEqualTo(1L);
        assertThat(result.facilities()).anyMatch(row -> "SCHOOL".equals(row.get("kind")) && ((Number)row.get("missing_date")).longValue()>=1);
    }
}
