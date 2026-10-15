package watoo.grd.nextroute.application.journey.port.in;

import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;

import java.util.Optional;

/** 사용자의 진행 중 여정 또는 특정 여정을 조회한다. */
public interface JourneyQueryUseCase {
    Optional<JourneySnapshot> getActive(Long userId);

    Optional<JourneySnapshot> get(Long userId, Long journeyId);

    /**
     * 조건부 조회가 "바뀐 것 없음"인지 가르는 데 필요한 것만(#415-6). 소유자가 아니거나 없으면 비어 있다.
     */
    default Optional<JourneyVersions> versions(Long userId, Long journeyId) {
        return get(userId, journeyId).map(s -> new JourneyVersions(s.commandVersion(), s.evaluationVersion(),
                s.currentLeg() != null && s.currentLeg().type() != null
                        && (s.currentLeg().type().name().contains("SUBWAY"))));
    }

    /**
     * @param subwayLeg 지금 단계가 지하철(대기·탑승)인지. 지하철이면 링 버전이 따로 있어 버전 두 개만으로는 가를 수 없다
     * @param currentLegType 지금 단계 종류. 전달 지연 지표의 태그(대기·탑승·도보는 앱 조회 간격이 다르다)
     * @param changedAtEpochMs 마지막 버전 변경 커밋 시각. 모르면 null(전달 지연을 재지 않는다)
     */
    record JourneyVersions(long commandVersion, long evaluationVersion, boolean subwayLeg,
                           String currentLegType, Long changedAtEpochMs) {

        public JourneyVersions(long commandVersion, long evaluationVersion, boolean subwayLeg) {
            this(commandVersion, evaluationVersion, subwayLeg, null, null);
        }

        /**
         * 앱이 들고 있는 번호와 같으면 true. 링 번호를 물었는데 지하철 단계면 여기서 가를 수 없어 false — 전체
         * 조립 경로가 링까지 읽어 판단한다.
         */
        public boolean unchanged(Long afterCommand, Long afterEvaluation, Long afterGuidance) {
            if (afterCommand == null && afterEvaluation == null && afterGuidance == null) {
                return false;
            }
            if (afterGuidance != null && subwayLeg) {
                return false;
            }
            boolean commandSame = afterCommand == null || commandVersion <= afterCommand;
            boolean evaluationSame = afterEvaluation == null || evaluationVersion <= afterEvaluation;
            return commandSame && evaluationSame;
        }
    }
}
