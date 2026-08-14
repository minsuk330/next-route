package watoo.grd.nextroute.application.journey.dto;

import java.util.List;

/**
 * 판정 한 번의 결과. <b>아무것도 하지 않는 것도 결과다</b> — 그게 기본값이다.
 *
 * <p>{@link Kind#NONE} 에 {@code reason} 을 붙이는 이유: "왜 고정하지 않았나"가 이 기능의 주된
 * 질문이다. 로그·지표가 이 문자열을 세면 관문 숫자를 실측으로 조일 수 있다. 사유 없이 세면
 * 몇 번 못 골랐는지는 알아도 어느 관문이 막았는지는 영영 모른다.
 */
public record SubwayBindingDecision(
        Kind kind,
        SubwayBindingCandidate chosen,
        List<SubwayBindingCandidate> candidates,
        String reason
) {

    public enum Kind {
        /** 이번 tick 에는 아무것도 바꾸지 않는다. */
        NONE,
        PROVISIONAL,
        CONFIRMED,
        /** 잠정으로 골랐던 열차가 아니라 다른 후보였다. 상태는 {@code CONFIRMED} 로 끝난다. */
        CORRECTED,
        ABANDON
    }

    public static SubwayBindingDecision none(String reason) {
        return new SubwayBindingDecision(Kind.NONE, null, List.of(), reason);
    }

    public static SubwayBindingDecision none(String reason,
                                             List<SubwayBindingCandidate> candidates) {
        return new SubwayBindingDecision(Kind.NONE, null, List.copyOf(candidates), reason);
    }

    public static SubwayBindingDecision provisional(SubwayBindingCandidate chosen,
                                                    List<SubwayBindingCandidate> candidates,
                                                    String reason) {
        return new SubwayBindingDecision(Kind.PROVISIONAL, chosen, List.copyOf(candidates), reason);
    }

    public static SubwayBindingDecision confirmed(SubwayBindingCandidate chosen,
                                                  List<SubwayBindingCandidate> candidates,
                                                  String reason) {
        return new SubwayBindingDecision(Kind.CONFIRMED, chosen, List.copyOf(candidates), reason);
    }

    /** 잠정으로 본 열차와 다른 후보로 확정한다. 상태는 {@code CONFIRMED}, 감사행만 갈린다. */
    public static SubwayBindingDecision corrected(SubwayBindingCandidate chosen,
                                                  List<SubwayBindingCandidate> candidates,
                                                  String reason) {
        return new SubwayBindingDecision(Kind.CORRECTED, chosen, List.copyOf(candidates), reason);
    }

    public static SubwayBindingDecision abandon(String reason) {
        return new SubwayBindingDecision(Kind.ABANDON, null, List.of(), reason);
    }

    /** 고정 행을 실제로 고치는 판정인지. {@code NONE} 만 아무 쓰기도 만들지 않는다. */
    public boolean hasWrite() {
        return kind != Kind.NONE;
    }

    /** 교정도 확정으로 끝난다 — 지속 상태에 {@code CORRECTED} 는 없다. */
    public boolean isConfirming() {
        return kind == Kind.CONFIRMED || kind == Kind.CORRECTED;
    }
}
