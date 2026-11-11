package com.safeschoolroad.family;

import com.safeschoolroad.member.CurrentMember;
import com.safeschoolroad.member.Member;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

// 보호자 화면: P-02 아이 계정 만들기, P-03 자녀 상세, P-04 초대 코드
@Controller
public class FamilyController {

    private final CurrentMember current;
    private final FamilyService family;

    public FamilyController(CurrentMember current, FamilyService family) {
        this.current = current;
        this.family = family;
    }

    // ── P-02 ──
    @GetMapping("/children/new")
    public String newChild(@RequestParam(required = false) String from, Model model) {
        // 설정에서 들어왔으면 "뒤로"가 설정으로 돌아가게
        model.addAttribute("fromSettings", "settings".equals(from));
        return "child-new";
    }

    @PostMapping("/children")
    public String createChild(@RequestParam String username, @RequestParam String password,
                              @RequestParam String nickname, @RequestParam(defaultValue = "false") boolean consent,
                              @RequestParam(required = false) String from, Authentication auth, Model model) {
        model.addAttribute("fromSettings", "settings".equals(from));
        model.addAttribute("consent", consent); // 다른 칸 오류로 돌아와도 동의 체크는 유지
        model.addAttribute("username", username);
        model.addAttribute("nickname", nickname);
        if (!consent) {
            model.addAttribute("error", "보호자 동의에 체크해 주세요.");
            return "child-new";
        }
        try {
            Member child = family.createChild(current.get(auth), username.strip(), password, nickname);
            model.addAttribute("child", child);
            return "child-created";
        } catch (IllegalArgumentException | IllegalStateException e) {
            model.addAttribute("error", e.getMessage());
            return "child-new";
        }
    }

    // ── P-03 ──
    @GetMapping("/children/{id}")
    public String child(@PathVariable long id, Authentication auth, Model model) {
        model.addAttribute("detail", family.child(current.get(auth), id));
        return "child-detail";
    }

    @PostMapping("/children/{id}/password")
    public String resetPassword(@PathVariable long id, @RequestParam String password, Authentication auth,
                                RedirectAttributes flash) {
        try {
            family.resetChildPassword(current.get(auth), id, password);
            flash.addFlashAttribute("toast", "비밀번호를 다시 정했어요. 아이 폰에서 새 비밀번호로 로그인해요.");
        } catch (IllegalArgumentException | IllegalStateException e) {
            flash.addFlashAttribute("toast", e.getMessage());
        }
        return "redirect:/children/" + id;
    }

    @PostMapping("/children/{id}/delete")
    public String delete(@PathVariable long id, @RequestParam String confirmNickname, Authentication auth,
                         RedirectAttributes flash) {
        Member guardian = current.get(auth);
        FamilyService.ChildDetail detail = family.child(guardian, id);
        if (!detail.child().getNickname().equals(confirmNickname.strip())) {
            flash.addFlashAttribute("toast", "별명이 맞지 않아 삭제하지 않았어요.");
            return "redirect:/children/" + id;
        }
        family.deleteChild(guardian, id);
        flash.addFlashAttribute("toast", detail.child().getNickname() + " 계정을 삭제했어요.");
        return "redirect:/";
    }

    @PostMapping("/children/{id}/unlink")
    public String unlink(@PathVariable long id, Authentication auth, RedirectAttributes flash) {
        try {
            family.unlink(current.get(auth), id);
            flash.addFlashAttribute("toast", "연동을 해제했어요.");
        } catch (IllegalStateException e) {
            flash.addFlashAttribute("toast", e.getMessage());
        }
        return "redirect:/";
    }

    // ── P-04 초대 코드 ──
    @GetMapping("/invite")
    public String invite() {
        return "invite";
    }
}
