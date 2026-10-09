package watoo.grd.nextroute.application.journey.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.route.port.out.TransitPatternPort.TransitStop;
import watoo.grd.nextroute.application.route.routing.RoutingMode;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("피드별로 쪼개진 같은 정류장")
class TrackStopSiblingsTest {

    @Test
    @DisplayName("수도권 세 피드: 나머지 두 피드의 같은 뒤 숫자가 후보")
    void candidates() {
        assertThat(TrackStopSiblings.candidates("1:BS_TAGO_SEB121000009"))
                .containsExactly("1:BS_TAGO_GGB121000009", "1:BS_TAGO_ICB121000009");
        assertThat(TrackStopSiblings.candidates("1:BS_TAGO_GGB121000009"))
                .containsExactly("1:BS_TAGO_SEB121000009", "1:BS_TAGO_ICB121000009");
    }

    @Test
    @DisplayName("지방 피드·지하철·null 은 후보 없음 — 지방은 뒤 숫자가 같아도 딴 정류장이다(ADB/TSB 354000001)")
    void noCandidatesOutsideMetro() {
        assertThat(TrackStopSiblings.candidates("1:BS_TAGO_ADB354000001")).isEmpty();
        assertThat(TrackStopSiblings.candidates("1:RS_ACC1_S-1-0222")).isEmpty();
        assertThat(TrackStopSiblings.candidates(null)).isEmpty();
        assertThat(TrackStopSiblings.groupKey("1:BS_TAGO_TSB354000001")).isNull();
    }

    @Test
    @DisplayName("묶음 키는 피드만 다르면 같다, 대표 순위는 서울 → 경기 → 인천 → 그 밖")
    void groupKeyAndRank() {
        assertThat(TrackStopSiblings.groupKey("1:BS_TAGO_SEB121000009"))
                .isEqualTo(TrackStopSiblings.groupKey("1:BS_TAGO_ICB121000009"));
        assertThat(TrackStopSiblings.representativeRank("1:BS_TAGO_SEB1")).isZero();
        assertThat(TrackStopSiblings.representativeRank("1:BS_TAGO_GGB1")).isEqualTo(1);
        assertThat(TrackStopSiblings.representativeRank("1:BS_TAGO_ICB1")).isEqualTo(2);
        assertThat(TrackStopSiblings.representativeRank("1:BS_TAGO_ADB1")).isEqualTo(3);
    }

    @Test
    @DisplayName("300m 안만 형제 — 실측 1,346m 떨어진 딴 정류장(104000010)은 뺀다. 자기 자신도 뺀다")
    void within() {
        TransitStop origin = new TransitStop("1:BS_TAGO_SEB104000010", "메리놀외방선교회", 37.5490, 127.0740,
                RoutingMode.BUS);
        TransitStop near = new TransitStop("1:BS_TAGO_ICB104000010", "x", 37.5491, 127.0741, RoutingMode.BUS);
        TransitStop far = new TransitStop("1:BS_TAGO_GGB104000010", "어린이대공원역2번출구", 37.5611, 127.0740,
                RoutingMode.BUS);

        assertThat(TrackStopSiblings.within(origin, List.of(origin, near, far)))
                .containsExactly("1:BS_TAGO_ICB104000010");
    }
}
