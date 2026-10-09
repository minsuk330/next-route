package watoo.grd.nextroute.infrastructure.adapter.in.api.map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import watoo.grd.nextroute.application.map.dto.BusStopSnapshotPayload;
import watoo.grd.nextroute.application.map.dto.BusStopTileSnapshot;
import watoo.grd.nextroute.application.map.port.in.GetBusStopSnapshotUseCase;
import watoo.grd.nextroute.application.map.port.in.GetBusStopTilesUseCase;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MapBusStopController.class)
@AutoConfigureMockMvc(addFilters = false)
class MapBusStopControllerTest {

    private static final String VERSION = "12986-1786000000";
    private static final String BODY = """
            {"version":"12986-1786000000","count":1,\
            "columns":["stopId","arsId","stopName","lat","lng"],\
            "stops":[["100000001","01001","종로2가사거리",37.57062,126.98814]]}""";

    @Autowired MockMvc mockMvc;
    @MockBean GetBusStopSnapshotUseCase getBusStopSnapshotUseCase;
    @MockBean GetBusStopTilesUseCase getBusStopTilesUseCase;

    // ── v2 전국 타일(#326) ──

    private static final String TILE_VERSION = "abcdef123456";
    private static final String TILE_BODY = """
            {"key":"374_1270","version":"abcdef123456",\
            "columns":["gtfsStopId","name","lat","lng","arsId"],\
            "stops":[["1:BS_TAGO_SEB121000009","신분당선강남역",37.4958,127.029,"22010"]]}""";
    private static final String MANIFEST = """
            {"version":"0123456789ab","tileSize":0.1,"count":1,"tiles":{"374_1270":"abcdef123456"}}""";

