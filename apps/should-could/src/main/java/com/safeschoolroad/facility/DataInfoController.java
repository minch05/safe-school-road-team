package com.safeschoolroad.facility;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
/** 공개 자료의 범위/의미를 설명. 개인정보와 원본 경로를 조회하지 않는다. */
@Controller
public class DataInfoController {
 private final FacilityRepository repository;
 private final SchoolZoneCheck zoneCheck;
 public DataInfoController(FacilityRepository repository,SchoolZoneCheck zoneCheck){this.repository=repository;this.zoneCheck=zoneCheck;}
 @GetMapping("/data-info") public String info(Model model){
  var coverage=repository.coverageSummary();var counts=new LinkedHashMap<String,Long>();
  for(var row:repository.countByKind())counts.merge(row.get("kind").toString(),((Number)row.get("n")).longValue(),Long::sum);
  var notes=Map.of(
   "CROSSWALK","TMAP의 건넘 위치를 등록 횡단보도와 대조해요. 신호는 보행등마다 15m 안에서 가장 가까운 횡단보도 하나에만 연결해 추정해요. 정답 자료가 있는 동작구 1,057곳으로 맞춰 보니 '신호 있음 추정'의 86%가 실제로 신호가 있었고, 실제 신호의 40%를 찾아냈어요. 그래서 안내문은 신호를 가정하지 않고 늘 멈춰서 확인하라고 해요. 미확인은 신호 없음이 아니에요. 날짜는 원본 내려받은 날짜예요.",
   "PEDESTRIAN_SIGNAL","보행등 설치 위치예요. 어느 건널목의 신호인지, 현재 켜지는지는 확인되지 않았어요. 가까이 있다는 이유만으로 신호를 확정하지 않아요.",
   "SCHOOL_ZONE","대상 시설의 300m 참고 반경을 표시해요. 실제 보호구역 도로 경계가 아니며, 많이 지나간다고 더 안전하다고 판단하지 않아요. 대상 초등학교의 학교 위치와 300m 넘게 어긋난 보호구역 좌표 "+zoneCheck.misplacedCount()+"곳은 위치가 잘못 적힌 것으로 보고 지도와 경로 집계에서 뺐어요(원본은 그대로 보관).",
   "SECURITY_LIGHT","구별 공식 파일을 검토해 유효 좌표와 최근 2년 안 기준일을 갖춘 단일 설치 기록만 등록해요(20개 구, 성동구는 쓸 수 있는 행이 8개뿐). 한 좌표에 10개 넘게 몰린 행은 실제 설치 위치가 아닌 대표점으로 보고 뺐어요(동대문·서대문 2026-10 추가분부터). 용산·강남·마포구 파일에는 좌표가 없어서, 송파(2022년)·도봉구(2018년) 파일은 기준일이 오래돼서 넣지 않았고, 서울시 2025년 12월 스마트폴 현황에서 서대문구 26곳·강남구 4곳을 별도로 보완해요. 이는 스마트폴에 포함된 보안등 일부이며, 구 전체 보안등 목록은 아니에요. 이 구들에서 보안등이 안 보이는 것은 없다는 뜻이 아니에요. 설치 위치와 현재 밝기·작동 상태는 달라요.",
   "CCTV","설치 위치를 표시해요. 최신 원본 접근에 제한이 있어 확보한 자료를 사용해요. 작동 상태와 사각지대는 확인되지 않았어요.",
   "EMERGENCY_BELL","설치 위치를 표시해요. 최신 원본 접근에 제한이 있어 확보한 자료를 사용해요. 종로구는 구청 파일(2023년 점검 정상분 713개)을 더했어요. 노원·서초구는 공개 파일을 찾지 못해 거의 비어 있어요. 작동과 연결 상태는 확인되지 않았어요.",
   "SCHOOL","학교의 대표 위치예요. 실제로 들어갈 정문과 다를 수 있으니 검색 결과와 지도에서 출입구를 확인해 주세요.",
   "CHILD_SAFETY_HOUSE","등록된 아동안전지킴이집 위치예요. 현재 영업·운영 시간은 확인되지 않았어요.");
  var uses=Map.of(
   "CROSSWALK","건넘·신호 추정 비교, 지도 표시, 핵심 안내 지점",
   "PEDESTRIAN_SIGNAL","횡단보도 신호 추정 보조·지도 표시. 신호등 개수 자체로 추천하지 않음",
   "SCHOOL_ZONE","경로 주변 시설 집계·300m 참고 표시. 추천 순위에 가산하지 않음",
   "SECURITY_LIGHT","경로 주변·지도 표시. 추천 순위와 핵심 안내 지점에는 미반영",
   "CCTV","경로 주변 시설 수·지도 표시·핵심 안내 지점. 추천 순위에 가산하지 않음",
   "EMERGENCY_BELL","경로 주변 시설 수·지도 표시·핵심 안내 지점. 추천 순위에 가산하지 않음",
   "SCHOOL","학교 대표 위치 표시·핵심 안내 지점. 추천 순위에 가산하지 않음",
   "CHILD_SAFETY_HOUSE","지도 표시·핵심 안내 지점. CCTV·비상벨 시설 수 및 추천 순위에는 미반영");
  model.addAttribute("sources",List.of(FacilityKind.values()).stream().filter(kind -> kind != FacilityKind.WALK_JUNCTION).map(kind->{
   Map<String,Object> row=new LinkedHashMap<>();row.put("name",kind.getLabel());row.put("count",counts.getOrDefault(kind.name(),0L));row.put("source",switch(kind.name()){case "CROSSWALK","PEDESTRIAN_SIGNAL" -> "서울시 공공데이터";case "SCHOOL" -> "공공데이터포털 학교 표준자료";case "CHILD_SAFETY_HOUSE" -> "경찰청 아동안전지킴이집 자료";default -> "공공데이터포털·지자체 등록 자료";});row.put("usage",uses.get(kind.name()));row.put("note",notes.getOrDefault(kind.name(),"등록 자료의 설치 위치예요."));
   var c=coverage.stream().filter(v->kind.name().equals(v.get("kind"))).findFirst().orElse(Map.of());
   var registered=java.util.Arrays.stream(java.util.Objects.toString(c.get("districts"),"").split(",")).map(String::strip).filter(com.safeschoolroad.facility.load.SeoulDistricts::isDistrict).collect(java.util.stream.Collectors.toSet());
   var missing=com.safeschoolroad.facility.load.SeoulDistricts.names().stream().filter(d->!registered.contains(d)).toList();
   row.put("districtCount",registered.size());row.put("missingDistricts",missing.isEmpty()?"모든 구에 등록 자료가 있어요. 전수 수집을 뜻하지 않아요.":String.join(" · ",missing));
   row.put("districts",registered.isEmpty()?"확보 지역 미확인":registered.stream().sorted().collect(java.util.stream.Collectors.joining(" · ")));row.put("date",c.get("newestDate")==null?"미확인":c.get("newestDate"));row.put("missingDates",c.getOrDefault("missingDateCount",0));return row;
  }).toList());return "data-info";
 }
}
