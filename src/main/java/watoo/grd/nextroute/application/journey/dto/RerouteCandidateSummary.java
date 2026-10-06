package watoo.grd.nextroute.application.journey.dto;

import watoo.grd.nextroute.domain.journey.entity.RerouteProposalKind;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * API 로 나가는 후보. <b>{@code apply} 가 없다.</b>
 *
 * <p>후보 하나에 경로 원본이 통째로 들어가고 후보는 최대 3개다. 그대로 응답에 실으면 수십 KB
 * JSON 이 재탐색 화면을 열 때마다 이동통신망으로 나간다 — 지하철에서 초조한 사용자에게 가장
 * 나쁜 크기다. 게다가 앱은 그 값을 쓰지 않는다. 앱에 필요한 것은 "무엇을 고를지"
 * ({@code candidateId})와 "어떻게 보일지"({@code display})뿐이다.
 *
 * <p>변환을 {@link #from} 한 자리로 모은다. 컨트롤러에서 필드를 골라 담게 하면 나중에 필드가
 * 하나 늘 때 {@code apply} 가 실수로 새어 나간다.
 */
public record RerouteCandidateSummary(
        String candidateId,
        RerouteProposalKind kind,
        RerouteCandidateDisplay display,
        @JsonInclude(JsonInclude.Include.NON_NULL) String originType) {

    public static RerouteCandidateSummary from(RerouteCandidate candidate) {
        return new RerouteCandidateSummary(
                candidate.candidateId(), candidate.kind(), candidate.display(), null);
    }

    public static List<RerouteCandidateSummary> from(List<RerouteCandidate> candidates) {
        return candidates.stream().map(RerouteCandidateSummary::from).toList();
    }

    /**
     * 보드(#264) 후보. 기점 종류를 앱이 쓰는 이름으로 싣는다 — 앱은 이 값으로 말풍선을 하차 정류장에
     * 놓을지(RIDE_STOP·PLANNED_ALIGHT), 내 위치·기다리는 정류장에 놓을지(CURRENT_POSITION·WAITING_STOP)
     * 고른다. 사건 기반(v1) 후보는 이 칸이 없다(응답 모양 불변).
     */
    public static List<RerouteCandidateSummary> from(List<RerouteCandidate> candidates,
                                                     Map<String, RerouteOriginKind> originKinds) {
        return candidates.stream()
                .map(candidate -> new RerouteCandidateSummary(candidate.candidateId(), candidate.kind(),
                        candidate.display(), originType(originKinds.get(candidate.candidateId()))))
                .toList();
    }

    /** 내부 기점 종류 → 앱 계약 이름. */
    public static String originType(RerouteOriginKind kind) {
        if (kind == null) {
            return null;
        }
        return switch (kind) {
            case TRANSFER_WAIT -> "PLANNED_ALIGHT";
            case RIDE_INTERIOR -> "RIDE_STOP";
            default -> kind.name();
        };
    }
}
