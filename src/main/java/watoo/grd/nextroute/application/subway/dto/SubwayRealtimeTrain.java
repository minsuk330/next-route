package watoo.grd.nextroute.application.subway.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SubwayRealtimeTrain {
    private String trainNo;
    private String stationId;
    private String lineId;
    private String direction;
    private String stationName;
    private String prevStationName;
    /** 서울 {@code statnFid} — 조회역 기준 직전 역 ID. 전역 코드(3·4·5)일 때 열차가 있는 역이다(#317). */
    private String prevStationId;
    private String nextStationName;
    private Integer arrivalSeconds;
    /** Seoul API recptnDt. 포맷: "yyyy-MM-dd HH:mm:ss" */
    private String receivedAt;
    /** prevStation → stationName 구간 소요시간(초). DB에 없으면 null. */
    private Double segmentTravelSeconds;
    /** stationName → nextStation 구간 소요시간(초). arrivalCode=2 보간용. */
    private Double nextSegmentTravelSeconds;
    /** 서울 {@code arvlCd} 원값. 뜻은 {@link SubwayArrivalCode} 가 소유한다. */
    private String arrivalCode;
    private String currentMessage;
    /** 서울 {@code arvlMsg3} — 그 열차의 현재 역 이름(implementation_note 2026-08-15 실측). 운행중(99) 열차 위치의 원천(#317). */
    private String currentStationName;
    private String destinationName;
    private String trainType;
}
