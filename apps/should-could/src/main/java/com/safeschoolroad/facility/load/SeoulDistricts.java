package com.safeschoolroad.facility.load;

import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// 서울 자치구 25곳. 서울시 횡단보도 데이터는 구를 코드(GU_CD)로만 준다(HANDOFF 6장).
public final class SeoulDistricts {

    // 행정표준 시군구 코드 끝 3자리 → 구 이름
    private static final Map<String, String> BY_CODE = Map.ofEntries(
            Map.entry("110", "종로구"), Map.entry("140", "중구"), Map.entry("170", "용산구"),
            Map.entry("200", "성동구"), Map.entry("215", "광진구"), Map.entry("230", "동대문구"),
            Map.entry("260", "중랑구"), Map.entry("290", "성북구"), Map.entry("305", "강북구"),
            Map.entry("320", "도봉구"), Map.entry("350", "노원구"), Map.entry("380", "은평구"),
            Map.entry("410", "서대문구"), Map.entry("440", "마포구"), Map.entry("470", "양천구"),
            Map.entry("500", "강서구"), Map.entry("530", "구로구"), Map.entry("545", "금천구"),
            Map.entry("560", "영등포구"), Map.entry("590", "동작구"), Map.entry("620", "관악구"),
            Map.entry("650", "서초구"), Map.entry("680", "강남구"), Map.entry("710", "송파구"),
            Map.entry("740", "강동구"),
            // 서울시 횡단보도 데이터는 이 세 구에 옛 코드를 쓴다 (좌표 평균으로 확인, 2026-10-09)
            Map.entry("210", "광진구"), Map.entry("300", "강북구"), Map.entry("540", "금천구"));

    private static final Set<String> NAMES = Set.copyOf(BY_CODE.values());

    // "서울특별시 중랑구 ..." / "서울시 종로구 ..." / "서울특별시 종로구청"
    private static final Pattern IN_ADDRESS = Pattern.compile("서울\\S*\\s+(\\S+?구)(?:청|\\s|$)");

    private SeoulDistricts() {
    }

    public static String byCode(String code) {
        return code == null ? "" : BY_CODE.getOrDefault(code.strip(), "");
    }

    /** 주소 여러 개를 앞에서부터 보고 처음 찾은 구 이름. 못 찾으면 "" */
    public static String fromAddress(String... addresses) {
        for (String address : addresses) {
            if (address == null) {
                continue;
            }
            Matcher m = IN_ADDRESS.matcher(address);
            if (m.find() && NAMES.contains(m.group(1))) {
                return m.group(1);
            }
        }
        return "";
    }

    public static java.util.List<String> names() { return NAMES.stream().sorted().toList(); }

    public static boolean isDistrict(String name) {
        return NAMES.contains(name);
    }
}
