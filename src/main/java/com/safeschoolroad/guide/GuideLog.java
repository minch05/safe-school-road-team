package com.safeschoolroad.guide;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

// 안내문을 만들 때마다 남기는 기록 (FR-09 검증, FR-24 "검증 결과를 숫자로 기록").
// 어떤 모델로 몇 번 만에 통과했는지, 어떤 문제가 걸렸는지. 지점 내용·사용자 정보는 남기지 않는다.
@Entity
@Table(name = "guide_log")
public class GuideLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 저장된 경로 번호 (저장하지 않고 닫은 경우는 비어 있음)
    private Long routeId;

    @Column(nullable = false, length = 60)
    private String model;

    @Column(nullable = false, length = 10)
    private String tone;

    private int pointCount;

    // AI 를 부른 횟수 (0 = AI 없이 정해진 문장)
    private int attempts;

    // AI = 검증 통과, TEMPLATE = 정해진 문장
    @Column(nullable = false, length = 10)
    private String source;

    private int errorCount;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String errors;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    protected GuideLog() {
    }

    public GuideLog(String model, String tone, int pointCount, int attempts, String source, java.util.List<String> errors) {
        this.model = model;
        this.tone = tone;
        this.pointCount = pointCount;
        this.attempts = attempts;
        this.source = source;
        this.errorCount = errors.size();
        this.errors = String.join("\n", errors);
        this.createdAt = LocalDateTime.now();
    }

    public void linkRoute(Long routeId) {
        this.routeId = routeId;
    }

    public Long getId() {
        return id;
    }

    public Long getRouteId() {
        return routeId;
    }

    public String getModel() {
        return model;
    }

    public String getTone() {
        return tone;
    }

    public int getPointCount() {
        return pointCount;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getSource() {
        return source;
    }

    public int getErrorCount() {
        return errorCount;
    }

    public String getErrors() {
        return errors;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
