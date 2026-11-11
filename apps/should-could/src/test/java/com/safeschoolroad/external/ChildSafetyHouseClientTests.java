package com.safeschoolroad.external;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.safeschoolroad.facility.FacilityKind;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ChildSafetyHouseClientTests {
    @Test void 웹메르카토르_좌표를_변환하고_업체명과_전화번호를_저장하지_않는다() throws Exception {
        var item = new ObjectMapper().readTree("""
                {"objt_id":"123", "fclty_cd":"504040", "ctprvn_cd":"11",
                 "fclty_nm":"가게이름", "telno":"비밀전화", "rn_adres":"서울특별시 중랑구 면목로 434", "data_yr":"2025"}
                """);
        ((com.fasterxml.jackson.databind.node.ObjectNode)item).put("x", Math.toRadians(127.08)*6378137);
        ((com.fasterxml.jackson.databind.node.ObjectNode)item).put("y", Math.log(Math.tan(Math.PI/4+Math.toRadians(37.59)/2))*6378137);
        var row = ChildSafetyHouseClient.toRow(item);
        assertThat(row.kind()).isEqualTo(FacilityKind.CHILD_SAFETY_HOUSE);
        assertThat(row.lat()).isCloseTo(37.59, within(.000001));
        assertThat(row.lng()).isCloseTo(127.08, within(.000001));
        assertThat(row.name()).isEqualTo("면목로");
        assertThat(row.district()).isEqualTo("중랑구");
    }

    @Test void 다른_시설과_좌표_없는_시설은_제외한다() throws Exception {
        var mapper = new ObjectMapper();
        assertThat(ChildSafetyHouseClient.toRow(mapper.readTree("{\"fclty_cd\":\"513081\"}"))).isNull();
        assertThat(ChildSafetyHouseClient.toRow(mapper.readTree("{\"fclty_cd\":\"504040\",\"ctprvn_cd\":\"11\",\"objt_id\":\"1\"}"))).isNull();
    }
}
