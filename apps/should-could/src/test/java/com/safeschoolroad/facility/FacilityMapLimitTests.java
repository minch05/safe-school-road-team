package com.safeschoolroad.facility;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.stream.IntStream;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;

class FacilityMapLimitTests {
    @Test void 밀집한_종류가_희소한_시설을_표시한도에서_밀어내지_않는다() {
        var repository=mock(FacilityRepository.class);
        var risks=mock(RiskAreaRepository.class);
        var cctv=IntStream.range(0,251).mapToObj(i->new Facility(i,FacilityKind.CCTV,"CCTV",37.59,127.08,null,"TEST",""+i)).toList();
        var house=new Facility(900,FacilityKind.CHILD_SAFETY_HOUSE,"지킴이집",37.59,127.08,null,"TEST","house");
        when(repository.findInBox(37.58,127.07,37.60,127.09,251,FacilityKind.CCTV)).thenReturn(cctv);
        when(repository.findInBox(37.58,127.07,37.60,127.09,251,FacilityKind.CHILD_SAFETY_HOUSE)).thenReturn(List.of(house));
        var result=new FacilityApiController(repository,risks,mock(SchoolZoneCheck.class)).inBox(37.58,127.07,37.60,127.09,List.of(FacilityKind.CCTV,FacilityKind.CHILD_SAFETY_HOUSE),false);
        assertThat(result.get("truncated")).isEqualTo(true);
        assertThat((List<?>)result.get("facilities")).hasSize(251);
        assertThat(result.get("facilities").toString()).contains("CHILD_SAFETY_HOUSE");
    }
    @Test void 사고영역만_선택하면_모든_시설을_조회하지_않는다() {
        var repository=mock(FacilityRepository.class);
        var risks=mock(RiskAreaRepository.class);
        when(risks.findInBox(37.58,127.07,37.60,127.09)).thenReturn(List.of());
        var result=new FacilityApiController(repository,risks,mock(SchoolZoneCheck.class)).inBox(37.58,127.07,37.60,127.09,List.of(),true);
        assertThat((List<?>)result.get("facilities")).isEmpty();
        verifyNoInteractions(repository);
        verify(risks).findInBox(37.58,127.07,37.60,127.09);
    }
}
