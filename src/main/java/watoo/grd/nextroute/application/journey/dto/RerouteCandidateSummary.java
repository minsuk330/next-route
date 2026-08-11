package watoo.grd.nextroute.application.journey.dto;

import watoo.grd.nextroute.domain.journey.entity.RerouteProposalKind;

import java.util.List;

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
        RerouteCandidateDisplay display) {

    public static RerouteCandidateSummary from(RerouteCandidate candidate) {
        return new RerouteCandidateSummary(
                candidate.candidateId(), candidate.kind(), candidate.display());
    }

    public static List<RerouteCandidateSummary> from(List<RerouteCandidate> candidates) {
        return candidates.stream().map(RerouteCandidateSummary::from).toList();
    }
}
