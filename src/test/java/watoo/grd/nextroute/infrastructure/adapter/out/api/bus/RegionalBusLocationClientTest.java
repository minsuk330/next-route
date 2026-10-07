package watoo.grd.nextroute.infrastructure.adapter.out.api.bus;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.client.RestClient;
import watoo.grd.nextroute.application.bus.dto.BusPositionInfo;
import watoo.grd.nextroute.application.bus.port.out.BusPositionPort.Outcome;
import watoo.grd.nextroute.application.bus.port.out.BusPositionPort.Snapshot;
import watoo.grd.nextroute.application.bus.port.out.BusPositionPort.Vehicle;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort.BusQueryResult;
import watoo.grd.nextroute.infrastructure.adapter.out.api.ApiCallBlocker;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** 위치 응답 파싱. 픽스처는 2026-10-07 실측(경기 9607 = 204000065, 대전 1001 = DJB30300151). */
@DisplayName("경기·TAGO·서울 버스 위치 파싱")
class RegionalBusLocationClientTest {

    private static final Instant AT = Instant.parse("2026-10-07T06:35:10Z");
    private final RegionalBusLocationClient client = new RegionalBusLocationClient(RestClient.builder().build(),
            new ObjectMapper(), mock(ApiCallBlocker.class), Clock.fixed(AT, ZoneOffset.UTC),
            "http://gg", "k", "http://tago", "k");

    @Test
    @DisplayName("경기: vehId·plateNo·stationSeq, stateCd 2 = 떠남")
    void gyeonggi() throws IOException {
        Snapshot snapshot = client.parseGyeonggi(fixture("gyeonggi-location.json"), AT);

        assertThat(snapshot.outcome()).isEqualTo(Outcome.OK);
        assertThat(snapshot.vehicles()).hasSize(5);
        assertThat(snapshot.vehicles().get(0)).isEqualTo(new Vehicle("204001367", "경기70아7580", 25, false));
        assertThat(snapshot.vehicles().get(2).departed()).isTrue();
    }

    @Test
    @DisplayName("TAGO: 차량번호가 id, nodeord 가 순번, 상태 없음")
    void tago() throws IOException {
        Snapshot snapshot = client.parseTago(fixture("tago-location.json"), AT);

        assertThat(snapshot.vehicles()).hasSize(5);
        assertThat(snapshot.vehicles().get(0)).isEqualTo(new Vehicle("대전75자9877", "대전75자9877", 3, false));
    }

    @Test
    @DisplayName("TAGO: 한 대면 객체, 없으면 items 빈 문자열")
    void tagoShapes() {
        String one = """
                {"response":{"header":{"resultCode":"00"},"body":{"items":{"item":
                 {"nodeord":7,"vehicleno":"대전1"}},"totalCount":1}}}""";
        String empty = """
                {"response":{"header":{"resultCode":"00"},"body":{"items":"","totalCount":0}}}""";

        assertThat(client.parseTago(one, AT).vehicles()).singleElement()
                .isEqualTo(new Vehicle("대전1", "대전1", 7, false));
        assertThat(client.parseTago(empty, AT).isOk()).isTrue();
        assertThat(client.parseTago(empty, AT).vehicles()).isEmpty();
    }

    @Test
    @DisplayName("경기 결과 없음(4) = 빈 OK, 포털 키 오류(30) = BLOCKED, TAGO 세션 한도(99) = LIMITED")
    void failures() {
        assertThat(client.parseGyeonggi("""
                {"response":{"msgHeader":{"resultCode":4}}}""", AT).vehicles()).isEmpty();
        assertThat(client.parseGyeonggi("""
                {"OpenAPI_ServiceResponse":{"cmmMsgHeader":{"returnReasonCode":"30"}}}""", AT).outcome())
                .isEqualTo(Outcome.BLOCKED);
        assertThat(client.parseTago("""
                {"response":{"header":{"resultCode":"99"}}}""", AT).outcome()).isEqualTo(Outcome.LIMITED);
        assertThat(client.parseTago("not json", AT).outcome()).isEqualTo(Outcome.ERROR);
    }

    @Test
    @DisplayName("서울: sectOrd 가 순번, stopFlag 1 = 정류소에 서 있음(아직 안 떠남)")
    void seoul() {
        BusPositionInfo atStop = seoul("V1", "서울74사1", 10, "1");
        BusPositionInfo running = seoul("V2", "서울74사2", 12, "0");

        Snapshot snapshot = BusPositionAdapter.toSnapshot(BusQueryResult.ok(List.of(atStop, running)),
                Clock.fixed(AT, ZoneOffset.UTC));

        assertThat(snapshot.vehicles()).containsExactly(new Vehicle("V1", "서울74사1", 10, false),
                new Vehicle("V2", "서울74사2", 12, true));
        assertThat(BusPositionAdapter.toSnapshot(BusQueryResult.limited(), Clock.fixed(AT, ZoneOffset.UTC))
                .outcome()).isEqualTo(Outcome.LIMITED);
    }

    private static BusPositionInfo seoul(String vehId, String plate, int sectOrd, String stopFlag) {
        return new BusPositionInfo(vehId, null, sectOrd, null, null, stopFlag, null, null, plate, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null);
    }

    private static String fixture(String name) throws IOException {
        return new ClassPathResource("bus/position/" + name).getContentAsString(StandardCharsets.UTF_8);
    }
}
