package com.safeschoolroad.place;

import com.safeschoolroad.member.CurrentMember;
import com.safeschoolroad.member.Member;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

// R-07 내 장소 관리 (이름 바꾸기·지우기). 장소는 경로를 등록할 때 저장된다.
// 장소를 지워도 이미 저장한 경로는 그대로다(경로가 출발지·도착지 좌표를 따로 갖고 있음).
@Controller
public class PlaceController {

    private final CurrentMember current;
    private final PlaceRepository places;

    public PlaceController(CurrentMember current, PlaceRepository places) {
        this.current = current;
        this.places = places;
    }

    @GetMapping("/places")
    public String list(Authentication auth, Model model) {
        model.addAttribute("places", places.findByOwnerOrderByIdAsc(current.get(auth)));
        return "places";
    }

    @PostMapping("/places/{id}/rename")
    @Transactional
    public String rename(@PathVariable long id, @RequestParam String name, Authentication auth, RedirectAttributes flash) {
        Place place = mine(current.get(auth), id);
        String n = name.strip();
        if (n.isEmpty() || n.length() > 30) {
            flash.addFlashAttribute("toast", "이름은 1~30자로 써 주세요.");
        } else if (places.findByOwnerAndName(place.getOwner(), n).filter(p -> !p.getId().equals(id)).isPresent()) {
            flash.addFlashAttribute("toast", "같은 이름의 장소가 이미 있어요.");
        } else {
            place.rename(n);
            flash.addFlashAttribute("toast", "이름을 바꿨어요.");
        }
        return "redirect:/places";
    }

    @PostMapping("/places/{id}/delete")
    @Transactional
    public String delete(@PathVariable long id, Authentication auth, RedirectAttributes flash) {
        places.delete(mine(current.get(auth), id));
        flash.addFlashAttribute("toast", "장소를 지웠어요.");
        return "redirect:/places";
    }

    private Place mine(Member me, long id) {
        Place p = places.findById(id).orElseThrow(() -> new AccessDeniedException("없는 장소"));
        if (!p.getOwner().getId().equals(me.getId())) {
            throw new AccessDeniedException("내 장소가 아님");
        }
        return p;
    }
}
