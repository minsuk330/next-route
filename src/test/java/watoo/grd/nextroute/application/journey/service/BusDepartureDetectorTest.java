package watoo.grd.nextroute.application.journey.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.bus.port.out.BusPositionPort.Outcome;
import watoo.grd.nextroute.application.bus.port.out.BusPositionPort.Snapshot;
import watoo.grd.nextroute.application.bus.port.out.BusPositionPort.Vehicle;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 원천별 출발 사건 추출. 승차 정류장 순번 10. */
@DisplayName("승차 정류장 출발 사건")
class BusDepartureDetectorTest {

    private static final Instant T1 = Instant.parse("2026-10-07T03:00:00Z");
    private static final Instant T2 = T1.plusSeconds(15);

    @Test
    @DisplayName("서울: 승차 정류장 도착(stopFlag 1) → 운행(0) 이면 출발, 시각 = 지금 관측")
    void seoulStopFlag() {
        var found = BusDepartureDetector.departures(snap(T1, new Vehicle("V1", "서울1", 10, false)),
                snap(T2, new Vehicle("V1", "서울1", 10, true)), 10, 3);

        assertThat(found).singleElement().satisfies(d -> {
            assertThat(d.vehicleId()).isEqualTo("V1");
            assertThat(d.departedAt()).isEqualTo(T2);
        });
    }

    @Test
    @DisplayName("경기: stateCd 2(정류소 출발)도, 순번 증가도 출발")
    void gyeonggi() {
        assertThat(BusDepartureDetector.departures(snap(T1, new Vehicle("G1", null, 9, false)),
                snap(T2, new Vehicle("G1", null, 10, true)), 10, 3)).hasSize(1);
        assertThat(BusDepartureDetector.departures(snap(T1, new Vehicle("G1", null, 10, false)),
                snap(T2, new Vehicle("G1", null, 11, false)), 10, 3)).hasSize(1);
    }

    @Test
    @DisplayName("TAGO: 상태가 없어 순번이 승차를 넘을 때만")
    void tago() {
        assertThat(BusDepartureDetector.departures(snap(T1, new Vehicle("대전75자1", "대전75자1", 10, false)),
                snap(T2, new Vehicle("대전75자1", "대전75자1", 10, false)), 10, 3)).isEmpty();
        assertThat(BusDepartureDetector.departures(snap(T1, new Vehicle("대전75자1", "대전75자1", 10, false)),
                snap(T2, new Vehicle("대전75자1", "대전75자1", 11, false)), 10, 3)).hasSize(1);
    }

    @Test
    @DisplayName("처음 본 차·이미 지난 차·멀리서 건너뛴 차는 사건이 아니다")
    void ignored() {
        assertThat(BusDepartureDetector.departures(snap(T1),
                snap(T2, new Vehicle("V1", null, 11, false)), 10, 3)).isEmpty();
        assertThat(BusDepartureDetector.departures(snap(T1, new Vehicle("V1", null, 11, false)),
                snap(T2, new Vehicle("V1", null, 12, false)), 10, 3)).isEmpty();
        assertThat(BusDepartureDetector.departures(snap(T1, new Vehicle("V1", null, 4, false)),
                snap(T2, new Vehicle("V1", null, 11, false)), 10, 3)).isEmpty();
        assertThat(BusDepartureDetector.departures(null, snap(T2, new Vehicle("V1", null, 11, false)), 10, 3))
                .isEmpty();
    }

    @Test
    @DisplayName("근접: 승차 2정거장 안에 아직 안 지난 차")
    void approaching() {
        assertThat(BusDepartureDetector.approaching(snap(T1, new Vehicle("V1", null, 8, false)), 10, 2)).isTrue();
        assertThat(BusDepartureDetector.approaching(snap(T1, new Vehicle("V1", null, 7, false)), 10, 2)).isFalse();
        assertThat(BusDepartureDetector.approaching(snap(T1, new Vehicle("V1", null, 10, true)), 10, 2)).isFalse();
        assertThat(BusDepartureDetector.approaching(Snapshot.failed(Outcome.ERROR, T1), 10, 2)).isFalse();
    }

    private static Snapshot snap(Instant at, Vehicle... vehicles) {
        return Snapshot.ok(List.of(vehicles), at);
    }
}
