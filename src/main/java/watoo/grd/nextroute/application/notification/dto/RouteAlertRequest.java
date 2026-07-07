package watoo.grd.nextroute.application.notification.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalTime;
import java.util.List;

/**
 * 반복(요일)/일회성 목표 도착 알림 구독 요청. 절대 날짜 없이 시각(HH:mm)만 지정한다.
 * repeat=true 면 weekdays 로 매주 반복, false 면 다음 도래(금일/익일) 1회.
 */
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

    /** 목표 도착 시각(HH:mm). 날짜는 서버가 다음 도래로 결정. */
    @NotNull
    @JsonFormat(pattern = "HH:mm")
    private LocalTime arrivalTime;

    /** 반복 요일 코드(["MON","WED",...]). repeat=true 면 1개 이상 필수. 일회성이면 무시. */
    private List<String> weekdays;

    /** true=반복, false=일회성. */
    private boolean repeat;

    /** 출발 리마인드 리드타임(분, {0,5,10,15}). */
    @NotNull
    @PositiveOrZero
    private Integer userLeadMinutes;

    /** 안전 버퍼(분). null 이면 서버 기본값 사용. */
    @PositiveOrZero
    private Integer bufferMinutes;
}
