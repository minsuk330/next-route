package watoo.grd.nextroute.application.notification.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalTime;
import java.util.List;

/**
 * 알림 스케줄 수정 요청. 좌표(출발/도착)는 불변이라 포함하지 않는다 — 경로 변경은 취소+재생성.
 * 수정 가능 필드 전체 교체(PUT). 검증은 생성과 동일.
 */
@Getter
@NoArgsConstructor
public class RouteAlertUpdateRequest {

    /** 목표 도착 시각(HH:mm). */
    @NotNull
    @JsonFormat(pattern = "HH:mm")
    private LocalTime arrivalTime;

    /** 반복 요일 코드(["MON",...]). repeat=true 면 1개 이상 필수. */
    private List<String> weekdays;

    private boolean repeat;

    @NotNull
    @PositiveOrZero
    private Integer userLeadMinutes;

    /** 안전 버퍼(분). null 이면 서버 기본값 사용(create 와 동일). */
    @PositiveOrZero
    private Integer bufferMinutes;
}
