package com.safeschoolroad.facility;

import com.safeschoolroad.facility.load.SeoulDistricts;

// 적재 범위. "서울"(전체) 또는 자치구 이름 하나 (HANDOFF 6장: 지역은 값 하나로 바꿀 수 있게)
public final class FacilityRegion {

    public static final String ALL_SEOUL = "서울";

    private FacilityRegion() {
    }

    public static boolean isValid(String region) {
        return ALL_SEOUL.equals(region) || SeoulDistricts.isDistrict(region);
    }

    public static boolean includes(String region, String district) {
        return ALL_SEOUL.equals(region) || region.equals(district);
    }
}
