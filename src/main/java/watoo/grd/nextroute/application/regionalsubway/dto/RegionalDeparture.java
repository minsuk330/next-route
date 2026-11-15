package watoo.grd.nextroute.application.regionalsubway.dto;

/**
 * 원천에서 받은 열차 한 편(그 역 출발).
 *
 * @param depSeconds   서비스일 0시 기준 초. 04:00 전과 24:xx 는 86400 이상
 * @param terminalName 그 열차 종착역 이름(원천 표기 그대로). 적재 서비스가 시드 역에 맞춰 seq 를 붙인다
 */
public record RegionalDeparture(RegionalDayType dayType, int depSeconds, String terminalName) {
}
