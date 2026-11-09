package watoo.grd.nextroute.application.journey.dto;

import watoo.grd.nextroute.domain.journey.entity.JourneyEventType;

import java.util.UUID;

/**
 * 여정에 보내는 명령 하나.
 *
 * <p>{@code eventId} 는 앱이 만든다. 지하철에서 응답을 못 받은 앱이 같은 전이를 다시 보내도 두 번
 * 반영되지 않게 하는 열쇠다.
 *
 * <p>{@code baseCommandVersion} 과 {@code routeRevision} 은 "내가 이 상태를 보고 눌렀다"는 진술이다.
 * 그 사이 상태가 바뀌었다면 서버는 명령을 적용하지 않고 최신 상태를 돌려준다 — 사용자가 보고
 * 누른 화면과 서버가 아는 사실이 다르면, 무엇을 의도했는지 서버가 알 수 없기 때문이다.
 *
 * @param eventId            명령 식별자. 재전송 판정의 기준
 * @param type               명령 종류
 * @param baseCommandVersion 앱이 들고 있던 명령 번호
 * @param routeRevision      앱이 들고 있던 경로 개정본 번호
 * @param fromLegIndex       앱이 경계를 통과했다고 판단한 현재 leg
 * @param toLegIndex         다음으로 활성화할 leg. 1차에서는 반드시 from+1
 * @param clientOccurredAt   기기 관측 시각. 감사용이며 서버 상태 순서의 기준으로 쓰지 않는다
 * @param trigger            앱이 어떤 근거로 leg 를 넘겼는지({@link #TRIGGERS} 중 하나, 모르면 null). 기록용이며 상태
 *                           판단에 쓰지 않는다
 */
public record JourneyCommand(
        UUID eventId,
        JourneyEventType type,
        long baseCommandVersion,
        int routeRevision,
        Integer fromLegIndex,
        Integer toLegIndex,
        java.time.Instant clientOccurredAt,
        String trigger
) {

    /** 앱이 보낼 수 있는 근거(bus-boarding-flow D4). 목록 밖 값은 저장하지 않는다. */
    public static final java.util.Set<String> TRIGGERS = java.util.Set.of(
            "WALK_PROGRESS", "STOP_NEAR", "SPEED_ON_LINE", "BUS_DEPARTED_MOVING",
            "MANUAL_LIVE_ACTIVITY", "MANUAL_APP", "MISSED_SAID_BOARDED");

    public JourneyCommand {
        trigger = normalizeTrigger(trigger);
    }

    /** 근거를 보내지 않는 명령(옛 앱, 종료·취소 등). */
    public JourneyCommand(UUID eventId, JourneyEventType type, long baseCommandVersion, int routeRevision,
                          Integer fromLegIndex, Integer toLegIndex, java.time.Instant clientOccurredAt) {
        this(eventId, type, baseCommandVersion, routeRevision, fromLegIndex, toLegIndex, clientOccurredAt, null);
    }

    /** 모르는 값을 거절하지 않는다 — 근거 한 칸 때문에 leg 전환이 실패하면 안 된다. 같은 입력은 늘 같은 값이 된다. */
    static String normalizeTrigger(String raw) {
        return raw != null && TRIGGERS.contains(raw) ? raw : null;
    }
}
