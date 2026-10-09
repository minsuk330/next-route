package watoo.grd.nextroute.domain.journey.entity;

/**
 * 감사 행 하나가 무엇을 적은 것인지. 값 목록은 V45 의
 * {@code subway_binding_event_type_check} 가 소유한다.
 *
 * <p>관측({@code EVIDENCE})과 판단({@code DECISION})이 한 표에 섞인다. 나누지 않는 이유는
 * 시간순으로 나란히 읽어야 "무엇을 보고 무엇을 정했나"가 보이기 때문이다. 두 표로 쪼개면
 * 그 순서를 되살리려고 매번 합쳐야 한다.
 */
public enum SubwayBindingEventType {

    // ── 앱이 보낸 관측 ──

    /** 차량 이동이 시작됐다. */
    MOVEMENT_START,

    /** 움직이다 잠깐 멈췄다가 다시 움직였다 — 역에 섰다는 뜻일 수 있다. */
    STATION_STOP_EPISODE,

    // ── 서버가 내린 판단 ──

    PENDING,
    PROVISIONAL,
    CONFIRMED,

    /** 잠정으로 골랐던 열차가 아니라 다른 후보였다. 상태는 {@code CONFIRMED} 로 끝난다. */
    CORRECTED,

    ABANDONED,

    /** 사용자가 다른 열차를 탔다고 알렸다. */
    USER_RESET,

    /** 사용자가 타지 않았다고 알렸다. */
    USER_NOT_BOARDED,

    /** 재탐색으로 개정본이 올랐지만 구간이 그대로여서 이어받았다. */
    INHERITED_ON_REROUTE,

    /** 역에서 사용자가 열차를 골랐다(#265, V60). 판단 종류다. */
    USER_SELECTED;

    public boolean isEvidence() {
        return this == MOVEMENT_START || this == STATION_STOP_EPISODE;
    }

    public SubwayBindingEventKind kind() {
        return isEvidence() ? SubwayBindingEventKind.EVIDENCE : SubwayBindingEventKind.DECISION;
    }
}
