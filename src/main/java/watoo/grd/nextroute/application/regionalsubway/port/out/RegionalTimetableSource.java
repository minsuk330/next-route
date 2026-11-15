package watoo.grd.nextroute.application.regionalsubway.port.out;

import watoo.grd.nextroute.application.regionalsubway.dto.RegionalDeparture;
import watoo.grd.nextroute.application.regionalsubway.dto.RegionalStationSeed;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * 노선 묶음별 공식 시간표 원천. 역 하나의 지원 요일 전부를 한 번에 돌려준다(방향 구분 없이 — 방향은 종착역 seq 로 정한다).
 */
public interface RegionalTimetableSource {

    /** 이 원천이 맡는 노선 코드. 예 {@code BS1..BS4}. */
    Set<String> lineCodes();

    /** {@code regional_subway_departure.source} 값. */
    String sourceCode();

    List<RegionalDeparture> fetch(RegionalStationSeed station) throws RegionalSourceException;

    /** 원천 기준일(파일 원천의 기준일자). 모르면 null. */
    default LocalDate sourceDate() {
        return null;
    }

    /** 원격 호출이면 true — 적재 서비스가 호출 사이 간격을 둔다. 리소스 파일 원천은 false. */
    default boolean remote() {
        return true;
    }
}
