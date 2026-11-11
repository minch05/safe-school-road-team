package com.safeschoolroad.facility.load;

import com.safeschoolroad.facility.FacilityKind;
import com.safeschoolroad.facility.SignalStatus;

import java.time.LocalDate;

// 원본 파일에서 읽어 DB에 넣기 직전의 시설 한 줄
public record FacilityRow(
        FacilityKind kind,
        String name,
        double lat,
        double lng,
        SignalStatus signalStatus, // 횡단보도가 아니면 null
        String district,
        String source,
        String sourceId,
        LocalDate baseDate) {
}
