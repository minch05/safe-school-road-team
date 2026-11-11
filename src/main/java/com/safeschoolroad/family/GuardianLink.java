package com.safeschoolroad.family;

import com.safeschoolroad.member.Member;
import jakarta.persistence.Column;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

// 보호자–자녀 연동 (HANDOFF 3장). 보호자 한 명에 자녀 여러 명, 자녀 한 명에 보호자 한 명.
@Entity
@Table(name = "guardian_link")
public class GuardianLink {

    public enum Type {
        CREATED, // 보호자가 만든 14세 미만 아이 계정. 연동 해제 없음(삭제만)
        INVITED  // 초대 코드로 연동한 14세 이상 자녀. 연동 해제 가능
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "guardian_id")
    private Member guardian;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "child_id", unique = true)
    private Member child;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR) // MySQL ENUM 대신 글자 칸 (종류를 나중에 늘려도 되게)
    @Column(nullable = false, length = 10)
    private Type type;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    protected GuardianLink() {
    }

    public GuardianLink(Member guardian, Member child, Type type) {
        this.guardian = guardian;
        this.child = child;
        this.type = type;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public Member getGuardian() {
        return guardian;
    }

    public Member getChild() {
        return child;
    }

    public Type getType() {
        return type;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
