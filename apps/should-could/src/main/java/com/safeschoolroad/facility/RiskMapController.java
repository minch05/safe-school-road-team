package com.safeschoolroad.facility;

import com.safeschoolroad.external.SafeMapClient;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
public class RiskMapController {
    private final SafeMapClient client;
    public RiskMapController(SafeMapClient client) { this.client = client; }

    @GetMapping("/api/risk-map/status")
    public Map<String, Object> status() {
        return Map.of("configured", client.configured(), "source", "생활안전지도 · 경찰청",
                "scored", false, "description", "이미지 표시만 제공해요. 빈 곳도 안전하다는 뜻은 아니에요.");
    }

    @GetMapping("/api/risk-map/crime")
    public ResponseEntity<byte[]> crime(@RequestParam double south, @RequestParam double west,
                                        @RequestParam double north, @RequestParam double east) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(MediaType.IMAGE_PNG).body(client.crimeMap(south, west, north, east));
    }

    @ExceptionHandler(SafeMapClient.Unavailable.class)
    ResponseEntity<Map<String, String>> unavailable(SafeMapClient.Unavailable e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.APPLICATION_JSON)
                .cacheControl(CacheControl.noStore()).body(Map.of("error", e.getMessage()));
    }
}
