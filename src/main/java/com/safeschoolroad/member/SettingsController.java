package com.safeschoolroad.member;

import com.safeschoolroad.family.FamilyAccess;
import com.safeschoolroad.family.GuardianLink;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.authentication.rememberme.PersistentTokenBasedRememberMeServices;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Optional;

// C-06 설정, C-05 사용 방법
@Controller
public class SettingsController {

    private final CurrentMember current;
    private final FamilyAccess access;
    private final AccountService accounts;
    private final PersistentTokenBasedRememberMeServices rememberMe;

    public SettingsController(CurrentMember current, FamilyAccess access, AccountService accounts,
                              PersistentTokenBasedRememberMeServices rememberMe) {
        this.current = current;
        this.access = access;
        this.accounts = accounts;
        this.rememberMe = rememberMe;
    }

    @GetMapping("/guide")
    public String guide() {
        return "guide";
    }

    @GetMapping("/settings")
    public String settings(Authentication auth, Model model) {
        Member me = current.get(auth);
        Optional<GuardianLink> guardian = access.guardianOf(me);
        model.addAttribute("me", me);
        model.addAttribute("isGuardian", access.isGuardian(me));
        model.addAttribute("guardianLink", guardian.orElse(null));
        model.addAttribute("guardianName", guardian.map(l -> l.getGuardian().getNickname()).orElse(null));
        // 보호자가 만든 아이 계정: 비밀번호·탈퇴는 보호자가
        model.addAttribute("createdChild", guardian.map(l -> l.getType() == GuardianLink.Type.CREATED).orElse(false));
        return "settings";
    }

    @PostMapping("/settings/nickname")
    public String nickname(@RequestParam String nickname, Authentication auth, RedirectAttributes flash) {
        String n = nickname.strip();
        if (n.isEmpty() || n.length() > 10) {
            flash.addFlashAttribute("toast", "별명은 1~10자로 써 주세요.");
        } else {
            accounts.changeNickname(current.get(auth), n);
            flash.addFlashAttribute("toast", "별명을 바꿨어요.");
        }
        return "redirect:/settings";
    }

    @PostMapping("/settings/password")
    public String password(@RequestParam String current, @RequestParam String next, Authentication auth,
                           RedirectAttributes flash) {
        Member me = this.current.get(auth);
        if (access.guardianOf(me).filter(l -> l.getType() == GuardianLink.Type.CREATED).isPresent()) {
            flash.addFlashAttribute("toast", "비밀번호는 보호자에게 바꿔 달라고 부탁해 주세요.");
            return "redirect:/settings";
        }
        try {
            accounts.changePassword(me, current, next);
            flash.addFlashAttribute("toast", "비밀번호를 바꿨어요. 다른 기기에서는 다시 로그인해야 해요.");
        } catch (IllegalArgumentException e) {
            flash.addFlashAttribute("toast", e.getMessage());
        }
        return "redirect:/settings";
    }

    @PostMapping("/settings/withdraw")
    public String withdraw(@RequestParam String confirmUsername, Authentication auth, HttpServletRequest request,
                           HttpServletResponse response, RedirectAttributes flash) {
        Member me = current.get(auth);
        if (!me.getUsername().equals(confirmUsername.strip())) {
            flash.addFlashAttribute("toast", "아이디가 맞지 않아 탈퇴하지 않았어요.");
            return "redirect:/settings";
        }
        try {
            accounts.withdraw(me);
        } catch (IllegalStateException e) {
            flash.addFlashAttribute("toast", e.getMessage());
            return "redirect:/settings";
        }
        rememberMe.logout(request, response, auth);
        new SecurityContextLogoutHandler().logout(request, response, auth);
        return "redirect:/?withdrawn";
    }
}
