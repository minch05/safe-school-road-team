package com.safeschoolroad.route.analysis;

import com.safeschoolroad.facility.Facility;
import com.safeschoolroad.facility.FacilityKind;
import com.safeschoolroad.geo.LatLng;
import com.safeschoolroad.geo.Polyline;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 횡단보도 신호 추정 규칙 (2026-10-10 개정).
 * 보행등마다 15m 안에서 가장 가까운 횡단보도 하나에만 연결한다. 연결된 보행등이 있는 횡단보도만 "신호 있음 추정".
 *
 * 동작구 정답 자료(전국횡단보도표준데이터의 보행자신호등유무, 1,057곳)로 비교한 결과:
 *   예전 규칙(보행등 10m 안이면 추정)      정밀도 76% · 재현율 21%
 *   직전 규칙(10m, 다른 횡단보도와 겹치면 제외) 정밀도 81% · 재현율 15%
 *   이 규칙(가장 가까운 횡단보도에만, 15m)   정밀도 86% · 재현율 40%
 * 12m 는 정밀도가 같고 재현율이 낮았고, 18m 부터는 정밀도가 떨어졌다. 한 구로 맞춘 값이라 현장 확인이 필요하다.
 * 정밀도가 100%가 아니므로 안내문은 여전히 신호를 가정하지 않는다(HANDOFF 5-8).
 */
public final class SignalLinks {

    public static final double LINK_M = 15;

    private SignalLinks() {
    }

    /** 보행등이 연결된 횡단보도의 id */
    public static Set<Long> linkedCrosswalks(Collection<Facility> crosswalks, Collection<Facility> signals) {
        Set<Long> linked = new HashSet<>();
        for (Facility s : signals) {
            LatLng sp = new LatLng(s.lat(), s.lng());
            Facility best = null;
            double bestD = LINK_M;
            for (Facility c : crosswalks) {
                double d = Polyline.distance(sp, new LatLng(c.lat(), c.lng()));
                if (d < bestD || d == bestD && (best == null || c.id() < best.id())) {
                    bestD = d;
                    best = c;
                }
            }
            if (best != null) {
                linked.add(best.id());
            }
        }
        return linked;
    }

    /** 15m 안에 보행등은 있지만 모두 다른 횡단보도에 더 가까워 이 횡단보도에 연결하지 못한 경우 */
    public static boolean onlySharedLights(Facility crossing, Collection<Facility> crosswalks, Collection<Facility> signals,
                                           Set<Long> linked) {
        if (linked.contains(crossing.id())) {
            return false;
        }
        LatLng cp = new LatLng(crossing.lat(), crossing.lng());
        return signals.stream().anyMatch(s -> Polyline.distance(cp, new LatLng(s.lat(), s.lng())) <= LINK_M);
    }

    static List<Facility> ofKind(Collection<Facility> all, FacilityKind kind) {
        return all.stream().filter(f -> f.kind() == kind).toList();
    }
}
