package watoo.grd.nextroute.application.route.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.route.dto.CoordPoint;
import watoo.grd.nextroute.application.route.dto.OdsayRoutePolylineData;
import watoo.grd.nextroute.application.route.dto.OdsayRoutePolylinePoint;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OdsayRoutePolylineSlicerTest {

    OdsayRoutePolylineSlicer slicer;

    @BeforeEach
    void setUp() {
        slicer = new OdsayRoutePolylineSlicer();
    }

    /**
     * 역 경계는 같은 좌표 2개가 연속으로 나타난다.
     * 출발역의 2번째 occurrence ~ 도착역의 1번째 occurrence 사이를 잘라 반환.
     *
     * 예시 polyline (간단 모델):
     *   index 0: 역A (도착 boundary)
     *   index 1: 역A (출발 boundary)
     *   index 2: 중간
     *   index 3: 중간
     *   index 4: 역B (도착 boundary)
     *   index 5: 역B (출발 boundary)
     *   index 6: 중간
     *   index 7: 역C (도착 boundary)
     *   index 8: 역C (출발 boundary)
     */
    private OdsayRoutePolylineData stationPolyline() {
        List<OdsayRoutePolylinePoint> points = new ArrayList<>();
        points.add(new OdsayRoutePolylinePoint(0, 126.10, 37.10)); // A 도착
        points.add(new OdsayRoutePolylinePoint(1, 126.10, 37.10)); // A 출발
        points.add(new OdsayRoutePolylinePoint(2, 126.12, 37.12));
        points.add(new OdsayRoutePolylinePoint(3, 126.14, 37.14));
        points.add(new OdsayRoutePolylinePoint(4, 126.16, 37.16)); // B 도착
        points.add(new OdsayRoutePolylinePoint(5, 126.16, 37.16)); // B 출발
        points.add(new OdsayRoutePolylinePoint(6, 126.18, 37.18));
        points.add(new OdsayRoutePolylinePoint(7, 126.20, 37.20)); // C 도착
        points.add(new OdsayRoutePolylinePoint(8, 126.20, 37.20)); // C 출발
        return new OdsayRoutePolylineData(points);
    }

    private OdsayRoutePolylineData circularPolyline() {
        List<OdsayRoutePolylinePoint> points = new ArrayList<>();
        points.add(new OdsayRoutePolylinePoint(0, 126.10, 37.10)); // A 도착
        points.add(new OdsayRoutePolylinePoint(1, 126.10, 37.10)); // A 출발
        points.add(new OdsayRoutePolylinePoint(2, 126.15, 37.15));
        points.add(new OdsayRoutePolylinePoint(3, 126.20, 37.20)); // B 도착
        points.add(new OdsayRoutePolylinePoint(4, 126.20, 37.20)); // B 출발
        points.add(new OdsayRoutePolylinePoint(5, 126.25, 37.25));
        points.add(new OdsayRoutePolylinePoint(6, 126.30, 37.30)); // C 도착
        points.add(new OdsayRoutePolylinePoint(7, 126.30, 37.30)); // C 출발
        points.add(new OdsayRoutePolylinePoint(8, 126.35, 37.35));
        points.add(new OdsayRoutePolylinePoint(9, 126.40, 37.40)); // D 도착
        points.add(new OdsayRoutePolylinePoint(10, 126.40, 37.40)); // D 출발
        points.add(new OdsayRoutePolylinePoint(11, 126.45, 37.45));
        points.add(new OdsayRoutePolylinePoint(12, 126.50, 37.50)); // E 도착
        points.add(new OdsayRoutePolylinePoint(13, 126.50, 37.50)); // E 출발
        points.add(new OdsayRoutePolylinePoint(14, 126.05, 37.05)); // E → A 중간
        return new OdsayRoutePolylineData(points);
    }

    private OdsayRoutePolylineData circularPolylineWithExtraDuplicateBeforeNextStation() {
        List<OdsayRoutePolylinePoint> points = new ArrayList<>();
        points.add(new OdsayRoutePolylinePoint(0, 126.10, 37.10)); // A 도착
        points.add(new OdsayRoutePolylinePoint(1, 126.10, 37.10)); // A 출발
        points.add(new OdsayRoutePolylinePoint(2, 126.15, 37.15));
        points.add(new OdsayRoutePolylinePoint(3, 126.20, 37.20)); // B 도착
        points.add(new OdsayRoutePolylinePoint(4, 126.20, 37.20)); // B 출발
        points.add(new OdsayRoutePolylinePoint(5, 126.25, 37.25));
        points.add(new OdsayRoutePolylinePoint(6, 126.30, 37.30)); // C 도착
        points.add(new OdsayRoutePolylinePoint(7, 126.30, 37.30)); // C 출발
        points.add(new OdsayRoutePolylinePoint(8, 126.35, 37.35));
        points.add(new OdsayRoutePolylinePoint(9, 126.40, 37.40)); // D 도착
        points.add(new OdsayRoutePolylinePoint(10, 126.40, 37.40)); // D 출발
        points.add(new OdsayRoutePolylinePoint(11, 126.42, 37.42)); // 역 boundary가 아닌 중복 좌표
        points.add(new OdsayRoutePolylinePoint(12, 126.42, 37.42));
        points.add(new OdsayRoutePolylinePoint(13, 126.45, 37.45));
        points.add(new OdsayRoutePolylinePoint(14, 126.50, 37.50)); // E 도착
        points.add(new OdsayRoutePolylinePoint(15, 126.50, 37.50)); // E 출발
        points.add(new OdsayRoutePolylinePoint(16, 126.05, 37.05)); // E → A 중간
        return new OdsayRoutePolylineData(points);
    }

    // ── 좌표 기반 slice ──────────────────────────────────────────────────

    @Test
    void TC_정방향_A에서_B_좌표_slice() {
        // A 출발(idx=1) → B 도착(idx=4): [1, 2, 3, 4]
        OdsayRoutePolylineData d = stationPolyline();
        List<CoordPoint> result = slicer.sliceByCoordinates(d, 126.10, 37.10, 126.16, 37.16);

        assertThat(result).hasSize(4);
        assertThat(result.get(0).x()).isEqualTo(126.10); // idx 1
        assertThat(result.get(1).x()).isEqualTo(126.12);
        assertThat(result.get(2).x()).isEqualTo(126.14);
        assertThat(result.get(3).x()).isEqualTo(126.16); // idx 4
    }

    @Test
    void TC_정방향_A에서_C_좌표_slice_중간역_포함() {
        // A 출발(idx=1) → C 도착(idx=7)
        OdsayRoutePolylineData d = stationPolyline();
        List<CoordPoint> result = slicer.sliceByCoordinates(d, 126.10, 37.10, 126.20, 37.20);

        assertThat(result).hasSize(7);
        assertThat(result.get(0).x()).isEqualTo(126.10);  // idx 1
        assertThat(result.get(6).x()).isEqualTo(126.20);  // idx 7
    }

    @Test
    void TC_역방향_C에서_A_좌표_slice_후_reverse() {
        // C 출발(idx=8) → A 도착(idx=0). startIndex(8) > endIndex(0) → reverse
        OdsayRoutePolylineData d = stationPolyline();
        List<CoordPoint> result = slicer.sliceByCoordinates(d, 126.20, 37.20, 126.10, 37.10);

        assertThat(result).hasSize(9);
        // 진행 방향 순서: C(idx 8) ... A(idx 0)
        assertThat(result.get(0).x()).isEqualTo(126.20);  // C 출발
        assertThat(result.get(8).x()).isEqualTo(126.10);  // A 도착
    }

    @Test
    void TC_start_좌표_미발견_시_빈_리스트() {
        OdsayRoutePolylineData d = stationPolyline();
        List<CoordPoint> result = slicer.sliceByCoordinates(d, 999.0, 999.0, 126.16, 37.16);
        assertThat(result).isEmpty();
    }

    @Test
    void TC_end_좌표_미발견_시_빈_리스트() {
        OdsayRoutePolylineData d = stationPolyline();
        List<CoordPoint> result = slicer.sliceByCoordinates(d, 126.10, 37.10, 999.0, 999.0);
        assertThat(result).isEmpty();
    }

    @Test
    void TC_start_occurrence_1개뿐인_경우_fallback으로_사용() {
        // 출발 좌표가 단일 (분기/종착) → fallback으로 첫 번째 occurrence 사용
        List<OdsayRoutePolylinePoint> points = new ArrayList<>();
        points.add(new OdsayRoutePolylinePoint(0, 126.10, 37.10)); // 단일 occurrence
        points.add(new OdsayRoutePolylinePoint(1, 126.12, 37.12));
        points.add(new OdsayRoutePolylinePoint(2, 126.14, 37.14)); // 도착 boundary
        points.add(new OdsayRoutePolylinePoint(3, 126.14, 37.14)); // 출발 boundary
        OdsayRoutePolylineData d = new OdsayRoutePolylineData(points);

        List<CoordPoint> result = slicer.sliceByCoordinates(d, 126.10, 37.10, 126.14, 37.14);

        // start idx=0, end idx=2 → [0,1,2]
        assertThat(result).hasSize(3);
        assertThat(result.get(0).x()).isEqualTo(126.10);
        assertThat(result.get(2).x()).isEqualTo(126.14);
    }

    @Test
    void TC_tolerance_내_좌표_매칭() {
        OdsayRoutePolylineData d = stationPolyline();
        // tolerance 1e-6 이내의 미세 차이 → 매칭 성공
        List<CoordPoint> result = slicer.sliceByCoordinates(
                d, 126.10 + 1e-7, 37.10 + 1e-7, 126.16, 37.16);
        assertThat(result).hasSize(4);
    }

    @Test
    void TC_tolerance_밖_좌표_미매칭() {
        OdsayRoutePolylineData d = stationPolyline();
        // tolerance 1e-6 밖의 차이 → 매칭 실패
        List<CoordPoint> result = slicer.sliceByCoordinates(
                d, 126.10 + 1e-3, 37.10 + 1e-3, 126.16, 37.16);
        assertThat(result).isEmpty();
    }

    @Test
    void TC_큰_mapObj_ref가_있어도_좌표_slice는_정상() {
        // mapObj=8:2:812:814처럼 큰 ref가 와도 좌표 기반이라 cache size와 무관
        OdsayRoutePolylineData d = stationPolyline();  // size=9
        List<CoordPoint> result = slicer.sliceByCoordinates(d, 126.10, 37.10, 126.16, 37.16);
        assertThat(result).hasSize(4);
    }

    @Test
    void TC_커스텀_tolerance_사용() {
        OdsayRoutePolylineData d = stationPolyline();
        List<CoordPoint> result = slicer.sliceByCoordinates(
                d, 126.10 + 1e-3, 37.10, 126.16, 37.16, 1e-2);
        // tolerance 1e-2면 매칭됨
        assertThat(result).hasSize(4);
    }

    @Test
    void TC_null_data_빈_리스트() {
        assertThat(slicer.sliceByCoordinates(null, 1.0, 2.0, 3.0, 4.0)).isEmpty();
    }

    @Test
    void TC_exact_match_먼저_시도_tolerance_fallback_안_탐() {
        // exact match 있는 좌표 + tolerance 안에 또 다른 occurrence가 있어도 exact 우선
        List<OdsayRoutePolylinePoint> points = new ArrayList<>();
        points.add(new OdsayRoutePolylinePoint(0, 126.10, 37.10));                 // exact
        points.add(new OdsayRoutePolylinePoint(1, 126.10, 37.10));                 // exact
        points.add(new OdsayRoutePolylinePoint(2, 126.10 + 1e-7, 37.10 + 1e-7));   // tolerance 내
        points.add(new OdsayRoutePolylinePoint(3, 126.12, 37.12));
        points.add(new OdsayRoutePolylinePoint(4, 126.16, 37.16));
        points.add(new OdsayRoutePolylinePoint(5, 126.16, 37.16));
        OdsayRoutePolylineData d = new OdsayRoutePolylineData(points);

        // 2번째 exact occurrence는 idx=1. tolerance에 있는 idx=2가 잘못 매칭되면 결과가 [2,3,4]
        List<CoordPoint> result = slicer.sliceByCoordinates(d, 126.10, 37.10, 126.16, 37.16);

        // exact 우선이면 idx 1 → idx 4: [1,2,3,4]
        assertThat(result).hasSize(4);
        assertThat(result.get(0).x()).isEqualTo(126.10);             // idx 1 (exact)
        assertThat(result.get(1).x()).isEqualTo(126.10 + 1e-7);     // idx 2 (tolerance 좌표도 포함되지만 통과 경로)
    }

    @Test
    void TC_exact_match_없으면_tolerance_fallback_탐() {
        // exact 없고 tolerance 안에만 있는 좌표
        List<OdsayRoutePolylinePoint> points = new ArrayList<>();
        points.add(new OdsayRoutePolylinePoint(0, 126.10 + 1e-7, 37.10));   // tolerance 내
        points.add(new OdsayRoutePolylinePoint(1, 126.10 + 1e-7, 37.10));   // tolerance 내
        points.add(new OdsayRoutePolylinePoint(2, 126.12, 37.12));
        points.add(new OdsayRoutePolylinePoint(3, 126.16, 37.16));          // end (exact)
        points.add(new OdsayRoutePolylinePoint(4, 126.16, 37.16));
        OdsayRoutePolylineData d = new OdsayRoutePolylineData(points);

        List<CoordPoint> result = slicer.sliceByCoordinates(d, 126.10, 37.10, 126.16, 37.16);

        // exact는 없고 tolerance fallback으로 매칭 → idx 1 → idx 3
        assertThat(result).hasSize(3);
    }

    @Test
    void TC_순환선은_두번째_역_좌표로_정방향_wrap_slice를_선택한다() {
        OdsayRoutePolylineData d = circularPolyline();

        // D → B 검색에서 두 번째 역이 E이면 저장 polyline의 정방향 wrap(D→E→A→B)을 선택해야 한다.
        // 기존 startIndex > endIndex reverse 규칙이면 D→C→B가 되어 E가 포함되지 않는다.
        List<CoordPoint> result = slicer.sliceByCoordinates(
                d,
                126.40, 37.40,
                126.20, 37.20,
                126.50, 37.50
        );

        assertThat(result).isNotEmpty();
        assertThat(result.get(0).x()).isEqualTo(126.40);
        assertThat(result).extracting(CoordPoint::x).contains(126.50, 126.10, 126.20);
        assertThat(result).extracting(CoordPoint::x).doesNotContain(126.30);
        assertThat(result.get(result.size() - 1).x()).isEqualTo(126.20);
    }

    @Test
    void TC_순환선_두번째_역_좌표는_소수점_절삭으로_매칭한다() {
        OdsayRoutePolylineData d = circularPolyline();

        // ODsay search의 역 좌표와 loadLane polyline boundary 좌표는 출처가 달라 1e-6보다 크게 다를 수 있다.
        // 방향 앵커 비교는 소수 5자리 → 4자리 순서로 절삭해 같은 역 boundary를 찾는다.
        List<CoordPoint> result = slicer.sliceByCoordinates(
                d,
                126.40, 37.40,
                126.20, 37.20,
                126.50005, 37.50005
        );

        assertThat(result).isNotEmpty();
        assertThat(result).extracting(CoordPoint::x).contains(126.50, 126.10, 126.20);
        assertThat(result).extracting(CoordPoint::x).doesNotContain(126.30);
    }

    @Test
    void TC_순환선_방향_anchor는_다음_중복좌표들을_순서대로_탐색한다() {
        OdsayRoutePolylineData d = circularPolylineWithExtraDuplicateBeforeNextStation();

        // D 다음에 역 boundary가 아닌 중복 좌표가 먼저 있어도, 이후 E boundary까지 탐색해 방향을 선택한다.
        List<CoordPoint> result = slicer.sliceByCoordinates(
                d,
                126.40, 37.40,
                126.20, 37.20,
                126.50005, 37.50005
        );

        assertThat(result).isNotEmpty();
        assertThat(result).extracting(CoordPoint::x).contains(126.42, 126.50, 126.10, 126.20);
        assertThat(result).extracting(CoordPoint::x).doesNotContain(126.30);
    }

    @Test
    void TC_순환선은_두번째_역_좌표로_역방향_slice를_선택한다() {
        OdsayRoutePolylineData d = circularPolyline();

        // D → B 검색에서 두 번째 역이 C이면 역방향(D→C→B)을 선택한다.
        List<CoordPoint> result = slicer.sliceByCoordinates(
                d,
                126.40, 37.40,
                126.20, 37.20,
                126.30, 37.30
        );

        assertThat(result).isNotEmpty();
        assertThat(result.get(0).x()).isEqualTo(126.40);
        assertThat(result).extracting(CoordPoint::x).contains(126.30, 126.20);
        assertThat(result).extracting(CoordPoint::x).doesNotContain(126.50);
        assertThat(result.get(result.size() - 1).x()).isEqualTo(126.20);
    }

    @Test
    void TC_실제_2호선_polyline_성수에서_시청_방향_anchor_slice() throws Exception {
        Assumptions.assumeTrue(
                Boolean.getBoolean("odsay.realPolylineTest"),
                "Run with -Dodsay.realPolylineTest=true and local DB containing odsay_route_polyline routeId=2"
        );

        OdsayRoutePolylineData d = loadActualLine2PolylineFromLocalDb();

        List<CoordPoint> result = slicer.sliceByCoordinates(
                d,
                127.094698, 37.535101,
                126.975277, 37.563536,
                127.086158, 37.537144
        );

        assertThat(d.size()).isEqualTo(942);
        assertThat(result).isNotEmpty();
        assertThat(result.get(0)).isEqualTo(new CoordPoint(127.094698, 37.535101));
        assertThat(result.get(result.size() - 1)).isEqualTo(new CoordPoint(126.975277, 37.563536));
        assertThat(result).anySatisfy(p -> {
            assertThat(truncate5(p.x())).isEqualTo("127.08615");
            assertThat(truncate5(p.y())).isEqualTo("37.53714");
        });
    }

    private OdsayRoutePolylineData loadActualLine2PolylineFromLocalDb() throws Exception {
        String url = System.getProperty("odsay.realPolylineTest.url",
                "jdbc:postgresql://localhost:5432/mydatabase");
        String user = System.getProperty("odsay.realPolylineTest.user", "myuser");
        String password = System.getProperty("odsay.realPolylineTest.password",
                "maABg3j5ahg4pWTOu0pRN17zIyLMNul9");

        try (Connection connection = DriverManager.getConnection(url, user, password);
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT polyline
                     FROM odsay_route_polyline
                     WHERE odsay_route_id = '2' AND lane_class = 2
                     """);
             ResultSet rs = statement.executeQuery()) {
            assertThat(rs.next()).isTrue();
            return new ObjectMapper().readValue(rs.getString(1), OdsayRoutePolylineData.class);
        }
    }

    private String truncate5(double value) {
        String raw = String.format(java.util.Locale.US, "%.10f", value);
        int dot = raw.indexOf('.');
        return raw.substring(0, dot + 6);
    }
}
