package watoo.grd.nextroute.application.route.port.out;

import java.util.Optional;
import java.util.Set;

/**
 * ML serving 으로부터 모델이 학습한 지원 route_id 집합을 조회하는 포트.
 * "환승 예측 가능" 배지 판정의 단일 소스.
 */
public interface MlSupportedRoutesPort {

    /**
     * 모델의 route_categories(지원 route_id) 집합.
     * serving 비활성/미로드/장애 시 {@link Optional#empty()} — 호출측이 기존 캐시를 유지하도록 한다.
     */
    Optional<Set<String>> fetchSupportedRouteIds();
}
