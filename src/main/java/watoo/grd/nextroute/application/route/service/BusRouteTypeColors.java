package watoo.grd.nextroute.application.route.service;

import java.util.Map;

/** 서울시 버스 {@code routeType} 기준의 공식 노선유형 표시색. */
public final class BusRouteTypeColors {

    private static final Map<Integer, String> COLORS = Map.of(
            2, "#53b332", // 마을
            3, "#0068b7", // 간선
            4, "#53b332", // 지선
            5, "#f2b70a", // 순환
            6, "#e60012"  // 광역
    );

    private BusRouteTypeColors() {
    }

    public static String colorFor(Integer routeType) {
        return COLORS.get(routeType);
    }
}
