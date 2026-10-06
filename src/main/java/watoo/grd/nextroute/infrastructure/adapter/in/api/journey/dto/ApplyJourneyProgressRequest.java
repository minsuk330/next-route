package watoo.grd.nextroute.infrastructure.adapter.in.api.journey.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import watoo.grd.nextroute.application.journey.dto.JourneyProgressCommand;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 진행 요약 요청 본문.
 *
 * <p>{@code progressId} 는 앱이 만들어 보낸다. 서버가 만들면 재전송을 구분할 수 없다 — 응답을
 * 못 받아 다시 보내는 것이 바로 그 상황이기 때문이다. 명령의 {@code eventId} 와는 별개의
 * 식별자다. progress 는 명령이 아니라 관측이라 같은 이력에 섞이지 않는다.
 *
 * <p>정밀 좌표를 받는 필드는 없다. 어느 구간 몇 %인지와 남은 거리·시간만 받는다.
 *
 * @param progressId               progress 식별자. 재전송이면 같은 값
 * @param routeRevision            앱이 보고 있던 경로 개정본
 * @param legIndex                 진행 중인 단계 순번
 * @param geometrySegmentIndex     단계 안에서의 구간 순번
 * @param segmentProgress          현재 구간 진행률 0~1
 * @param legProgress              단계 전체 진행률 0~1
 * @param sectionId                버스 구간 식별자. 버스가 아니면 비운다
 * @param remainingDistanceMeters  단계 끝까지 남은 거리(m)
 * @param remainingDurationSeconds 단계 끝까지 남은 시간(초)
 * @param remainingStops           남은 정류장 수. 버스는 정류장, 지하철은 역 수(#264 — 재탐색 보드가
 *                                 "하나 지났다"를 이 값으로 안다). 그 밖에는 비운다
 * @param observedAt               앱이 이 진행을 확정한 시각
 * @param quality                  관측 품질
 */
public record ApplyJourneyProgressRequest(
        @NotNull UUID progressId,
        @NotNull @PositiveOrZero Integer routeRevision,
        @NotNull @PositiveOrZero Integer legIndex,
        @PositiveOrZero Integer geometrySegmentIndex,
        @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal segmentProgress,
        @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal legProgress,
        String sectionId,
        @PositiveOrZero Integer remainingDistanceMeters,
        @PositiveOrZero Integer remainingDurationSeconds,
        @PositiveOrZero Integer remainingStops,
        @NotNull Instant observedAt,
        String quality
) {

    public JourneyProgressCommand toCommand() {
        return new JourneyProgressCommand(progressId, routeRevision, legIndex,
                geometrySegmentIndex, segmentProgress, legProgress, sectionId,
                remainingDistanceMeters, remainingDurationSeconds, remainingStops,
                observedAt, quality);
    }
}
