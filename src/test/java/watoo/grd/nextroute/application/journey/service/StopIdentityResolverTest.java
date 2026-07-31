package watoo.grd.nextroute.application.journey.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.journey.service.StopIdentityResolver.StopRef;
import watoo.grd.nextroute.application.journey.service.StopIdentityResolver.Verdict;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 같은 정류장 판별의 우선순위와 "모르겠다"의 경계를 고정한다.
 *
 * <p>이 판별기가 틀리면 걷지 않아도 되는 환승에 걷기 단계가 생기거나, 100m 떨어진 정류장이
 * 같은 곳으로 합쳐진다. 둘 다 화면과 알림에 그대로 드러난다.
 */
class StopIdentityResolverTest {

    private final StopIdentityResolver resolver = new StopIdentityResolver();

    /** 위도 0.0002도 ≈ 22m. 0.001도 ≈ 111m. */
    private static final double BASE_X = 127.0;
    private static final double BASE_Y = 37.5;

    @Test
    @DisplayName("내부 식별자가 있으면 그것만 본다")
    void stopId_wins() {
        StopRef a = new StopRef("ST-1", "99999", "다른이름", BASE_X, BASE_Y);
        StopRef b = new StopRef("ST-1", "11111", "또다른이름", BASE_X + 0.01, BASE_Y + 0.01);

        assertThat(resolver.resolve(a, b))
                .as("이름과 좌표가 어긋나도 식별자가 같으면 같은 정류장이다")
                .isEqualTo(Verdict.SAME);
    }

    @Test
    @DisplayName("내부 식별자가 다르면 다른 정류장")
    void stopId_differs() {
        assertThat(resolver.resolve(
                new StopRef("ST-1", null, "정류장A", BASE_X, BASE_Y),
                new StopRef("ST-2", null, "정류장A", BASE_X, BASE_Y)))
                .isEqualTo(Verdict.DIFFERENT);
    }

    @Test
    @DisplayName("식별자가 없으면 ARS 번호를 본다")
    void arsId_isSecond() {
        assertThat(resolver.resolve(
                new StopRef(null, "12345", "정류장A", null, null),
                new StopRef(null, "12345", "정류장B", null, null)))
                .isEqualTo(Verdict.SAME);
        assertThat(resolver.resolve(
                new StopRef(null, "12345", "정류장A", null, null),
                new StopRef(null, "54321", "정류장A", null, null)))
                .isEqualTo(Verdict.DIFFERENT);
    }

    @Test
    @DisplayName("이름이 같고 30m 안이면 같은 정류장")
    void nameAndCoords_close() {
        assertThat(resolver.resolve(
                new StopRef(null, null, "정류장 A", BASE_X, BASE_Y),
                new StopRef(null, null, "정류장A", BASE_X, BASE_Y + 0.0002)))
                .as("공백 차이는 무시한다")
                .isEqualTo(Verdict.SAME);
    }

    @Test
    @DisplayName("이름이 같아도 멀면 다른 정류장")
    void sameName_farApart() {
        assertThat(resolver.resolve(
                new StopRef(null, null, "시청", BASE_X, BASE_Y),
                new StopRef(null, null, "시청", BASE_X, BASE_Y + 0.001)))
                .isEqualTo(Verdict.DIFFERENT);
    }

    @Test
    @DisplayName("가깝지만 이름이 다르면 판단하지 않는다")
    void closeButDifferentName_unknown() {
        assertThat(resolver.resolve(
                new StopRef(null, null, "정류장A", BASE_X, BASE_Y),
                new StopRef(null, null, "정류장B", BASE_X, BASE_Y + 0.0002)))
                .as("가깝다는 것만으로 같다고 하면 마주 보는 반대편 정류장이 합쳐진다")
                .isEqualTo(Verdict.UNKNOWN);
    }

    @Test
    @DisplayName("근거가 없으면 모르겠다")
    void noEvidence_unknown() {
        assertThat(resolver.resolve(
                new StopRef(null, null, "정류장A", null, null),
                new StopRef(null, null, "정류장A", null, null)))
                .isEqualTo(Verdict.UNKNOWN);
        assertThat(resolver.resolve(null, new StopRef("ST-1", null, null, null, null)))
                .isEqualTo(Verdict.UNKNOWN);
    }

    @Test
    @DisplayName("한쪽에만 식별자가 있으면 그 다음 근거로 넘어간다")
    void partialIdentifier_fallsThrough() {
        assertThat(resolver.resolve(
                new StopRef("ST-1", null, "정류장A", BASE_X, BASE_Y),
                new StopRef(null, null, "정류장A", BASE_X, BASE_Y + 0.0002)))
                .as("한쪽만 있는 식별자로는 비교할 수 없다")
                .isEqualTo(Verdict.SAME);
    }
}
