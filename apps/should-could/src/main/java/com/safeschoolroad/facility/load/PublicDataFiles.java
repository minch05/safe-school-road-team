package com.safeschoolroad.facility.load;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safeschoolroad.facility.FacilityKind;
import com.safeschoolroad.facility.SignalStatus;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

// 내려받은 공공데이터 원본 파일(무제 폴더/)을 읽어 FacilityRow 로 바꾼다. 서울 것만 돌려준다.
// 규칙은 사전 시험 코드(experiments/facility-count/count.py)와 HANDOFF 5-3, 6장을 따른다.
public class PublicDataFiles {

    public static final String CROSSWALK_FILE = "서울시_횡단보도_trafficSafetyA004AInfo.json";
    public static final String PED_LIGHT_FILE = "서울시_보행등_위치정보_20260213.xlsx";
    public static final String SCHOOL_FILE = "전국초중등학교위치표준데이터.json";
    public static final String CCTV_FILE = "CCTV정보_서울특별시.csv";
    public static final String BELL_FILE = "안전비상벨위치정보.csv";
    public static final String SCHOOL_ZONE_FILE = "전국어린이보호구역표준데이터.json";

    // 보행등이 횡단보도에서 이 거리 안에 있으면 "신호 있음 추정" (HANDOFF 6장)
    static final double PED_LIGHT_METERS = 10;

    // 서울을 넉넉히 감싸는 사각형. 좌표가 잘못 적힌 줄을 거른다.
    private static final double MIN_LAT = 37.40, MAX_LAT = 37.73, MIN_LNG = 126.75, MAX_LNG = 127.20;

    private static final Charset CP949 = Charset.forName("MS949");
    private static final Pattern ROAD_NAME = Pattern.compile("([가-힣0-9]+(?:로|길))\\s*\\d");

    private static final TypeReference<Map<String, Object>> JSON_RECORD = new TypeReference<>() {
    };

    private final Path dir;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PublicDataFiles(Path dir) {
        this.dir = dir;
    }

    // ── 횡단보도: 서울시 교통안전시설물 (OA-15554) ─────────────────
    // 같은 관리번호의 변경 이력이 여러 줄이라 HSTRY_ID 가 가장 큰 줄만 쓴다. 좌표가 빈 줄은 뺀다.
    // 최신 줄의 상태(STTS)가 001 이 아니면 뺀다(사전 시험과 같은 기준).
    public List<FacilityRow> readCrosswalks(PedestrianLights pedLights) throws IOException {
        Map<String, Map<String, String>> latest = new HashMap<>();
        String[] downloaded = {""};
        forEachJsonRecord(CROSSWALK_FILE, downloaded, r -> {
            if (r.getOrDefault("CRSWK_MNG_NO1", "").isBlank()) {
                return;
            }
            String key = r.get("CRSWK_MNG_NO1");
            Map<String, String> prev = latest.get(key);
            if (prev == null || toLong(r.get("HSTRY_ID")) > toLong(prev.get("HSTRY_ID"))) {
                latest.put(key, r);
            }
        });
        LocalDate baseDate = toDate(downloaded[0]);

        List<FacilityRow> rows = new ArrayList<>();
        for (Map<String, String> r : latest.values()) {
            if (!"001".equals(r.get("STTS"))) {
                continue;
            }
            Double x = toDouble(r.get("XCRD")), y = toDouble(r.get("YCRD"));
            if (x == null || y == null || !Double.isFinite(x) || !Double.isFinite(y)) continue;
            double[] latLng = Tm5186.toWgs84(x, y);
            if (!inSeoul(latLng[0], latLng[1])) {
                continue;
            }
            SignalStatus signal = pedLights.anyWithin(latLng[0], latLng[1], PED_LIGHT_METERS)
                    ? SignalStatus.ESTIMATED : SignalStatus.UNKNOWN;
            // 횡단보도는 이름 없이 종류와 신호 상태만 (HANDOFF 5-3)
            rows.add(new FacilityRow(FacilityKind.CROSSWALK, "", latLng[0], latLng[1], signal,
                    SeoulDistricts.byCode(r.get("GU_CD")), "SEOUL_CROSSWALK", r.get("CRSWK_MNG_NO1"), baseDate));
        }
        return fillMissingDistricts(rows);
    }

