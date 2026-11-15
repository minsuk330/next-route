package watoo.grd.nextroute.application.regionalsubway.port.out;

import watoo.grd.nextroute.application.regionalsubway.dto.RegionalDeparture;
import watoo.grd.nextroute.application.regionalsubway.dto.RegionalStationSeed;

import java.util.List;

/** TAGO 평일·휴일 시간표(노선 무관). 파일 원천이 낡았는지 비교하는 데만 쓴다. */
public interface RegionalTagoCrossCheckPort {

    List<RegionalDeparture> fetchTago(RegionalStationSeed station) throws RegionalSourceException;
}
