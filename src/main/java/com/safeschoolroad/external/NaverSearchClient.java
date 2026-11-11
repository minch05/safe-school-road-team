package com.safeschoolroad.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** 서버에서만 비밀키를 사용한다. 검색 결과/주소는 로그나 캐시에 남기지 않는다. */
@Component
public class NaverSearchClient {
    private final RestClient http = Http.client(Duration.ofSeconds(6));
    private final ObjectMapper mapper;
    @Value("${NAVER_MAPS_CLIENT_ID:}") private String mapsId;
    @Value("${NAVER_MAPS_CLIENT_SECRET:}") private String mapsSecret;
    @Value("${NAVER_SEARCH_CLIENT_ID:}") private String searchId;
    @Value("${NAVER_SEARCH_CLIENT_SECRET:}") private String searchSecret;
    public NaverSearchClient(ObjectMapper mapper) { this.mapper=mapper; }

    public List<VworldSearchClient.Item> search(String query) {
        String q=query.strip();
        if(q.length()<2 || q.length()>100) return List.of();
        var places=local(q);
        var addresses=geocode(q);
        var out=new ArrayList<VworldSearchClient.Item>();
        boolean address=q.chars().anyMatch(Character::isDigit);
        for(var item:address?addresses:places) addUnique(out,item);
        for(var item:address?places:addresses) addUnique(out,item);
        return out.stream().limit(10).toList();
    }
    private List<VworldSearchClient.Item> local(String q) {
        if(searchId.isBlank()||searchSecret.isBlank())return List.of();
        JsonNode root=request("https://naverapihub.apigw.ntruss.com/search/v1/local?display=5&start=1&sort=random&format=json&query="+encode(q),searchId,searchSecret);
        var out=new ArrayList<VworldSearchClient.Item>();
        for(var node:root.path("items")){var item=localItem(node);if(item!=null)out.add(item);}
        return out;
    }
    private List<VworldSearchClient.Item> geocode(String q) {
        if(mapsId.isBlank()||mapsSecret.isBlank())return List.of();
        JsonNode root=request("https://maps.apigw.ntruss.com/map-geocode/v2/geocode?count=5&query="+encode(q),mapsId,mapsSecret);
        var out=new ArrayList<VworldSearchClient.Item>();
        for(var node:root.path("addresses")){
            double lng=node.path("x").asDouble(Double.NaN),lat=node.path("y").asDouble(Double.NaN);
            if(!inSeoul(lat,lng))continue;
            String address=node.path("roadAddress").asText("");if(address.isBlank())address=node.path("jibunAddress").asText("");
            out.add(new VworldSearchClient.Item("",address,lat,lng,false));
        }
        return out;
    }
    private JsonNode request(String url,String id,String secret) {
        try {return mapper.readTree(http.get().uri(URI.create(url)).header("X-NCP-APIGW-API-KEY-ID",id)
            .header("X-NCP-APIGW-API-KEY",secret).header("Accept","application/json").retrieve().body(String.class));}
        catch(Exception ignored){return mapper.createObjectNode();} // URL/본문/키를 로그에 기록하지 않음
    }
    static VworldSearchClient.Item localItem(JsonNode node) {
        double lng=coordinate(node.path("mapx").asText()),lat=coordinate(node.path("mapy").asText());
        if(!inSeoul(lat,lng))return null;
        String title=node.path("title").asText("").replaceAll("(?i)</?b>","");
        String address=node.path("roadAddress").asText("");if(address.isBlank())address=node.path("address").asText("");
        return new VworldSearchClient.Item(title,address,lat,lng,true);
    }
    // 지역 검색은 WGS84를 10^7배한 정수로도 반환한다. 주소 검색의 소수 좌표와 모두 호환.
    static double coordinate(String value) {
        try {double n=Double.parseDouble(value);return Math.abs(n)>180?n/10_000_000d:n;}catch(NumberFormatException e){return Double.NaN;}
    }
    static boolean inSeoul(double lat,double lng){return Double.isFinite(lat)&&Double.isFinite(lng)&&lat>=37.41&&lat<=37.72&&lng>=126.76&&lng<=127.19;}
    private static String encode(String q){return URLEncoder.encode(q,StandardCharsets.UTF_8);}
    private static void addUnique(List<VworldSearchClient.Item> out,VworldSearchClient.Item item){
        if(out.stream().noneMatch(x->Math.abs(x.lat()-item.lat())<.00001&&Math.abs(x.lng()-item.lng())<.00001&&x.title().equals(item.title())))out.add(item);
    }
}
