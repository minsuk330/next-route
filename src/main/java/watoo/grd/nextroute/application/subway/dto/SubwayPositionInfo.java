package watoo.grd.nextroute.application.subway.dto;

/**
 * 열차 한 대의 현재 위치. 서울 실시간 <b>위치</b> API({@code realtimePosition}) 한 행이다.
 *
 * <p>도착정보({@link SubwayArrivalInfo})와 축이 다르다. 도착정보는 "역마다 다음에 올 열차"라
 * 같은 열차가 여러 역의 행으로 <b>동시에</b> 나타난다. 그래서 그것으로는 "이 열차가 지금 어디
 * 있는가"를 말할 수 없다. 위치 API 는 열차 기준이라 그 질문에 답한다.
 *
 * @param lineId       {@code subwayId}. 도착정보와 같은 ID 공간이다
 * @param trainNo      {@code trainNo}. <b>전역 유일하지 않다</b> — 자정 전후 재사용과 노선별
 *                     중복이 있어, 열차를 가리키려면 운행일·노선·방향과 함께 써야 한다
 * @param stationId    {@code statnId}. 지금 이 열차가 있는 역
 * @param stationName  {@code statnNm}
 * @param direction    {@code updnLine} 을 도착정보와 같은 표기로 정규화한 값
 *                     (상행·하행·내선·외선). 해석하지 못하면 null
 * @param terminalId   {@code statnTid}. 종착역
 * @param terminalName {@code statnTnm}
 * @param status       {@code trainSttus}
 * @param express      {@code directAt}
 * @param receivedAt   {@code recptnDt}. <b>최종수신시간이지 사건 발생 시각이 아니다.</b>
 *                     이 값을 출발·도착 시각으로 단정하면 안 된다
 * @param lastReceivedDate {@code lastRecptnDt}. 최종수신날짜. {@code receivedAt} 이 시각만
 *                     담고 오는 경우를 위해 함께 보존한다
 */
public record SubwayPositionInfo(
        String lineId,
        String trainNo,
        String stationId,
        String stationName,
        String direction,
        String terminalId,
        String terminalName,
        SubwayTrainStatus status,
        SubwayExpressType express,
        String receivedAt,
        String lastReceivedDate
) {

    /** 위치로 쓸 수 있는 최소 조건. 하나라도 없으면 전이 판정의 근거가 되지 못한다. */
    public boolean isTrackable() {
        return hasText(lineId) && hasText(trainNo) && hasText(stationId) && hasText(direction);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
