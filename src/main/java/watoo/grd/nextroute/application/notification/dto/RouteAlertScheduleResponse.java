package watoo.grd.nextroute.application.notification.dto;

import lombok.Builder;
import lombok.Getter;
import watoo.grd.nextroute.domain.notification.entity.RouteAlertPhase;
import watoo.grd.nextroute.domain.notification.entity.RouteAlertSchedule;
import watoo.grd.nextroute.domain.notification.entity.RouteAlertScheduleStatus;
import watoo.grd.nextroute.domain.notification.entity.RouteArrivalAlert;
import watoo.grd.nextroute.domain.notification.entity.RouteWeekdays;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * 반복/일회성 알림 구독 응답. schedule 요약 + 계산된 다음 도래시각 + (있으면) 진행 중 occurrence 요약.
 */
@Getter
@Builder
public class RouteAlertScheduleResponse {
    private Long id;
    private RouteAlertScheduleStatus status;
    private Double startX;
    private Double startY;
    private String startName;
    private Double endX;
    private Double endY;
    private String endName;
    private LocalTime arrivalTime;
    private List<String> weekdays;
    private boolean repeat;
    private Integer userLeadMinutes;
    private Integer bufferMinutes;

    /** 다음 도래 목표 도착시각(materialize 여부 무관 계산값). */
    private LocalDateTime nextArrivalAt;

    // ── 진행 중 occurrence (없으면 null) ──────────────────────────────
    private Long occurrenceId;
    private RouteAlertPhase occurrencePhase;
    /** 등록 시 추정 출발시각. */
    private LocalDateTime baselineLeaveByAt;
    /** first check 로 확정된 추천 출발시각(first check 전 null). */
    private LocalDateTime recommendedLeaveByAt;

    public static RouteAlertScheduleResponse from(RouteAlertSchedule s, LocalDateTime nextArrivalAt,
                                                  RouteArrivalAlert occurrence) {
        RouteAlertScheduleResponseBuilder b = RouteAlertScheduleResponse.builder()
                .id(s.getId())
                .status(s.getStatus())
                .startX(s.getStartX())
                .startY(s.getStartY())
                .startName(s.getStartName())
                .endX(s.getEndX())
                .endY(s.getEndY())
                .endName(s.getEndName())
                .arrivalTime(s.getArrivalTime())
                .weekdays(RouteWeekdays.toCodes(s.getWeekdaysMask()))
                .repeat(s.isRepeat())
                .userLeadMinutes(s.getUserLeadMinutes())
                .bufferMinutes(s.getBufferMinutes())
                .nextArrivalAt(nextArrivalAt);
        if (occurrence != null) {
            b.occurrenceId(occurrence.getId())
                    .occurrencePhase(occurrence.getPhase())
                    .baselineLeaveByAt(occurrence.getBaselineLeaveByAt())
                    .recommendedLeaveByAt(occurrence.getRecommendedLeaveByAt());
        }
        return b.build();
    }
}
