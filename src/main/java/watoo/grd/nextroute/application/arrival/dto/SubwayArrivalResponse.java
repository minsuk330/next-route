package watoo.grd.nextroute.application.arrival.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class SubwayArrivalResponse {
    private String lineId;
    private String direction;

    /**
     * 지금 기준으로 보정한 남은 초.
     *
     * <p><b>{@code null} 이 흔하다.</b> 원천이 시간 대신 거리로만 답하는 경우가 실측 표본의
     * 절반이다. 그때는 {@link #stationsAway} 와 {@link #currentMessage} 가 대신 답한다 —
     * 클라이언트는 이 값이 없다고 열차를 감추면 안 된다.
     */
    private Integer arrivalSeconds;

    /**
     * 조회역까지 남은 정거장 수. 조회역에 진입·도착해 있으면 {@code 0}, 알 수 없으면 {@code null}.
     *
     * <p>{@link #currentMessage} 를 파싱해서 얻는 것이 아니라 서버가 확정해 내려 준다.
     * 문구 형식은 원천 사정으로 바뀔 수 있고, 그 파싱이 클라이언트마다 흩어지면 한 번의 표현
     * 변경이 모든 화면을 동시에 깨뜨린다.
     */
    private Integer stationsAway;

    /** 서울 {@code arvlMsg2} 원문. "전역 도착", "[3]번째 전역", "5분 30초 후" 등. */
    private String currentMessage;

    private String destinationName;
    private String trainType;
    private String arrivalCode;
}
