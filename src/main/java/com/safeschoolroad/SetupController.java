package com.safeschoolroad;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
@RestController
public class SetupController {
    @GetMapping("/") public Map<String, String> home() {
        return Map.of("project", "safe-school-road", "status", "team project initialized");
    }
    @GetMapping("/health/setup") public Map<String, String> health() { return Map.of("status", "UP"); }
}
