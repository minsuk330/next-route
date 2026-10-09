package watoo.grd.nextroute.application.map.port.in;

import watoo.grd.nextroute.application.map.dto.BusStopTileSnapshot;

import java.util.Optional;

/** 지도 정류장 전국 타일(#326). 아직 한 번도 못 만들었으면 빈 값(컨트롤러가 503). */
public interface GetBusStopTilesUseCase {

    Optional<BusStopTileSnapshot> current();
}
