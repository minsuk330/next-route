package watoo.grd.nextroute.application.arrival.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.arrival.dto.BusStopArrivalResponse;
import watoo.grd.nextroute.application.arrival.dto.GyeonggiBusArrival;
import watoo.grd.nextroute.application.arrival.dto.GyeonggiStopArrival;
import watoo.grd.nextroute.application.arrival.dto.SeoulStationArrival;
import watoo.grd.nextroute.application.arrival.dto.TagoBusArrival;
import watoo.grd.nextroute.application.arrival.dto.TagoStopArrival;

/** 정류장 단위 원본 → 노선 한 줄(#577). 값은 2026-11-16 실응답(강남역9번출구 22167)에서 가져왔다. */
class BusStopArrivalMappersTest {

    private static SeoulStationArrival seoul(String routeId, String name, Integer type, String stId, Integer staOrd,
                                             String msg1, Integer tra1, String veh1, Integer sect1,
                                             String msg2, Integer tra2, String veh2, Integer sect2) {
        return new SeoulStationArrival(routeId, name, type, stId, staOrd, msg1, tra1, veh1, sect1, 3,
                msg2, tra2, veh2, sect2, 0, "용산공영차고지");
    }

    @Test
    void 서울_버스가_있으면_남은초와_몇정거장전_차량을_준다() {
        var row = BusStopArrivalMappers.seoul(seoul("104000014", "040", 3, "121000091", 56,
                "[막차] 11분후[5번째 전]", 685, "104011016", 51, "운행종료", 0, "0", 0));

        assertThat(row.routeId()).isEqualTo("104000014");
        assertThat(row.routeName()).isEqualTo("040");
        assertThat(row.routeType()).isEqualTo(3);
        assertThat(row.routeTypeSource()).isEqualTo("SEOUL");
        assertThat(row.routeTypeName()).isEqualTo("간선");
        assertThat(row.direction()).isEqualTo("용산공영차고지");
        assertThat(row.predictTime1()).isEqualTo(685);
        assertThat(row.stopsAway1()).as("staOrd 56 − sectOrd 51 = [5번째 전]").isEqualTo(5);
        assertThat(row.vehicleId1()).isEqualTo("104011016");
        assertThat(row.congestionNum1()).isEqualTo(3);
    }

    @Test
    void 서울_차량이_0인_행은_남은초도_몇정거장전도_없다() {
        var row = BusStopArrivalMappers.seoul(seoul("100100093", "640", 3, "121000091", 41,
                "운행종료", 0, "0", 0, "운행종료", 0, "0", 0));

        assertThat(row.arrivalMsg1()).isEqualTo("운행종료");
        assertThat(row.predictTime1()).as("운행종료 행의 traTime 0 은 '0초 후'가 아니다").isNull();
        assertThat(row.stopsAway1()).isNull();
        assertThat(row.vehicleId1()).isNull();
        assertThat(row.predictTime2()).isNull();
    }

    @Test
    void 서울은_요청한_정류장_id_의_행만_쓰고_같은_노선은_빠른쪽_하나() {
        var rows = BusStopArrivalMappers.seoul(List.of(
                seoul("R1", "1", 3, "121000091", 10, "5분후[2번째 전]", 300, "V1", 8, null, null, "0", 0),
                seoul("R1", "1", 3, "121000091", 40, "2분후[1번째 전]", 120, "V2", 39, null, null, "0", 0),
                seoul("R2", "2", 3, "121000099", 10, "1분후", 60, "V3", 9, null, null, "0", 0)), "121000091");

        assertThat(rows).extracting(BusStopArrivalResponse::routeId).containsExactly("R1");
        assertThat(rows.get(0).predictTime1()).as("순환 노선 두 행 중 첫 버스가 빠른 쪽").isEqualTo(120);
    }

    @Test
    void 서울_심야버스_유형15는_심야() {
        var row = BusStopArrivalMappers.seoul(seoul("100100589", "N61", 15, "121000091", 147,
                "45분후[28번째 전]", 2725, "110027470", 119, null, null, "0", 0));
        assertThat(row.routeTypeName()).isEqualTo("심야");
        assertThat(row.stopsAway1()).isEqualTo(28);
    }

