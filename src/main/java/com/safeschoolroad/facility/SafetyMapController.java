package com.safeschoolroad.facility;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
@Controller
public class SafetyMapController {
    @GetMapping("/map") public String map() { return "safety-map"; }
}
