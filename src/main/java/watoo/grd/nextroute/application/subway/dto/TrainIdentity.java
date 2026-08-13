package watoo.grd.nextroute.application.subway.dto;

import java.time.LocalDate;

/**
 * 열차 하나를 가리키는 값.
 *
 * <p><b>{@code trainNo} 단독으로는 부족하다.</b> 자정 전후로 번호가 재사용되고 노선마다 겹치는
 * 번호가 있다. 운행일·노선·방향을 함께 묶어야 "어제 그 열차"와 "오늘 그 열차"가, 그리고
 * "1호선 1234"와 "2호선 1234"가 한 덩어리로 뭉치지 않는다.
 *
 * @param serviceDate 운행일. {@link watoo.grd.nextroute.application.subway.service.ServiceDateResolver}
 * @param lineId      내부 line_id
 * @param direction   도착정보와 같은 표기(상행·하행·내선·외선)
 */
public record TrainIdentity(
        LocalDate serviceDate,
        String lineId,
        String direction,
        String trainNo
) {

    /** Redis 키·필드에 쓰는 표기. 사람이 읽을 수 있어야 장애 때 뒤져볼 수 있다. */
    public String asKeySuffix() {
        return serviceDate + ":" + lineId + ":" + direction + ":" + trainNo;
    }

    public String asHashField() {
        return direction + "|" + trainNo;
    }
}
