package watoo.grd.nextroute.infrastructure.adapter.in.api.journey.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import watoo.grd.nextroute.application.journey.dto.SubwayBoardingEvidence;
import watoo.grd.nextroute.domain.journey.entity.SubwayBindingConfidence;
import watoo.grd.nextroute.domain.journey.entity.SubwayBindingEventType;

import java.time.Instant;

/**
 * 앱이 보내는 탑승 관측.
 *
 * <p><b>열차번호를 받지 않는다.</b> 앱은 자기가 어느 열차에 탔는지 모르고, 안다고 주장해도
 * 서버가 검증할 방법이 없다. 앱은 자기가 확실히 아는 것 — 움직이기 시작했다, 잠깐 섰다 —
 * 까지만 말한다.
 *
 * @param routeRevision     이 관측이 유효한 개정본. 재탐색으로 바뀌었으면 거절한다
 * @param legIndex          단계 번호. 대기 단계를 가리켜도 서버가 짝 타는 단계로 옮겨 적는다
 * @param type              {@code MOVEMENT_START} 또는 {@code STATION_STOP_EPISODE}
 * @param sequence          종류별 순번. 1 부터. 같은 값이 다시 오면 조용히 무시한다
 * @param movementStartedAt 이동 시작 관측에서 필수
 * @param stationaryAt      정차 관측에서 필수
 * @param resumedAt         다시 움직인 시각. 아직 모르면 비운다 — 뒤따르는 관측이 채운다
 * @param occurredAt        기기 시각. 서버는 순서의 근거로 쓰지 않는다
 */
public record SubwayBoardingEvidenceRequest(
        @NotNull Integer routeRevision,
        @NotNull @Min(0) Integer legIndex,
        @NotNull SubwayBindingEventType type,
        @NotNull @Min(0) Integer sequence,
        Instant movementStartedAt,
        Instant stationaryAt,
        Instant resumedAt,
        String previousActivity,
        String currentActivity,
        SubwayBindingConfidence confidence,
        @Min(0) Integer sustainedSeconds,
        Boolean vibrationEvidence,
        @NotNull Instant occurredAt
) {

    /** 서버가 정하는 값이지 앱이 고를 값이 아니다. 판단 이름을 관측으로 보내면 거절한다. */
    @AssertTrue(message = "관측 종류가 아닙니다")
    public boolean isEvidenceType() {
        return type != null && type.isEvidence();
    }

    @AssertTrue(message = "이동 시작 시각이 필요합니다")
    public boolean hasMovementStartedAt() {
        return type != SubwayBindingEventType.MOVEMENT_START || movementStartedAt != null;
    }

    @AssertTrue(message = "정차 시각이 필요합니다")
    public boolean hasStationaryAt() {
        return type != SubwayBindingEventType.STATION_STOP_EPISODE || stationaryAt != null;
    }

    /**
     * 다시 움직인 시각이 멈춘 시각보다 앞설 수는 없다.
     *
     * <p>이건 시계 오차가 아니라 <b>말이 안 되는 값</b>이라 거절한다. 시계가 틀어져도 한 기기
     * 안에서의 앞뒤는 지켜진다.
     */
    @AssertTrue(message = "다시 움직인 시각이 멈춘 시각보다 앞섭니다")
    public boolean hasOrderedEpisode() {
        return stationaryAt == null || resumedAt == null || !resumedAt.isBefore(stationaryAt);
    }

    public SubwayBoardingEvidence toEvidence() {
        return new SubwayBoardingEvidence(type, sequence, movementStartedAt, stationaryAt,
                resumedAt, previousActivity, currentActivity, confidence, sustainedSeconds,
                vibrationEvidence, occurredAt);
    }
}
