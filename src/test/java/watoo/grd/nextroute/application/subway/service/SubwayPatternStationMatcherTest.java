package watoo.grd.nextroute.application.subway.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.subway.service.SubwayPatternStationMatcher.Candidate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SubwayPatternStationMatcherTest {

    // 운영 역 마스터 좌표(2026-10-07)
    private static final Candidate DOBONG = new Candidate("1001000127", "도봉", 37.6792119, 127.0455349);
    private static final Candidate DOBONGSAN = new Candidate("1001000126", "도봉산", 37.6895117, 127.0460627);
    private static final Candidate BULAMSAN = new Candidate("1004000409", "불암산", 37.6702177, 127.0790666);

    @Test
    @DisplayName("이름이 같으면 좌표가 멀어도 그 역 — OTP 는 도봉·도봉산 좌표가 서로 바뀌어 있다")
    void sameNameWinsOverSwappedCoordinates() {
        // OTP '도봉' 좌표는 실제 도봉산 자리다
        assertThat(SubwayPatternStationMatcher.match("도봉", 37.68959, 127.046059, List.of(DOBONG, DOBONGSAN)))
                .contains(DOBONG);
        assertThat(SubwayPatternStationMatcher.match("도봉산(1호선)", 37.679058, 127.045494, List.of(DOBONG, DOBONGSAN)))
                .contains(DOBONGSAN);
    }

    @Test
    @DisplayName("이름이 다르면 가까운 역(150m)으로 대체한다 — 개명(당고개→불암산)")
    void fallsBackToNearestForRenamedStation() {
        assertThat(SubwayPatternStationMatcher.match("당고개", 37.67027, 127.07913, List.of(BULAMSAN)))
                .contains(BULAMSAN);
    }

    @Test
    @DisplayName("이름도 다르고 150m 밖이면 잇지 않는다 — 틀린 역에 붙느니 비운다")
    void leavesUnmatchedWhenNothingFits() {
        assertThat(SubwayPatternStationMatcher.match("진접", 37.7200, 127.2000, List.of(BULAMSAN))).isEmpty();
    }

    @Test
    @DisplayName("이름이 같아도 5km 밖이면 데이터 사고로 보고 잇지 않는다")
    void sameNameTooFarIsRejected() {
        Candidate yangpyeongLine5 = new Candidate("1005000525", "양평", 37.5256, 126.8864);

        assertThat(SubwayPatternStationMatcher.match("양평", 37.4927, 127.4922, List.of(yangpyeongLine5))).isEmpty();
    }

    @Test
    void displayNameDropsLineSuffix() {
        assertThat(SubwayPatternStationMatcher.displayName("강남(2호선)")).isEqualTo("강남");
    }
}
