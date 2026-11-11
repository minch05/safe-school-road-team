package com.safeschoolroad.facility;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;
import java.util.Map;

/** 공개 공공데이터만 제공. 회원 정보나 실시간 위치를 조회하지 않는다. */
@RestController
public class FacilityApiController {
    private static final int MAX = 500;
    private final FacilityRepository repository;
    private final RiskAreaRepository risks;
    private final SchoolZoneCheck zoneCheck;
    public FacilityApiController(FacilityRepository repository, RiskAreaRepository risks, SchoolZoneCheck zoneCheck) {
        this.zoneCheck=zoneCheck;
        this.repository=repository; this.risks=risks;
    }
    @GetMapping("/api/facilities/catalog")
    public Map<String,Object> catalog() {
        return Map.of("region","서울", "counts",repository.countByKind(),"coverage",repository.coverageSummary());
    }
    @GetMapping("/api/facilities")
    public Map<String,Object> inBox(@RequestParam double south, @RequestParam double west,
            @RequestParam double north, @RequestParam double east,
            @RequestParam(defaultValue="CROSSWALK") List<FacilityKind> kinds,
            @RequestParam(defaultValue="false") boolean accidents) {
        if (!Double.isFinite(south)||!Double.isFinite(west)||!Double.isFinite(north)||!Double.isFinite(east)
                ||south<37.4||north>37.73||west<126.75||east>127.2||north<=south||east<=west)
            throw new IllegalArgumentException("서울 안의 올바른 지도 범위를 선택해 주세요.");
        if(kinds.size()>FacilityKind.values().length) throw new IllegalArgumentException("시설 종류를 다시 선택해 주세요.");
        if (north-south>.03||east-west>.03) return Map.of("tooWide",true,"truncated",false,"facilities",List.of(),"areas",List.of());
        var selected=kinds.stream().distinct().toList();
        var rows=new java.util.ArrayList<Facility>();
        boolean truncated=false;
        int perKind=selected.isEmpty()?MAX:MAX/selected.size();
        for(var kind:selected) {
            var group=repository.findInBox(south,west,north,east,perKind+1,kind);
            // 대상 학교 위치와 어긋난 보호구역 좌표는 지도에 찍지 않는다 (SchoolZoneCheck)
            if(kind==FacilityKind.SCHOOL_ZONE) group=zoneCheck.keepTrusted(group);
            truncated|=group.size()>perKind;
            rows.addAll(group.stream().limit(perKind).toList());
        }
        var facilities=rows.stream().limit(MAX).map(f->Map.<String,Object>of(
                "id",f.id(),"kind",f.kind().name(),"kindLabel",f.kind().getLabel(),"name",f.name(),
                "lat",f.lat(),"lng",f.lng(),"source",f.source(),"baseDate",f.baseDate()==null?"":f.baseDate().toString(),
                "baseDateLabel","SEOUL_CROSSWALK".equals(f.source())?"원본 내려받은 날짜":"SMART_POLE_LIGHT".equals(f.source())?"스마트폴 현황 기준 월(말일 대표값)":"자료 기준일",
                "signal",f.signalStatus()==null?"":f.signalStatus().getLabel())).toList();
        return Map.of("tooWide",false,"truncated",truncated,"facilities",facilities,
                "areas",accidents?risks.findInBox(south,west,north,east):List.of());
    }
}
