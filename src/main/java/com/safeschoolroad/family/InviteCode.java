package com.safeschoolroad.family;

import com.safeschoolroad.member.Member;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

// 초대 코드 (FR-23, 만 14세 이상 자녀 연동). 보호자가 발급, 6자리 숫자, 10분 동안 한 번만 쓸 수 있다.
@Entity
@Table(name = "invite_code")
public class InviteCode {

    public static final int VALID_MINUTES = 10;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "guardian_id")
    private Member guardian;

    @Column(nullable = false, length = 6)
    private String code;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    private LocalDateTime usedAt;

    protected InviteCode() {
    }

    public InviteCode(Member guardian, String code) {
        this.guardian = guardian;
        this.code = code;
        this.expiresAt = LocalDateTime.now().plusMinutes(VALID_MINUTES);
    }

    public boolean isUsable() {
        return usedAt == null && LocalDateTime.now().isBefore(expiresAt);
    }

    public void use() {
        usedAt = LocalDateTime.now();
    }

    public Long getId(){return id;}

    public Member getGuardian() {
        return guardian;
    }

    public String getCode() {
        return code;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }
}
