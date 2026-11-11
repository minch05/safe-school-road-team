package com.safeschoolroad.push;

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

// 웹 푸시 구독 정보 (HANDOFF 3장 "받는 것"). 기기(브라우저)마다 하나. 보호자가 출발·도착 알림을 받는 데 쓴다.
@Entity
@Table(name = "push_subscription")
public class PushSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id")
    private Member member;

    // 푸시 서버 주소 (예: web.push.apple.com/...). 기기마다 다르다
    @Column(nullable = false, unique = true, length = 700)
    private String endpoint;

    @Column(nullable = false, length = 200)
    private String p256dh;

    @Column(nullable = false, length = 100)
    private String auth;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(length=36)
    private String registrationGeneration;

    protected PushSubscription() {
    }

    public PushSubscription(Member member, String endpoint, String p256dh, String auth) {
        this.member = member;
        this.endpoint = endpoint;
        this.p256dh = p256dh;
        this.auth = auth;
        this.createdAt = LocalDateTime.now();
        this.registrationGeneration = java.util.UUID.randomUUID().toString();
    }

    public void update(Member member, String p256dh, String auth) {
        if (!java.util.Objects.equals(this.member.getId(),member.getId())
                || !java.util.Objects.equals(this.p256dh,p256dh) || !java.util.Objects.equals(this.auth,auth))
            this.registrationGeneration = java.util.UUID.randomUUID().toString();
        this.member = member;
        this.p256dh = p256dh;
        this.auth = auth;
    }

    public String getRegistrationGeneration(){return registrationGeneration;}

    public Long getId() {
        return id;
    }

    public Member getMember() {
        return member;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public String getP256dh() {
        return p256dh;
    }

    public String getAuth() {
        return auth;
    }
}
