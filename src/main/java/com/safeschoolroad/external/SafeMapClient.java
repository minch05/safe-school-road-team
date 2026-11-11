package com.safeschoolroad.external;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.Semaphore;

// 공식 WMS와 범례를 제공한다. 원본 이미지는 변경하지 않는다.
// 실시간 위치가 아니라 사용자가 선택한 후보 경로의 범위만 요청한다. 인증키는 서버 안에만 둔다.
@Component
public class SafeMapClient {
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private final RestClient http;
    private final String key;
    private final Semaphore requestSlot = new Semaphore(1);
    private volatile long nextRequestAt;
    private volatile int[] palette;
    private volatile long paletteUntil;
    private volatile MapImage cachedImage;
    private record MapImage(String box, byte[] bytes, long until) {}

    @org.springframework.beans.factory.annotation.Autowired
    public SafeMapClient(@Value("${SAFEMAP_API_KEY:}") String key) {
        this(Http.client(Duration.ofSeconds(12)), key);
    }

    SafeMapClient(RestClient http, String key) {
        this.http = http;
        this.key = key;
    }

    public boolean configured() { return !key.isBlank(); }

    public byte[] crimeMap(double south, double west, double north, double east) {
        validateBounds(south, west, north, east);
        String cacheBox = String.format(Locale.ROOT, "%f,%f,%f,%f", west, south, east, north);
        MapImage cached = cachedImage;
        if (cached != null && cached.box().equals(cacheBox) && cached.until()>System.currentTimeMillis()) return cached.bytes().clone();
        if (!configured()) throw new Unavailable("범죄주의구간은 아직 연결되지 않아 미확인이에요.");
        if (!requestSlot.tryAcquire()) throw new Unavailable("지도를 불러오는 중이에요. 잠시 뒤 다시 확인해 주세요.");
        try {
            if (System.currentTimeMillis() < nextRequestAt) {
                throw new Unavailable("범죄 지도를 잠시 불러올 수 없어요. 미확인으로 표시해요.");
            }
            nextRequestAt = System.currentTimeMillis() + 1000;
            String bbox = String.format(Locale.ROOT, "%f,%f,%f,%f", west, south, east, north);
            URI uri = URI.create("https://www.safemap.go.kr/openapi2/IF_0087_WMS?serviceKey=" + enc(key)
                    + "&srs=EPSG%3A4326&bbox=" + enc(bbox)
                    + "&format=image%2Fpng&width=512&height=512&transparent=TRUE"
                    + "&layers=A2SM_CRMNLHSPOT_TOT&styles=A2SM_CrmnlHspot_Tot_Tot");
            try {
                return http.get().uri(uri).exchange((request, response) -> {
                    if (!response.getStatusCode().is2xxSuccessful()) throw new Unavailable("범죄 지도 응답을 받지 못했어요. 미확인으로 표시해요.");
                    byte[] bytes = response.getBody().readNBytes(MAX_BYTES + 1);
                    byte[] checked = checkedImage(bytes);
                    cachedImage = new MapImage(cacheBox, checked, System.currentTimeMillis()+300_000);
                    return checked;
                });
            } catch (Exception e) {
                nextRequestAt = System.currentTimeMillis() + 30_000;
                // 외부 예외에 인증키가 포함된 URL이 있을 수 있어 원문·원인을 화면이나 로그로 넘기지 않는다.
                throw new Unavailable("범죄 지도 제공기관의 응답을 확인하지 못했어요. 미확인으로 표시해요.");
            }
        } finally {
            requestSlot.release();
        }
    }

    /** 공개 범례에서 순번·등급·색을 검증한다. 캐시는 공공 지도만, 경로선은 저장하지 않는다. */
    public synchronized int[] crimePalette() {
        if (palette != null && paletteUntil > System.currentTimeMillis()) return palette.clone();
        if (!configured()) throw new Unavailable("범죄주의구간 범례가 미확인이에요.");
        try {
            URI uri = URI.create("https://www.safemap.go.kr/openapi2/lgdInfo?serviceKey="+enc(key)+"&intId=IF_0087&layer=A2SM_CRMNLHSPOT_TOT&style=A2SM_CrmnlHspot_Tot_Tot");
            byte[] body = http.get().uri(uri).exchange((req,res) -> {
                if(!res.getStatusCode().is2xxSuccessful()) throw new Unavailable("범례 응답 오류");
                return res.getBody().readNBytes(65_537);
            });
            if (body.length>65_536) throw new Unavailable("범례 크기 오류");
            var root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
            if (!"00".equals(root.path("header").path("resultCode").asText())) throw new Unavailable("범례 오류");
            var items = root.path("body").path("items").path("item");
            int[] colors = new int[10]; boolean[] seen = new boolean[10];
            if (!items.isArray() || items.size()!=10) throw new Unavailable("범례 항목 오류");
            for (var item:items) {
                int grade=item.path("LGD_NO").asInt(); String color=item.path("IMAGE").asText();
                if (grade<1 || grade>10 || seen[grade-1] || !color.matches("#[0-9a-fA-F]{6}")
                        || !"A2SM_CrmnlHspot_Tot_Tot".equals(item.path("STYLE").asText())
                        || !(grade+"등급").equals(item.path("LGD_NM").asText())) throw new Unavailable("범례 형식 오류");
                seen[grade-1]=true; colors[grade-1]=Integer.parseInt(color.substring(1),16);
            }
            palette=colors; paletteUntil=System.currentTimeMillis()+3_600_000;
            return colors.clone();
        } catch (Exception e) { throw new Unavailable("범죄주의구간 범례를 확인하지 못했어요."); }
    }

    static byte[] checkedImage(byte[] bytes) throws java.io.IOException {
        if (bytes == null || bytes.length > MAX_BYTES || bytes.length < 8
                || bytes[0] != (byte) 137 || bytes[1] != 80 || bytes[2] != 78 || bytes[3] != 71) {
            throw new Unavailable("이미지 응답이 아니에요.");
        }
        // PNG 헤더의 크기를 먼저 확인해 과도한 메모리를 쓰는 파일을 디코딩하지 않는다.
        if (bytes.length < 24) throw new Unavailable("이미지가 잘렸어요.");
        var header = java.nio.ByteBuffer.wrap(bytes);
        int width = header.getInt(16), height = header.getInt(20);
        if (width != 512 || height != 512 || ImageIO.read(new ByteArrayInputStream(bytes)) == null) {
            throw new Unavailable("이미지 크기나 내용이 올바르지 않아요.");
        }
        return bytes;
    }

    static void validateBounds(double south, double west, double north, double east) {
        if (!Double.isFinite(south) || !Double.isFinite(west) || !Double.isFinite(north) || !Double.isFinite(east)
                || south < 37.40 || north > 37.73 || west < 126.75 || east > 127.20
                || north <= south || east <= west || north - south > .12 || east - west > .12) {
            throw new IllegalArgumentException("서울 안의 경로 범위를 선택해 주세요. 범위가 너무 넓으면 지도를 불러올 수 없어요.");
        }
    }
    private static String enc(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    public static class Unavailable extends RuntimeException {
        public Unavailable(String message) { super(message); }
    }
}
