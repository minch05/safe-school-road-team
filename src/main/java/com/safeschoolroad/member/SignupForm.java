package com.safeschoolroad.member;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// 가입 화면(C-04)에서 받는 값. 화면의 입력칸 이름과 같다.
public class SignupForm {

    @Pattern(regexp = "^[a-z0-9]{4,20}$", message = "영문 소문자와 숫자로 4~20자를 써 주세요.")
    private String username;

    @Size(min = 8, max = 64, message = "비밀번호는 8자 이상으로 써 주세요.")
    private String password;

    private String passwordConfirm;

    @NotBlank(message = "별명을 써 주세요.")
    @Size(max = 10, message = "별명은 10자까지 쓸 수 있어요.")
    private String nickname;

    @AssertTrue(message = "만 14세 이상만 직접 가입할 수 있어요.")
    private boolean over14;

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getPasswordConfirm() {
        return passwordConfirm;
    }

    public void setPasswordConfirm(String passwordConfirm) {
        this.passwordConfirm = passwordConfirm;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        // 앞뒤 빈칸은 지운다
        this.nickname = nickname == null ? null : nickname.strip();
    }

    public boolean isOver14() {
        return over14;
    }

    public void setOver14(boolean over14) {
        this.over14 = over14;
    }
}
