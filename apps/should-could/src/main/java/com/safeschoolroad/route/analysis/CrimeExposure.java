package com.safeschoolroad.route.analysis;

import java.util.List;

/** 제공기관 도로 등급에 경로 길이를 대조한 참고값. 안전 점수나 범죄 발생 확률이 아니다. */
public record CrimeExposure(boolean available, long observedMeters, long unknownMeters,
                            long upperGradeMeters, int coveragePercent, int maxGrade,
                            List<Long> gradeMeters, String message, List<Segment> segments) {
    public CrimeExposure(boolean available,long observedMeters,long unknownMeters,long upperGradeMeters,int coveragePercent,int maxGrade,List<Long> gradeMeters,String message){
        this(available,observedMeters,unknownMeters,upperGradeMeters,coveragePercent,maxGrade,gradeMeters,message,List.of());
    }
    public record Segment(String band, List<List<Double>> line) {}
    public static CrimeExposure unknown(String message) {
        return new CrimeExposure(false, 0, 0, 0, 0, 0, List.of(), message);
    }
}