    private void givenTiles() {
        given(getBusStopTilesUseCase.current()).willReturn(java.util.Optional.of(new BusStopTileSnapshot(
                "0123456789ab", 1, java.time.Instant.EPOCH,
                java.util.Map.of("374_1270", new BusStopTileSnapshot.Tile(TILE_VERSION,
                        TILE_BODY.getBytes(StandardCharsets.UTF_8))),
                MANIFEST.getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void tiles_manifest_200_noCache_etag() throws Exception {
        givenTiles();

        mockMvc.perform(get("/api/map/bus-stops/v2/tiles"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"0123456789ab\""))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache"))
                .andExpect(jsonPath("$.tileSize").value(0.1))
                .andExpect(jsonPath("$.tiles['374_1270']").value(TILE_VERSION));
    }

    @Test
    void tiles_manifest_304_alsoWeak() throws Exception {
        givenTiles();

        mockMvc.perform(get("/api/map/bus-stops/v2/tiles").header(HttpHeaders.IF_NONE_MATCH, "\"0123456789ab\""))
                .andExpect(status().isNotModified())
                .andExpect(content().string(""));
        // Tomcat gzip 은 강한 ETag 를 W/ 로 바꾼다 — 클라가 그대로 돌려보내도 304
        mockMvc.perform(get("/api/map/bus-stops/v2/tiles").header(HttpHeaders.IF_NONE_MATCH, "W/\"0123456789ab\""))
                .andExpect(status().isNotModified());
    }

    @Test
    void tiles_beforeFirstBuild_503_retryAfter() throws Exception {
        given(getBusStopTilesUseCase.current()).willReturn(java.util.Optional.empty());

        mockMvc.perform(get("/api/map/bus-stops/v2/tiles"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "30"));
        mockMvc.perform(get("/api/map/bus-stops/v2/tiles/374_1270"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void tile_200_columnsContract_andCacheByVersionParam() throws Exception {
        givenTiles();

        mockMvc.perform(get("/api/map/bus-stops/v2/tiles/374_1270").param("v", TILE_VERSION))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"" + TILE_VERSION + "\""))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=31536000, public, immutable"))
                .andExpect(jsonPath("$.columns[0]").value("gtfsStopId"))
                .andExpect(jsonPath("$.columns[4]").value("arsId"))
                .andExpect(jsonPath("$.stops[0][0]").value("1:BS_TAGO_SEB121000009"));
        // v 가 없거나 낡았으면 immutable 이 아니다 — 지금 본문 + 재검증
        mockMvc.perform(get("/api/map/bus-stops/v2/tiles/374_1270").param("v", "old"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache"));
    }

    @Test
    void tile_304_404_400() throws Exception {
        givenTiles();

        mockMvc.perform(get("/api/map/bus-stops/v2/tiles/374_1270")
                        .header(HttpHeaders.IF_NONE_MATCH, "\"" + TILE_VERSION + "\""))
                .andExpect(status().isNotModified());
        mockMvc.perform(get("/api/map/bus-stops/v2/tiles/350_1250")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/map/bus-stops/v2/tiles/abc")).andExpect(status().isBadRequest());
    }

    @Test
    void snapshot_returns200WithVersionEtagAndRows() throws Exception {
        given(getBusStopSnapshotUseCase.currentVersion()).willReturn(VERSION);
        given(getBusStopSnapshotUseCase.snapshot())
                .willReturn(new BusStopSnapshotPayload(VERSION, BODY.getBytes(StandardCharsets.UTF_8)));

        mockMvc.perform(get("/api/map/bus-stops/snapshot"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"" + VERSION + "\""))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=604800, must-revalidate, public"))
                .andExpect(jsonPath("$.version").value(VERSION))
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.columns[0]").value("stopId"))
                .andExpect(jsonPath("$.columns[4]").value("lng"))
                .andExpect(jsonPath("$.stops[0][0]").value("100000001"))
                .andExpect(jsonPath("$.stops[0][3]").value(37.57062));
    }

    /** 304 의 핵심 계약: 본문을 만들지 않는다. */
    @Test
    void snapshot_matchingIfNoneMatch_returns304WithoutBuildingBody() throws Exception {
        given(getBusStopSnapshotUseCase.currentVersion()).willReturn(VERSION);

        mockMvc.perform(get("/api/map/bus-stops/snapshot")
                        .header(HttpHeaders.IF_NONE_MATCH, "\"" + VERSION + "\""))
                .andExpect(status().isNotModified())
                .andExpect(header().string(HttpHeaders.ETAG, "\"" + VERSION + "\""))
                .andExpect(content().string(""));

        verify(getBusStopSnapshotUseCase, never()).snapshot();
    }

    @Test
    void snapshot_weakIfNoneMatch_returns304() throws Exception {
        given(getBusStopSnapshotUseCase.currentVersion()).willReturn(VERSION);

        mockMvc.perform(get("/api/map/bus-stops/snapshot")
                        .header(HttpHeaders.IF_NONE_MATCH, "W/\"" + VERSION + "\""))
                .andExpect(status().isNotModified());

        verify(getBusStopSnapshotUseCase, never()).snapshot();
    }

    @Test
    void snapshot_staleIfNoneMatch_returns200() throws Exception {
        given(getBusStopSnapshotUseCase.currentVersion()).willReturn(VERSION);
        given(getBusStopSnapshotUseCase.snapshot())
                .willReturn(new BusStopSnapshotPayload(VERSION, BODY.getBytes(StandardCharsets.UTF_8)));

        mockMvc.perform(get("/api/map/bus-stops/snapshot")
                        .header(HttpHeaders.IF_NONE_MATCH, "\"12000-1700000000\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(VERSION));
    }

    /** 프로브와 본문 버전이 어긋나도 클라에 주는 ETag 는 실제 본문의 버전이어야 한다. */
    @Test
    void snapshot_etagFollowsBodyVersion_notProbeVersion() throws Exception {
        given(getBusStopSnapshotUseCase.currentVersion()).willReturn("12986-1786000000");
        given(getBusStopSnapshotUseCase.snapshot())
                .willReturn(new BusStopSnapshotPayload("12987-1786000099",
                        BODY.getBytes(StandardCharsets.UTF_8)));

        String etag = mockMvc.perform(get("/api/map/bus-stops/snapshot"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getHeader(HttpHeaders.ETAG);

        assertThat(etag).isEqualTo("\"12987-1786000099\"");
    }
}
