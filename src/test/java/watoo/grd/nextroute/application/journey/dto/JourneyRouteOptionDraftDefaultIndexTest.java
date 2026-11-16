package watoo.grd.nextroute.application.journey.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.route.dto.TransferArrival;
import watoo.grd.nextroute.application.route.dto.TransferArrival.Source;
import watoo.grd.nextroute.application.route.dto.TransferArrival.Status;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("#614 여정 시작 기본 선택 = 걸어서 닿은 뒤 가장 먼저 오는 lane")
class JourneyRouteOptionDraftDefaultIndexTest {

    /** 검색 시각 T, 도보 5분 → 정류장 도착 T+5분. */
    private static final Instant T = Instant.parse("2026-11-17T00:30:00Z");
    private static final Instant AT_STOP = T.plusSeconds(300);

    private static TransferArrival arrival(int lane, Status status, Instant predicted) {
        return new TransferArrival("R" + lane, lane, Source.REALTIME, status, T, AT_STOP, predicted,
                predicted == null ? null : predicted.getEpochSecond() - AT_STOP.getEpochSecond(),
                null, null, null, false);
    }

    private static JourneyRouteOptionDraft option(int index, String name, TransferArrival arrival) {
        return JourneyRouteOptionDraft.of(index, "BL-" + name, name, null, null).withSearch(arrival, null);
    }

    @Test
    @DisplayName("journey 516 모양: 402(대표) 19분, 741 7분, 140 3분 → 140")
    void earliestLaneWins() {
        List<JourneyRouteOptionDraft> options = List.of(
                option(0, "402", arrival(0, Status.AVAILABLE, AT_STOP.plusSeconds(19 * 60))),
                option(1, "741", arrival(1, Status.AVAILABLE, AT_STOP.plusSeconds(7 * 60))),
                option(2, "140", arrival(2, Status.ARRIVAL_TIME_APPROXIMATE, AT_STOP.plusSeconds(3 * 60))));

        assertThat(JourneyRouteOptionDraft.defaultIndex(options, T)).isEqualTo(2);
    }

    @Test
    @DisplayName("도보 도달 경계: 검색 3분 뒤 시작하면 정류장 도착은 T+8분 — T+6분 140 은 못 타고 T+10분 741")
    void startedLateExcludesBusThatPassesBeforeUserReachesStop() {
        List<JourneyRouteOptionDraft> options = List.of(
                option(0, "402", arrival(0, Status.AVAILABLE, T.plusSeconds(19 * 60))),
                option(1, "741", arrival(1, Status.AVAILABLE, T.plusSeconds(10 * 60))),
                option(2, "140", arrival(2, Status.AVAILABLE, T.plusSeconds(6 * 60))));

        assertThat(JourneyRouteOptionDraft.defaultIndex(options, T)).isEqualTo(2);              // 바로 시작하면 140
        assertThat(JourneyRouteOptionDraft.defaultIndex(options, T.plusSeconds(180))).isEqualTo(1);   // 3분 뒤면 741
        assertThat(JourneyRouteOptionDraft.defaultIndex(options, T.plusSeconds(300))).isEqualTo(1);   // 정류장 T+10 = 741 도착 시각, 탄다
        assertThat(JourneyRouteOptionDraft.defaultIndex(options, T.plusSeconds(301))).isZero();       // 1초 늦으면 741 놓침 → 402(대표)
    }

    @Test
    @DisplayName("탈 수 없는 상태·예측 없음은 빼고, 전부 빠지면 첫 후보(대표)")
    void unusableFallsBackToFirst() {
        List<JourneyRouteOptionDraft> options = List.of(
                option(0, "402", arrival(0, Status.ARRIVAL_ONLY, AT_STOP.plusSeconds(60))),   // 도착만 앎 — 검색도 안 씀
                option(1, "741", arrival(1, Status.DISABLED, null)),
                option(2, "140", null));

        assertThat(JourneyRouteOptionDraft.defaultIndex(options, T)).isZero();
        assertThat(JourneyRouteOptionDraft.defaultIndex(List.of(), T)).isZero();
    }

    @Test
    @DisplayName("전부 이미 지나갔으면 첫 후보, 동률은 앞 순번")
    void allPassedOrTie() {
        List<JourneyRouteOptionDraft> passed = List.of(
                option(0, "402", arrival(0, Status.AVAILABLE, T.plusSeconds(400))),
                option(1, "741", arrival(1, Status.AVAILABLE, T.plusSeconds(350))));
        assertThat(JourneyRouteOptionDraft.defaultIndex(passed, T.plusSeconds(600))).isZero();

        Instant same = AT_STOP.plusSeconds(120);
        List<JourneyRouteOptionDraft> tie = List.of(
                option(0, "402", arrival(0, Status.AVAILABLE, AT_STOP.plusSeconds(900))),
                option(1, "741", arrival(1, Status.AVAILABLE, same)),
                option(2, "140", arrival(2, Status.AVAILABLE, same)));
        assertThat(JourneyRouteOptionDraft.defaultIndex(tie, T)).isEqualTo(1);
    }

    @Test
    @DisplayName("TransferArrival: 조건부(앞 버스 가정) 값은 isUnconditional=false, 정류장 도착을 모르면 시작 시각 기준")
    void transferArrivalRules() {
        TransferArrival conditional = new TransferArrival("R", 0, Source.REALTIME, Status.AVAILABLE, T, AT_STOP,
                AT_STOP.plusSeconds(60), 60L, null, null, 1, true);
        assertThat(conditional.isUnconditional()).isFalse();
        assertThat(arrival(0, Status.AVAILABLE, AT_STOP).isUnconditional()).isTrue();

        TransferArrival noUserAt = new TransferArrival("R", 0, Source.MODEL, Status.AVAILABLE, T, null,
                T.plusSeconds(60), null, null, null, null, null);
        assertThat(noUserAt.reachableIfStartedAt(T.plusSeconds(30))).isTrue();
        assertThat(noUserAt.reachableIfStartedAt(T.plusSeconds(90))).isFalse();
        // 검색 시각보다 이른 시작(시계 차이)은 늦음 0
        assertThat(arrival(0, Status.AVAILABLE, AT_STOP).reachableIfStartedAt(T.minusSeconds(5))).isTrue();
    }

    @Test
    @DisplayName("저장 안 하는 칸은 JSON 에 안 나가고, resolved() 뒤에도 남는다")
    void transientFields() throws Exception {
        JourneyRouteOptionDraft draft = JourneyRouteOptionDraft.of(0, "BL-140", "140", null, "#3D5BAB")
                .withSearch(arrival(0, Status.AVAILABLE, AT_STOP), 605);
        assertThat(draft.resolved("R-140", 3, 9).rideSeconds()).isEqualTo(605);
        assertThat(draft.resolved("R-140", 3, 9).searchArrival()).isNotNull();
        var json = new com.fasterxml.jackson.databind.ObjectMapper()
                .findAndRegisterModules().writeValueAsString(draft);
        assertThat(json).doesNotContain("searchArrival").doesNotContain("rideSeconds");
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()
                .writeValueAsString(arrival(0, Status.AVAILABLE, AT_STOP)))
                .doesNotContain("boardable").doesNotContain("unconditional");
    }
}
