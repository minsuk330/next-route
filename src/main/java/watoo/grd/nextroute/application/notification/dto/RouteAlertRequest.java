package watoo.grd.nextroute.application.notification.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 목표 도착시간 기반 출발 알림 구독 요청. */
@Getter
@NoArgsConstructor
public class RouteAlertRequest {

    @NotNull
    private Double startX;
    @NotNull
    private Double startY;
    private String startName;

    @NotNull
    private Double endX;
    @NotNull
    private Double endY;
    private String endName;

    /** 목표 도착시각(미래). */
    @NotNull
    private LocalDateTime targetArrivalAt;

    /** 출발 리마인드 리드타임(분, 5분 단위). */
    @NotNull
    @PositiveOrZero
    private Integer userLeadMinutes;

    /** 안전 버퍼(분). null 이면 서버 기본값 사용. */
    @PositiveOrZero
    private Integer bufferMinutes;
}
