package com.safeschoolroad;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SetupController {
    @GetMapping("/health/setup")
    public Map<String, String> health() { return Map.of("status", "UP"); }
}