    @Test
    void 경기는_노선단위_규칙을_그대로_쓰고_이름과_유형과_종점을_더한다() {
        var ended = new GyeonggiStopArrival(new GyeonggiBusArrival("200000144", null, null, null, null,
                null, null, null, null, "STOP", null, null, null, null, null), "99", 13, "쎈트럴써밋");
        var coming = new GyeonggiStopArrival(new GyeonggiBusArrival("200000078", 17, 18, 1, null,
                null, null, null, null, "PASS", "200011003", null, 1039, null, "홈플러스서수원점"), "62-1", 13, "동탄차고지");

        var rows = BusStopArrivalMappers.gyeonggi(List.of(ended, coming));

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).routeName()).isEqualTo("99");
        assertThat(rows.get(0).routeTypeSource()).isEqualTo("GG");
        assertThat(rows.get(0).routeTypeName()).isEqualTo("일반");
        assertThat(rows.get(0).arrivalMsg1()).isEqualTo("운행종료");
        assertThat(rows.get(0).predictTime1()).isNull();
        assertThat(rows.get(1).direction()).isEqualTo("동탄차고지");
        assertThat(rows.get(1).predictTime1()).as("초가 분(17×60)과 60초 안이면 원본 초").isEqualTo(1039);
        assertThat(rows.get(1).stopsAway1()).isEqualTo(18);
        assertThat(rows.get(1).vehicleId1()).isEqualTo("200011003");
        assertThat(rows.get(1).congestionNum1()).as("경기 혼잡 1 → 서울 코드 3").isEqualTo(3);
    }

    @Test
    void TAGO는_버스_한대씩_온_줄을_노선으로_묶어_빠른_두대를_쓴다() {
        var rows = BusStopArrivalMappers.tago(List.of(
                new TagoStopArrival(new TagoBusArrival("BSB5200039000", 627, 5, "저상버스"), "39", "일반버스"),
                new TagoStopArrival(new TagoBusArrival("BSB5200155000", 303, 2, "저상버스"), "155", "일반버스"),
                new TagoStopArrival(new TagoBusArrival("BSB5200039000", 203, 2, "저상버스"), "39", "일반버스")));

        assertThat(rows).hasSize(2);
        BusStopArrivalResponse line39 = rows.stream().filter(r -> r.routeId().equals("BSB5200039000")).findFirst().orElseThrow();
        assertThat(line39.routeName()).isEqualTo("39");
        assertThat(line39.routeType()).isNull();
        assertThat(line39.routeTypeSource()).isEqualTo("TAGO");
        assertThat(line39.routeTypeName()).isEqualTo("일반버스");
        assertThat(line39.predictTime1()).isEqualTo(203);
        assertThat(line39.stopsAway1()).isEqualTo(2);
        assertThat(line39.predictTime2()).isEqualTo(627);
        assertThat(line39.vehicleId1()).isNull();
    }

    @Test
    void 합칠때는_노선_id_로만_묶고_시간이_있는_줄을_남기며_시간순_뒤에_시간없는_줄() {
        // 서울 정류장 단위 응답에도 경기 노선이 경기 노선 id 로 온다(실응답 222000073 1700남양주).
        BusStopArrivalResponse seoulSide = BusStopArrivalResponse.builder().routeId("222000073").routeName("1700남양주")
                .routeTypeSource("SEOUL").arrivalMsg1("운행종료").build();
        BusStopArrivalResponse ggSide = BusStopArrivalResponse.builder().routeId("222000073").routeName("1700")
                .routeTypeSource("GG").arrivalMsg1("5분후").predictTime1(300).build();
        BusStopArrivalResponse soon = BusStopArrivalResponse.builder().routeId("A").routeName("146").predictTime1(82).build();
        BusStopArrivalResponse ended = BusStopArrivalResponse.builder().routeId("B").routeName("640").build();

        var merged = BusStopArrivalMappers.mergeSorted(List.of(List.of(seoulSide, ended, soon), List.of(ggSide)));

        assertThat(merged).extracting(BusStopArrivalResponse::routeId).containsExactly("A", "222000073", "B");
        assertThat(merged.get(1).routeTypeSource()).as("같은 노선이면 시간이 있는 경기 쪽").isEqualTo("GG");
    }

    @Test
    void 서울_문구가_운행종료면_차량id가_와도_시간과_정거장과_차량을_비운다() {
        // 2026-11-16 01:1x 121000009 420번 실응답: 막차가 정류장에 닿은 순간.
        var row = BusStopArrivalMappers.seoul(seoul("100100062", "420", 3, "121000009", 71,
                "운행종료", 5, "122014120", 71, "운행종료", 0, "0", 0));

        assertThat(row.arrivalMsg1()).isEqualTo("운행종료");
        assertThat(row.predictTime1()).isNull();
        assertThat(row.stopsAway1()).isNull();
        assertThat(row.vehicleId1()).isNull();
    }

    @Test
    void 서울_막차_문구는_운행종료가_아니라_시간을_준다() {
        var row = BusStopArrivalMappers.seoul(seoul("104000014", "040", 3, "121000091", 56,
                "[막차] 11분후[5번째 전]", 685, "104011016", 51, "운행종료", 0, "0", 0));

        assertThat(row.predictTime1()).isEqualTo(685);
        assertThat(row.predictTime2()).isNull();
    }
}
