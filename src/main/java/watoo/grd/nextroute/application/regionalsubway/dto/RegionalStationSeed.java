package watoo.grd.nextroute.application.regionalsubway.dto;

/**
 * 커밋된 시드(resources/subway/regional-stations.json)의 역 하나.
 *
 * @param stationKey          {lineCode}-{운영사 역코드}. 예 {@code BS1-119}
 * @param seq                 노선 위 일직선 순서. 방향 판정에 쓴다
 * @param providerStationCode 운영사 역코드(부산 scode, 대구 STT_CD, 대전·광주 역번호)
 * @param gtfsStopId          OTP 정류장 id
 */
public record RegionalStationSeed(
        String stationKey,
        String lineCode,
        String lineName,
        String stationName,
        int seq,
        String tagoStationId,
        String providerStationCode,
        String gtfsStopId,
        double lat,
        double lon
) {
}
