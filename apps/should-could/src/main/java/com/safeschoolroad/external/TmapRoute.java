package com.safeschoolroad.external;

import com.safeschoolroad.geo.LatLng;

import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

// TMAP 보행자 경로 응답 하나. 약관 때문에 이 값은 DB에 저장하지 않는다(당일 캐시만).
public record TmapRoute(List<LatLng> line, List<Turn> turns, int distanceMeters, int timeSeconds) {

    // TMAP 보행자 turnType: 12 좌회전, 13 우회전, 14 유턴, 16~19 8·10·2·4시 방향, 211~217 횡단보도
    private static final Set<Integer> TURN_TYPES = Set.of(12, 13, 14, 16, 17, 18, 19);
    private static final Set<Integer> CROSS_TYPES = Set.copyOf(IntStream.rangeClosed(211, 217).boxed().toList());

    public record Turn(double lat, double lng, int type, String description) {

        /** TMAP이 "횡단보도를 건넌다"고 한 곳 */
        public boolean isCrossing() {
            return CROSS_TYPES.contains(type);
        }

        /** 길을 꺾는 곳 (횡단보도 제외) */
        public boolean isTurn() {
            return TURN_TYPES.contains(type);
        }
    }
}
