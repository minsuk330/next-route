package watoo.grd.nextroute.application.subway.dto;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SubwayPositionDtoTest {

    /** 외부 API 가 코드를 하나 늘린다고 수집이 죽으면 안 된다. */
    @Test
    void unknownCodesDegradeInsteadOfThrowing() {
        assertThat(SubwayTrainStatus.from("9")).isEqualTo(SubwayTrainStatus.UNKNOWN);
        assertThat(SubwayTrainStatus.from(null)).isEqualTo(SubwayTrainStatus.UNKNOWN);
        assertThat(SubwayExpressType.from("5")).isEqualTo(SubwayExpressType.UNKNOWN);
        assertThat(SubwayExpressType.from(null)).isEqualTo(SubwayExpressType.UNKNOWN);
    }

    @Test
    void mapsDocumentedStatusCodes() {
        assertThat(SubwayTrainStatus.from("0")).isEqualTo(SubwayTrainStatus.ENTERING);
        assertThat(SubwayTrainStatus.from("1")).isEqualTo(SubwayTrainStatus.ARRIVED);
        assertThat(SubwayTrainStatus.from("2")).isEqualTo(SubwayTrainStatus.DEPARTED);
        assertThat(SubwayTrainStatus.from("3")).isEqualTo(SubwayTrainStatus.LEFT_PREVIOUS);
    }

    /** 출발 전이의 선행 근거가 되는 상태는 "그 역에 있다"는 둘뿐이다. */
    @Test
    void onlyEnteringAndArrivedCountAsBeingAtTheStation() {
        assertThat(SubwayTrainStatus.ENTERING.isAtStation()).isTrue();
        assertThat(SubwayTrainStatus.ARRIVED.isAtStation()).isTrue();
        assertThat(SubwayTrainStatus.DEPARTED.isAtStation()).isFalse();
        assertThat(SubwayTrainStatus.LEFT_PREVIOUS.isAtStation()).isFalse();
        assertThat(SubwayTrainStatus.UNKNOWN.isAtStation()).isFalse();
    }

    /** 방향을 해석하지 못한 행은 추적에 쓰지 않는다 — 반대 방향 열차를 집는 사고를 막는다. */
    @Test
    void rowWithoutDirectionIsNotTrackable() {
        assertThat(position("1002", "상행").isTrackable()).isTrue();
        assertThat(position("1002", null).isTrackable()).isFalse();
        assertThat(position(null, "상행").isTrackable()).isFalse();
    }

    /**
     * 빈 목록과 호출 실패가 구분된다.
     *
     * <p>둘을 섞으면 "새벽이라 열차가 없다"와 "API 장애"가 같은 모양이 되고, 전이 추적기는
     * 멀쩡한 열차가 통째로 사라졌다고 읽는다.
     */
    @Test
    void emptySuccessIsDistinguishableFromFailure() {
        SubwayPositionFetchResult empty = new SubwayPositionFetchResult(
                "1002", List.of(), 0, SubwayPositionFetchResult.Status.COMPLETE, Instant.now(), null);
        SubwayPositionFetchResult failed =
                SubwayPositionFetchResult.failed("1002", Instant.now(), "INFO-200");

        assertThat(empty.rows()).isEmpty();
        assertThat(empty.isComplete()).isTrue();
        assertThat(failed.rows()).isEmpty();
        assertThat(failed.isComplete()).isFalse();
    }

    private SubwayPositionInfo position(String lineId, String direction) {
        return new SubwayPositionInfo(lineId, "1234", "1002000228", "강남", direction,
                "1002000201", "시청", SubwayTrainStatus.ARRIVED, SubwayExpressType.NORMAL,
                "2026-08-13 09:00:00", "2026-08-13");
    }
}
