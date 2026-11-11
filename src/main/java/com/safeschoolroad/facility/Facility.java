package com.safeschoolroad.facility;

// DB에서 꺼낸 시설 하나. signalStatus 는 횡단보도만 있다.
// id 는 다시 적재하면 바뀌므로, 오래 가리킬 때는 source + sourceId 를 쓴다.
public record Facility(
        long id,
        FacilityKind kind,
        String name,
        double lat,
        double lng,
        SignalStatus signalStatus,
        String source,
        String sourceId, java.time.LocalDate baseDate) {
    public Facility(long id, FacilityKind kind, String name, double lat, double lng,
                    SignalStatus signalStatus, String source, String sourceId) {
        this(id,kind,name,lat,lng,signalStatus,source,sourceId,null);
    }
}
