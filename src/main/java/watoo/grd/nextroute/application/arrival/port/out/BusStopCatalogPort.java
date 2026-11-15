package watoo.grd.nextroute.application.arrival.port.out;

import java.util.List;
import java.util.Optional;

/** 정류장 단위 도착이 어느 공급자를 부를지 정하는 카탈로그 조회. */
public interface BusStopCatalogPort {

    /** 서울 카탈로그({@code bus_stop}, 지워지지 않은 행)의 정류소 번호. 없거나 비면 empty. */
    Optional<String> seoulArsId(String stopId);

    /** 경기 카탈로그({@code regional_bus_route_stop}, provider GG)에 이 정류장이 있는지. */
    boolean isGyeonggiStop(String stopId);

    /** TAGO 정류장의 도시코드들. 정류장 하나가 도시 여럿에 걸칠 수 있다(2026-11-15 측정 2,311곳). */
    List<String> tagoCityCodes(String stopId);
}
