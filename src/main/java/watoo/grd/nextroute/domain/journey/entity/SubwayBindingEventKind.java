package watoo.grd.nextroute.domain.journey.entity;

/**
 * 감사 행이 관측인지 판단인지. 값 목록은 V45 의
 * {@code subway_binding_event_kind_check} 가 소유한다.
 *
 * <p>관측 순번({@code evidence_sequence})의 유일성이 이 값과 이름별로 걸린다 — 이동 시작과
 * 정차가 각각 1 부터 세기 때문이다.
 */
public enum SubwayBindingEventKind {

    /** 앱이 보낸 것. 같은 순번이 다시 오면 조용히 무시한다. */
    EVIDENCE,

    /** 서버가 정한 것. 순번이 없다. */
    DECISION
}
