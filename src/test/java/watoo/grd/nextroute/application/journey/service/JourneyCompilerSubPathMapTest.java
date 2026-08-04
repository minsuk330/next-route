package watoo.grd.nextroute.application.journey.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.journey.dto.JourneyLegDraft;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 단계 → 원본 구간 순번 되찾기.
 *
 * <p>좌표는 원본 구간이 들고 있고 저장된 단계에는 그 순번이 없다. 좌표를 찾을 때마다 같은
 * 쪼개기를 한 번 더 돌려 순번을 받는데, 그 매핑이 맞는지를 여기서 못 박는다.
 */
@DisplayName("쪼개기가 단계별 원본 구간 순번을 알려준다")
class JourneyCompilerSubPathMapTest {

    private final JourneyCompiler compiler = new JourneyCompiler(new StopIdentityResolver());

    @Test
    @DisplayName("걷기·기다리기·타고가기가 각각 원본 구간을 가리킨다")
    void mapsEveryLegToItsSubPath() {
        PathResult path = path(
                SubPathFixture.walk(5).names("집", "정류장").coords(127.0, 37.5, 127.001, 37.5).build(),
                SubPathFixture.bus(20).names("정류장", "회사앞")
                        .coords(127.001, 37.5, 127.02, 37.5)
                        .busStops("100", "200")
                        .lanes(SubPathFixture.busLane("370", "BL370"))
                        .build(),
                SubPathFixture.walk(3).names("회사앞", "회사").coords(127.02, 37.5, 127.021, 37.5).build());

        JourneyCompiler.Compiled compiled = compiler.compileDetailed(path);
        List<JourneyLegDraft> legs = compiled.plan().legs();

        // 걷기 1 + (기다리기 + 타고가기) + 걷기 1 = 4단계
        assertThat(legs).extracting(JourneyLegDraft::type).containsExactly(
                JourneyLegType.WALK,
                JourneyLegType.WAIT_BUS,
                JourneyLegType.RIDE_BUS,
                JourneyLegType.WALK);

        assertThat(compiled.subPathIndexOf(0)).isZero();
        // 기다리기와 타고가기는 같은 원본 구간에서 나왔다.
        assertThat(compiled.subPathIndexOf(1)).isEqualTo(1);
        assertThat(compiled.subPathIndexOf(2)).isEqualTo(1);
        assertThat(compiled.subPathIndexOf(3)).isEqualTo(2);
    }

    @Test
    @DisplayName("범위를 벗어난 단계 순번은 null이다")
    void returnsNullOutsideRange() {
        JourneyCompiler.Compiled compiled = compiler.compileDetailed(path(
                SubPathFixture.walk(5).names("집", "역").coords(127.0, 37.5, 127.001, 37.5).build()));

        assertThat(compiled.subPathIndexOf(-1)).isNull();
        assertThat(compiled.subPathIndexOf(99)).isNull();
    }

    @Test
    @DisplayName("빈 경로는 매핑도 비어 있다")
    void emptyPathHasEmptyMap() {
        JourneyCompiler.Compiled compiled = compiler.compileDetailed(
                new PathResult(1, null, List.of(), List.of()));

        assertThat(compiled.plan().isEmpty()).isTrue();
        assertThat(compiled.subPathIndexByLegIndex()).isEmpty();
    }

    private PathResult path(watoo.grd.nextroute.application.route.dto.SubPathResult... subPaths) {
        return new PathResult(1, null, List.of(subPaths), List.of());
    }
}
