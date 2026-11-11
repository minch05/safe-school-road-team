package com.safeschoolroad.member;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

// 회원 1명. 계정은 한 종류뿐이다(HANDOFF 3장).
// 받는 정보는 아이디·비밀번호·별명·만 14세 이상 여부뿐이다. 실명·전화번호·이메일·생년월일은 받지 않는다.
// 보호자–자녀 연동은 2단계에서 따로 테이블을 만들어 붙인다.
@Entity
@Table(name = "member")
public class Member {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 20)
    private String username;

    // 암호화한 비밀번호 (예: {bcrypt}$2a$10$...). 원래 비밀번호는 저장하지 않는다.
    @Column(nullable = false, length = 100)
    private String password;

    @Column(nullable = false, length = 10)
    private String nickname;

    // 직접 가입한 사람은 true. 보호자가 만들어 주는 아이 계정(2단계)은 false.
    @Column(nullable = false)
    private boolean over14;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    protected Member() {
        // JPA가 쓰는 빈 생성자
    }

    public Member(String username, String encodedPassword, String nickname, boolean over14) {
        this.username = username;
        this.password = encodedPassword;
        this.nickname = nickname;
        this.over14 = over14;
        this.createdAt = LocalDateTime.now();
    }

    public void changeNickname(String nickname) {
        this.nickname = nickname;
    }

    public void changePassword(String encodedPassword) {
        this.password = encodedPassword;
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public String getNickname() {
        return nickname;
    }

    public boolean isOver14() {
        return over14;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
