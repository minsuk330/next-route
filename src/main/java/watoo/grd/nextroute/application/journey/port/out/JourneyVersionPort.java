package watoo.grd.nextroute.application.journey.port.out;

import java.util.Optional;

/**
 * 조건부 조회(#415-6)가 "바뀐 것 없음(204)"을 가르는 데 필요한 값만 읽는다.
 *
 * <p>소유자로 거르지 않고 여정 번호로만 읽는다 — 캐시가 여정마다 한 칸이라, 남의 여정을 물어도 같은 칸을 보고
 * 소유자 비교로 걸러야 한다. 소유자 비교는 부르는 쪽({@code JourneyQueryService})이 한다.
 */
public interface JourneyVersionPort {

    /** @return 지워졌거나 없으면 비어 있다 */
    Optional<Entry> find(long journeyId);

    /**
     * @param currentLegType   지금 단계 종류({@code JourneyLegType} 이름). 없으면 null
     * @param changedAtEpochMs 마지막 버전 변경이 커밋된 시각(캐시 덮어쓰기가 적는다). 모르면 null — DB 에는 이 값이
     *                         없어, 캐시가 비어 DB 에서 채운 칸은 언제나 null 이다(지어내지 않는다)
     */
    record Entry(long userId, long commandVersion, long evaluationVersion, String currentLegType,
                 Long changedAtEpochMs) {

        public boolean subwayLeg() {
            return currentLegType != null && currentLegType.endsWith("SUBWAY");
        }

        public Entry withChangedAt(Long epochMs) {
            return new Entry(userId, commandVersion, evaluationVersion, currentLegType, epochMs);
        }
    }
}
