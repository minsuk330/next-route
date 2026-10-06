package watoo.grd.nextroute.application.regionalbus.port.out;

import watoo.grd.nextroute.application.regionalbus.dto.RegionalRouteStop;
import watoo.grd.nextroute.application.regionalbus.dto.TagoCity;
import watoo.grd.nextroute.application.regionalbus.dto.TagoRoute;

import java.util.List;

/**
 * 경기·TAGO 노선 원천. 실패는 {@link RegionalBusSourceException} 으로 던진다.
 *
 * <p>정류장 목록이 비어 있으면 빈 리스트다(오류가 아니다).
 */
public interface RegionalBusRouteSourcePort {

    List<TagoCity> tagoCities();

    /** 그 도시의 노선 전체(전 페이지). */
    List<TagoRoute> tagoRoutes(String cityCode);

    /** TAGO 노선별 경유정류소. */
    List<RegionalRouteStop> tagoRouteStops(String cityCode, String routeId);

    /** 경기 노선정류소. {@code routeId} 는 접두사 없는 9자리. */
    List<RegionalRouteStop> gyeonggiRouteStops(String routeId);
}
