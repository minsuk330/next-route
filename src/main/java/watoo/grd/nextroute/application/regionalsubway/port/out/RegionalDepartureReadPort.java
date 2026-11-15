package watoo.grd.nextroute.application.regionalsubway.port.out;

import java.util.List;

/** {@code regional_subway_departure} 조회. */
public interface RegionalDepartureReadPort {

    /** 그 역, 그 요일 출발(서비스일 초 오름차순). */
    List<Row> departures(String stationKey, String dayType);

    record Row(int depSeconds, String terminalName, int terminalSeq) {
    }
}
