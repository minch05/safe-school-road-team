package com.safeschoolroad.member;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.RememberMeServices;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.Map;

// 화면 C-02 로그인, C-03 연령 확인, C-04 가입 정보 입력
@Controller
public class AuthController {

    private static final String USERNAME_RULE = "^[a-z0-9]{4,20}$";

    private final MemberService memberService;
    private final UserDetailsService userDetailsService;
    private final RememberMeServices rememberMeServices;
    private final SecurityContextRepository securityContextRepository;

    public AuthController(MemberService memberService,
                          UserDetailsService userDetailsService,
                          RememberMeServices rememberMeServices,
                          SecurityContextRepository securityContextRepository) {
        this.memberService = memberService;
        this.userDetailsService = userDetailsService;
        this.rememberMeServices = rememberMeServices;
        this.securityContextRepository = securityContextRepository;
    }

    // C-02. 실제 로그인 처리(POST /login)는 Spring Security가 한다.
    @GetMapping("/login")
    public String loginPage(Authentication authentication) {
        if (isLoggedIn(authentication)) {
            return "redirect:/";
        }
        return "login";
    }

    // C-03. under14=true 면 "보호자가 만들어 준 아이디로 로그인하세요" 안내를 보여 준다.
    @GetMapping("/signup")
    public String agePage(@RequestParam(defaultValue = "false") boolean under14,
                          Authentication authentication, Model model) {
        if (isLoggedIn(authentication)) {
            return "redirect:/";
        }
        model.addAttribute("under14", under14);
        return "signup-age";
    }

    // C-04
    @GetMapping("/signup/form")
    public String signupForm(Authentication authentication, Model model) {
        if (isLoggedIn(authentication)) {
            return "redirect:/";
        }
        model.addAttribute("form", new SignupForm());
        return "signup-form";
    }

    @PostMapping("/signup")
    public String signup(@Valid @ModelAttribute("form") SignupForm form, BindingResult result,
                         HttpServletRequest request, HttpServletResponse response) {
        if (form.getPassword() != null && !form.getPassword().equals(form.getPasswordConfirm())) {
            result.rejectValue("passwordConfirm", "mismatch", "비밀번호가 서로 달라요.");
        }
        if (result.hasErrors()) {
            return "signup-form";
        }
        try {
            memberService.signup(form);
        } catch (MemberService.DuplicateUsernameException e) {
            result.rejectValue("username", "duplicate", "이미 쓰고 있는 아이디예요.");
            return "signup-form";
        }
        logIn(form.getUsername(), request, response);
        return "redirect:/";
    }

    // C-04의 "중복 확인" 버튼이 부른다
    @GetMapping("/signup/check-username")
    @ResponseBody
    public Map<String, Object> checkUsername(@RequestParam String username) {
        if (!username.matches(USERNAME_RULE)) {
            return Map.of("available", false, "message", "영문 소문자와 숫자로 4~20자를 써 주세요.");
        }
        if (memberService.isUsernameTaken(username)) {
            return Map.of("available", false, "message", "이미 쓰고 있는 아이디예요.");
        }
        return Map.of("available", true, "message", "쓸 수 있는 아이디예요.");
    }

    // 가입 직후 로그인 화면을 다시 거치지 않고 바로 로그인시킨다(자동 로그인 쿠키까지).
    private void logIn(String username, HttpServletRequest request, HttpServletResponse response) {
        UserDetails user = userDetailsService.loadUserByUsername(username);
        Authentication auth = UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities());
        if (request.getSession(false) != null) {
            request.changeSessionId(); // 로그인 전 세션 번호를 그대로 쓰지 않는다
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
        rememberMeServices.loginSuccess(request, response, auth);
    }

    private static boolean isLoggedIn(Authentication authentication) {
        return authentication != null && !(authentication instanceof AnonymousAuthenticationToken);
    }
}
