package com.safeschoolroad.family;

import com.safeschoolroad.member.CurrentMember;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

// 초대 코드 (P-04, FR-23)
@RestController
public class InviteApiController {

    private final CurrentMember current;
    private final FamilyService family;

    public InviteApiController(CurrentMember current, FamilyService family) {
        this.current = current;
        this.family = family;
    }

    public record CodeRequest(String code,String previewToken) {
    }

    @org.springframework.web.bind.annotation.GetMapping("/api/invite/status")
    public org.springframework.http.ResponseEntity<FamilyService.LinkStatus> status(Authentication auth) {
        return org.springframework.http.ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .body(family.linkStatus(current.get(auth)));
    }

    @PostMapping("/api/invite/issue")
    public Map<String, Object> issue(Authentication auth) {
        InviteCode code = family.issueInvite(current.get(auth));
        return Map.of("code", code.getCode(), "minutes", InviteCode.VALID_MINUTES);
    }

    private static final String PREVIEW="invitePreview";
    private record Preview(String token,Long childId,Long inviteId) implements java.io.Serializable {}

    @PostMapping("/api/invite/preview")
    public Map<String, Object> preview(@RequestBody CodeRequest req, Authentication auth,jakarta.servlet.http.HttpSession session) {
        session.removeAttribute(PREVIEW);
        var child=current.get(auth);var invite=family.previewInviteCode(child,req.code());
        var confirmed=new Preview(java.util.UUID.randomUUID().toString(),child.getId(),invite.getId());
        session.setAttribute(PREVIEW,confirmed);
        return Map.of("guardian",invite.getGuardian().getNickname(),"previewToken",confirmed.token());
    }

    @PostMapping("/api/invite/redeem")
    public Map<String, Object> redeem(@RequestBody CodeRequest req, Authentication auth,jakarta.servlet.http.HttpSession session) {
        var child=current.get(auth);
        var confirmed=session.getAttribute(PREVIEW);
        if(!(confirmed instanceof Preview preview)||!preview.childId().equals(child.getId())||!preview.token().equals(req.previewToken()))
            throw new IllegalArgumentException("보호자를 다시 확인해 주세요.");
        family.redeemInvite(child,req.code(),preview.inviteId());
        session.removeAttribute(PREVIEW);
        return Map.of("ok", true);
    }
}
