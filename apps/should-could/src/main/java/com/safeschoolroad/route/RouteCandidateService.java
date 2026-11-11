package com.safeschoolroad.route;

import com.safeschoolroad.external.TmapClient;
import com.safeschoolroad.external.TmapRoute;
import com.safeschoolroad.geo.LatLng;
import com.safeschoolroad.route.analysis.RouteAnalysis;
import com.safeschoolroad.route.analysis.RouteAnalyzer;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

// 후보 경로 만들기 (FR-04, 05). TMAP 탐색 옵션 3개를 받아 분석하고, 같은 경로는 하나로 합친다(HANDOFF 5-1).
// "같은 경로" = 지나는 시설(건너는 횡단보도·핵심 지점)이 같은 것 (2026-10-09 결정)
@Service
public class RouteCandidateService {

    // 핵심 지점이 하나도 없는 아주 짧은 길끼리는 길이로 비교한다
    private static final int SAME_LENGTH_M = 20;

    private final TmapClient tmap;
    private final RouteAnalyzer analyzer;
    private final com.safeschoolroad.route.analysis.CrimeExposureService crime;

    public RouteCandidateService(TmapClient tmap, RouteAnalyzer analyzer, com.safeschoolroad.route.analysis.CrimeExposureService crime) {
        this.tmap = tmap;
        this.analyzer = analyzer;
        this.crime = crime;
    }

    /** options: 이 후보를 알려 준 탐색 옵션들 (첫 번째가 대표) */
    public record Candidate(List<Integer> options, RouteAnalysis analysis, com.safeschoolroad.route.analysis.CrimeExposure crime) {
        public Candidate(List<Integer> options, RouteAnalysis analysis) {
            this(options, analysis, com.safeschoolroad.route.analysis.CrimeExposure.unknown("미확인"));
        }
        public int option() {
            return options.get(0);
        }

        public String label() {
            return String.join("·", options.stream().map(TmapClient::optionLabel).toList());
        }
    }

    public List<Candidate> find(LatLng start, LatLng end) {
        List<Candidate> unique = new ArrayList<>();
        for (int option : TmapClient.OPTIONS) {
            RouteAnalysis analysis = analyzer.analyze(tmap.pedestrian(start, end, option, List.of()));
            Candidate same = unique.stream().filter(c -> sameRoute(c.analysis(), analysis)).findFirst().orElse(null);
            if (same != null) {
                List<Integer> merged = new ArrayList<>(same.options());
                merged.add(option);
                unique.set(unique.indexOf(same), new Candidate(merged, same.analysis()));
            } else {
                unique.add(new Candidate(List.of(option), analysis));
            }
        }
        var exposures = crime.compare(unique.stream().map(c -> c.analysis().route().line()).toList());
        for (int i=0;i<unique.size();i++) {
            Candidate c=unique.get(i);
            unique.set(i,new Candidate(c.options(),c.analysis(),exposures.get(i)));
        }
        return unique;
    }

    /** 확정할 때: 고른 옵션 하나만 다시 분석한다 (오늘 받은 것이면 캐시에서 나온다) */
    public RouteAnalysis analyzeOption(LatLng start, LatLng end, int option) {
        if (!TmapClient.OPTIONS.contains(option)) {
            throw new IllegalArgumentException("없는 탐색 옵션: " + option);
        }
        return analyzer.analyze(tmap.pedestrian(start, end, option, List.of()));
    }

    static boolean sameRoute(RouteAnalysis a, RouteAnalysis b) {
        if (a.keyPoints().isEmpty() && b.keyPoints().isEmpty()) {
            TmapRoute ra = a.route(), rb = b.route();
            return Math.abs(ra.distanceMeters() - rb.distanceMeters()) <= SAME_LENGTH_M;
        }
        return a.facilitySignature().equals(b.facilitySignature());
    }
}