    // 구 코드가 빈 횡단보도(약 0.7%)는 가장 가까운 횡단보도(300m 안)의 구를 빌려 쓴다
    private static List<FacilityRow> fillMissingDistricts(List<FacilityRow> rows) {
        double cell = 0.003; // 약 300m
        Map<Long, List<FacilityRow>> grid = new HashMap<>();
        for (FacilityRow r : rows) {
            if (!r.district().isEmpty()) {
                grid.computeIfAbsent(gridKey(r.lat(), r.lng(), cell, 0, 0), k -> new ArrayList<>()).add(r);
            }
        }
        List<FacilityRow> out = new ArrayList<>(rows.size());
        for (FacilityRow r : rows) {
            if (!r.district().isEmpty()) {
                out.add(r);
                continue;
            }
            String nearest = "";
            double best = 300;
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    for (FacilityRow k : grid.getOrDefault(gridKey(r.lat(), r.lng(), cell, dy, dx), List.of())) {
                        double d = Math.hypot((r.lat() - k.lat()) * 110540, (r.lng() - k.lng()) * 88200);
                        if (d < best) {
                            best = d;
                            nearest = k.district();
                        }
                    }
                }
            }
            out.add(new FacilityRow(r.kind(), r.name(), r.lat(), r.lng(), r.signalStatus(), nearest,
                    r.source(), r.sourceId(), r.baseDate()));
        }
        return out;
    }

    private static long gridKey(double lat, double lng, double cell, int dy, int dx) {
        return ((long) Math.floor(lat / cell) + dy) * 1_000_000L + ((long) Math.floor(lng / cell) + dx);
    }

    // ── 보행등: 서울시 보행등 위치 (OA-22355, xlsx) ────────────────
    // 첫 시트의 C열(X), D열(Y)이 EPSG:5186 좌표다. 엑셀 라이브러리 없이 xlsx 안의 XML을 직접 읽는다.
    public PedestrianLights readPedestrianLights() throws IOException {
        PedestrianLights lights = new PedestrianLights();
        Path file = require(PED_LIGHT_FILE);
        try (ZipFile zip = new ZipFile(file.toFile())) {
            ZipEntry sheet = zip.getEntry("xl/worksheets/sheet1.xml");
            try (InputStream in = zip.getInputStream(sheet)) {
                XMLStreamReader xml = XMLInputFactory.newFactory().createXMLStreamReader(in);
                int rowNum = 0;
                String col = null;
                Double x = null, y = null;
                while (xml.hasNext()) {
                    int event = xml.next();
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        switch (xml.getLocalName()) {
                            case "row" -> {
                                rowNum = Integer.parseInt(xml.getAttributeValue(null, "r"));
                                x = null;
                                y = null;
                            }
                            case "c" -> col = xml.getAttributeValue(null, "r").replaceAll("\\d", "");
                            case "v" -> {
                                String text = xml.getElementText();
                                if (rowNum > 1 && "C".equals(col)) {
                                    x = toDouble(text);
                                } else if (rowNum > 1 && "D".equals(col)) {
                                    y = toDouble(text);
                                }
                            }
                            default -> {
                            }
                        }
                    } else if (event == XMLStreamConstants.END_ELEMENT && "row".equals(xml.getLocalName())
                            && x != null && y != null) {
                        double[] latLng = Tm5186.toWgs84(x, y);
                        lights.add(latLng[0], latLng[1]);
                    }
                }
            } catch (XMLStreamException e) {
                throw new IOException("보행등 파일을 읽지 못했습니다: " + file, e);
            }
        }
        return lights;
    }

    // ── 학교: 전국초중등학교위치표준데이터 (서울만, 운영 중인 곳) ──────────
    public List<FacilityRow> readSchools() throws IOException {
        List<FacilityRow> rows = new ArrayList<>();
        forEachJsonRecord(SCHOOL_FILE, null, r -> {
            String address = r.getOrDefault("소재지도로명주소", "");
            if (!address.startsWith("서울") || !"운영".equals(r.get("운영상태"))) {
                return;
            }
            addIfValid(rows, FacilityKind.SCHOOL, r.get("학교명"), r.get("위도"), r.get("경도"),
                    SeoulDistricts.fromAddress(address, r.get("소재지지번주소")),
                    "SCHOOL_STD", r.get("학교ID"), r.get("데이터기준일자"));
        });
        return rows;
    }

    // ── CCTV: CCTV정보_서울특별시.csv ──────────────────────────
    // 이름은 도로명 주소에서 도로 이름만 뽑는다 (HANDOFF 5-3)
    public List<FacilityRow> readCctvs() throws IOException {
        List<FacilityRow> rows = new ArrayList<>();
        forEachCsvRow(CCTV_FILE, r -> {
            String road = r.getOrDefault("소재지도로명주소", "");
            addIfValid(rows, FacilityKind.CCTV, roadName(road), r.get("WGS84위도"), r.get("WGS84경도"),
                    SeoulDistricts.fromAddress(road, r.get("소재지지번주소"), r.get("관리기관명")),
                    "CCTV_STD", r.get("관리번호"), r.get("데이터기준일자"));
        });
        return rows;
    }

    // ── 안전비상벨: 안전비상벨위치정보.csv (전국 파일에서 서울만) ──────────
    // 사람이 적은 "설치위치"는 가게 이름이 섞여 있어 쓰지 않는다. 이름 = 설치장소유형 · 도로명 (HANDOFF 5-3)
    public List<FacilityRow> readEmergencyBells() throws IOException {
        List<FacilityRow> rows = new ArrayList<>();
        forEachCsvRow(BELL_FILE, r -> {
            String road = r.getOrDefault("소재지도로명주소", "");
            String lot = r.getOrDefault("소재지지번주소", "");
            String agency = r.getOrDefault("관리기관명", "");
            if (!(road + lot + agency).contains("서울")) {
                return;
            }
            String name = joinNonBlank(" · ", r.get("설치장소유형"), roadName(road));
            addIfValid(rows, FacilityKind.EMERGENCY_BELL, name, r.get("WGS84위도"), r.get("WGS84경도"),
                    SeoulDistricts.fromAddress(road, lot, agency),
                    "BELL_STD", r.get("관리번호"), r.get("데이터기준일자"));
        });
        return rows;
    }

    // ── 어린이보호구역: 전국어린이보호구역표준데이터 (서울만) ──────────────
    // 구역의 모양은 없고 대상 시설(학교·유치원 등)의 위치만 있다. 원본에 관리번호가 없어 이름+좌표로 만든다.
    public List<FacilityRow> readSchoolZones() throws IOException {
        List<FacilityRow> rows = new ArrayList<>();
        forEachJsonRecord(SCHOOL_ZONE_FILE, null, r -> {
            String road = r.getOrDefault("소재지도로명주소", "");
            String lot = r.getOrDefault("소재지지번주소", "");
            if (!road.startsWith("서울") && !lot.startsWith("서울")) {
                return;
            }
            String name = r.getOrDefault("대상시설명", "");
            String id = Integer.toHexString((name + "|" + r.get("위도") + "|" + r.get("경도")).hashCode());
            addIfValid(rows, FacilityKind.SCHOOL_ZONE, name, r.get("위도"), r.get("경도"),
                    SeoulDistricts.fromAddress(road, lot), "SCHOOL_ZONE_STD", id, r.get("데이터기준일자"));
        });
        return rows;
    }

    // 추가 데이터는 별도로 적재한다. 잘린 파일·없는 파일로 기존 데이터를 지우지 않도록 한다.
    public static final String SECURITY_LIGHT_FILE = "전국보안등정보표준데이터.json";
    public static final String SAFETY_HOUSE_FILE = "아동안전지킴이집.csv";

    public List<FacilityRow> readSecurityLights() throws IOException {
        List<FacilityRow> rows = new ArrayList<>();
        forEachJsonRecord(SECURITY_LIGHT_FILE, null, r -> {
            String road = r.getOrDefault("소재지도로명주소", "");
            String lot = r.getOrDefault("소재지지번주소", "");
            if (!(road + lot + r.getOrDefault("관리기관명", "")).contains("서울")) return;
            // 사람이 적은 위치명에는 가게 이름이 있어 도로명만 저장한다.
            addIfValid(rows, FacilityKind.SECURITY_LIGHT, roadName(road), r.get("위도"), r.get("경도"),
                    SeoulDistricts.fromAddress(road, lot, r.get("관리기관명")), "SECURITY_LIGHT_STD",
                    stableId(r.get("보안등위치명"), r.get("위도"), r.get("경도")), r.get("데이터기준일자"));
        });
        return rows;
    }

    // 공식 공개 데이터에서 내려받아 정규화한 CSV. 원본 업체명은 저장·AI 전달하지 않는다.
    public List<FacilityRow> readChildSafetyHouses() throws IOException {
        List<FacilityRow> rows = new ArrayList<>();
        Path file = require(SAFETY_HOUSE_FILE);
        Charset charset = CP949;
        // 첫 줄로 UTF-8 또는 CP949를 구분한다 (CSV 헤더는 '위도'를 포함해야 한다).
        try (InputStream in = Files.newInputStream(file)) {
            byte[] head = in.readNBytes(8192);
            int end = 0;
            while (end < head.length && head[end] != '\n') end++;
            if (new String(head, 0, end, StandardCharsets.UTF_8).contains("위도")) charset = StandardCharsets.UTF_8;
        }
        try (Reader reader = new InputStreamReader(Files.newInputStream(file), charset)) {
            SimpleCsv.forEachRow(reader, r -> {
                String road = r.getOrDefault("소재지도로명주소", "");
                String lot = r.getOrDefault("소재지지번주소", "");
                if (!(road + lot).contains("서울")) return;
                addIfValid(rows, FacilityKind.CHILD_SAFETY_HOUSE, roadName(road), r.get("위도"), r.get("경도"),
                        SeoulDistricts.fromAddress(road, lot), "CHILD_SAFETY_HOUSE",
                        stableId(r.get("시설번호"), r.get("위도"), r.get("경도")), r.get("데이터기준일자"));
            });
        }
        return rows;
    }

    private static String stableId(String... parts) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(String.join("|", java.util.Arrays.stream(parts).map(s -> s == null ? "" : s).toList())
                            .getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest).substring(0, 32);
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    // ── 공통 ───────────────────────────────────────────

    /** "서울특별시 중랑구 면목로 434 (면목동)" → "면목로". 없으면 "" */
    static String roadName(String address) {
        if (address == null) {
            return "";
        }
        Matcher m = ROAD_NAME.matcher(address);
        return m.find() ? m.group(1) : "";
    }

    private void addIfValid(List<FacilityRow> rows, FacilityKind kind, String name, String lat, String lng,
                            String district, String source, String sourceId, String baseDate) {
        Double la = toDouble(lat);
        Double ln = toDouble(lng);
        if (la == null || ln == null || !inSeoul(la, ln) || sourceId == null || sourceId.isBlank()) {
            return;
        }
        rows.add(new FacilityRow(kind, truncate(name == null ? "" : name.strip(), 100), la, ln, null,
                district, source, sourceId.strip(), toDate(baseDate)));
    }

    private static boolean inSeoul(double lat, double lng) {
        return lat >= MIN_LAT && lat <= MAX_LAT && lng >= MIN_LNG && lng <= MAX_LNG;
    }

    // {"...": ..., "records": [ {..}, {..} ]} 모양의 큰 JSON을 한 줄씩 읽는다(파일 전체를 메모리에 올리지 않음).
    // downloaded 를 넘기면 맨 위 "downloaded" 값을 [0]에 담아 준다.
    private void forEachJsonRecord(String fileName, String[] downloaded, Consumer<Map<String, String>> handler)
            throws IOException {
        Path file = require(fileName);
        try (JsonParser p = new JsonFactory().setCodec(objectMapper).createParser(file.toFile())) {
            if (p.nextToken() != JsonToken.START_OBJECT) {
                throw new IOException("JSON 모양이 다릅니다: " + file);
            }
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                String field = p.currentName();
                JsonToken value = p.nextToken();
                if ("records".equals(field) && value == JsonToken.START_ARRAY) {
                    while (p.nextToken() == JsonToken.START_OBJECT) {
                        Map<String, Object> raw = p.readValueAs(JSON_RECORD);
                        Map<String, String> record = new HashMap<>();
                        raw.forEach((k, v) -> record.put(k, v == null ? "" : String.valueOf(v)));
                        handler.accept(record);
                    }
                } else if ("downloaded".equals(field) && downloaded != null) {
                    downloaded[0] = p.getValueAsString("");
                } else {
                    p.skipChildren();
                }
            }
        }
    }

    private void forEachCsvRow(String fileName, Consumer<Map<String, String>> handler) throws IOException {
        // InputStreamReader 는 깨진 글자를 만나면 멈추지 않고 대체 문자로 바꾼다 (count.py 의 errors='replace')
        try (Reader in = new InputStreamReader(Files.newInputStream(require(fileName)), CP949)) {
            SimpleCsv.forEachRow(in, handler);
        }
    }

    private Path require(String fileName) throws IOException {
        Path file = dir.resolve(fileName);
        if (!Files.isRegularFile(file)) {
            throw new NoSuchFileException(file.toAbsolutePath().toString(), null, "공공데이터 원본 파일이 없습니다");
        }
        return file;
    }

    private static Double toDouble(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(s.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static long toLong(String s) {
        try {
            return s == null || s.isBlank() ? 0 : Long.parseLong(s.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static LocalDate toDate(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(s.strip().substring(0, Math.min(10, s.strip().length())));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String joinNonBlank(String sep, String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part != null && !part.isBlank()) {
                if (!sb.isEmpty()) {
                    sb.append(sep);
                }
                sb.append(part.strip());
            }
        }
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
