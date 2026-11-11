package com.safeschoolroad.place;

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

// 내 장소 (FR-16). 사용자가 붙인 이름과 좌표만 저장한다. 주소 글자는 저장하지 않는다(HANDOFF 3장).
@Entity
@Table(name = "place")
public class Place {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 장소의 주인 (보호자가 아이 경로를 등록하며 저장하면 아이의 장소가 된다)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id")
    private Member owner;

    @Column(nullable = false, length = 30)
    private String name;

    @Column(nullable = false)
    private double lat;

    @Column(nullable = false)
    private double lng;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    protected Place() {
    }

    public Place(Member owner, String name, double lat, double lng) {
        this.owner = owner;
        this.name = name;
        this.lat = lat;
        this.lng = lng;
        this.createdAt = LocalDateTime.now();
    }

    public void rename(String name) {
        this.name = name;
    }

    public void move(double lat, double lng) {
        this.lat = lat;
        this.lng = lng;
    }

    public Long getId() {
        return id;
    }

    public Member getOwner() {
        return owner;
    }

    public String getName() {
        return name;
    }

    public double getLat() {
        return lat;
    }

    public double getLng() {
        return lng;
    }
}
