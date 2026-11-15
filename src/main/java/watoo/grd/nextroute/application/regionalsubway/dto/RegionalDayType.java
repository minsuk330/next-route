package watoo.grd.nextroute.application.regionalsubway.dto;

/**
 * 지방 지하철 시간표 요일. 운영사 공식 표 구분을 그대로 옮긴다.
 *
 * <ul>
 *   <li>{@code WD} 평일</li>
 *   <li>{@code SA} 토요일</li>
 *   <li>{@code HO} 공휴일(일요일 포함). 부산 dayType 3, 대구 HOLIDAY, 광주 휴일, TAGO 03</li>
 *   <li>{@code MH} 명절(설, 추석). 광주교통공사만 따로 운행표가 있다</li>
 * </ul>
 */
public enum RegionalDayType {
    WD, SA, HO, MH
}
