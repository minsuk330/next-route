package watoo.grd.nextroute.application.journey.dto;

import watoo.grd.nextroute.domain.journey.entity.SubwayBindingConfidence;
import watoo.grd.nextroute.domain.journey.entity.SubwayBindingEventType;

import java.time.Instant;

/**
 * 앱이 보낸 탑승 관측 하나.
 *
 * <p><b>열차번호가 없다.</b> 앱은 자기가 어느 열차에 탔는지 모르고, 안다고 주장해도 서버가
 * 검증할 방법이 없다. 앱은 "움직이기 시작했다"·"잠깐 섰다 다시 갔다"까지만 말하고,
 * 그것을 실제 열차와 잇는 일은 서버가 한다.
 *
 * <p>정차를 <b>한 시각이 아니라 구간</b>으로 받는다. 신호 대기, 터널 서행, 폰을 내려놓은 것도
 * 순간적으로는 "멈춤"으로 보인다. 멈췄다가 <b>다시 움직인 것</b>까지 봐야 역에 섰다고 말할 수 있다.
 *
 * @param type              {@code MOVEMENT_START} 또는 {@code STATION_STOP_EPISODE}
 * @param sequence          종류별 순번. 같은 값이 다시 오면 조용히 무시한다
 * @param movementStartedAt 차량 이동이 시작된 시각. 이동 시작 관측에만 있다
 * @param stationaryAt      멈춘 시각. 정차 관측에만 있다
 * @param resumedAt         다시 움직인 시각. 아직 모르면 null — 뒤따르는 관측이 채운다
 * @param previousActivity  직전에 무엇을 하고 있었는지(서 있었다·걷고 있었다)
 * @param currentActivity   지금 무엇으로 분류됐는지
 * @param confidence        기기가 자기 분류를 얼마나 믿는지
 * @param sustainedSeconds  그 상태가 얼마나 이어졌는지
 * @param vibrationEvidence 차량 진동으로 볼 만한 흔들림이 있었는지
 * @param occurredAt        기기 시각. <b>믿지 않는다</b> — 순서와 보관은 서버 시각을 따른다
 */
public record SubwayBoardingEvidence(
        SubwayBindingEventType type,
        Integer sequence,
        Instant movementStartedAt,
        Instant stationaryAt,
        Instant resumedAt,
        String previousActivity,
        String currentActivity,
        SubwayBindingConfidence confidence,
        Integer sustainedSeconds,
        Boolean vibrationEvidence,
        Instant occurredAt
) {

    public boolean isMovementStart() {
        return type == SubwayBindingEventType.MOVEMENT_START;
    }
}
