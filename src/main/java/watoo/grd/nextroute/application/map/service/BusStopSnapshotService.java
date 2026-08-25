package watoo.grd.nextroute.application.map.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.map.dto.BusStopSnapshotPayload;
import watoo.grd.nextroute.application.map.port.in.GetBusStopSnapshotUseCase;
import watoo.grd.nextroute.domain.bus.repository.BusStopRepository;
import watoo.grd.nextroute.domain.bus.repository.BusStopSnapshotProjection;
import watoo.grd.nextroute.domain.bus.repository.BusStopSnapshotVersionProjection;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 서울 전체 버스정류장 스냅샷 제공.
 *
 * <p>정류장 표는 거의 변하지 않는다. 그래서 지도 클라이언트가 화면 이동마다 서버에 묻는 대신
 * 앱 시작 때 한 번 받아 로컬에 두는 것이 이 기능의 전제다. 서버는 (1) 값싼 버전 프로브와
 * (2) 한 번 만들어 재사용하는 본문, 두 가지만 책임진다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BusStopSnapshotService implements GetBusStopSnapshotUseCase {

    /**
     * 버전 프로브 결과 재사용 시간. 304 응답이 요청마다 DB 를 때리지 않게 하는 것이 목적이다.
     * 데이터 변경이 클라에 보이기까지 최대 이만큼 늦는다 — 정류장 표 갱신 주기에 비하면 무의미한 지연.
     */
    static final Duration VERSION_PROBE_TTL = Duration.ofSeconds(60);

    /** 응답 {@code stops} 배열의 열 순서 계약. 클라가 인덱스를 검증할 수 있게 본문에도 실어 보낸다. */
    static final List<String> COLUMNS = List.of("stopId", "arsId", "stopName", "lat", "lng");

    /** 좌표 절삭 배율. 소수 5자리 ≈ 1.1m — 마커 배치엔 충분하고 페이로드가 줄어든다. */
    private static final double COORDINATE_SCALE = 100_000d;

    private static final String EMPTY_VERSION = "0-0";

    private final BusStopRepository busStopRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    private final AtomicReference<VersionProbe> probeCache = new AtomicReference<>();
    private final AtomicReference<BusStopSnapshotPayload> cached = new AtomicReference<>();

    @Override
    public String currentVersion() {
        return probe().version();
    }

    @Override
    public BusStopSnapshotPayload snapshot() {
        String version = probe().version();
        BusStopSnapshotPayload current = cached.get();
        if (current != null && current.version().equals(version)) {
            return current;
        }
        return rebuild(version);
    }

    /**
     * 재빌드는 single-flight 다. 동시 요청이 각자 1.3만 행을 긁으면 콜드 스타트에서 DB 가 맞는다.
     * 락 안에서 다시 확인해 먼저 들어온 쪽 결과를 재사용한다.
     */
    private synchronized BusStopSnapshotPayload rebuild(String version) {
        BusStopSnapshotPayload current = cached.get();
        if (current != null && current.version().equals(version)) {
            return current;
        }

        List<BusStopSnapshotProjection> rows = busStopRepository.findAllForSnapshot();
        List<Object[]> stops = new ArrayList<>(rows.size());
        for (BusStopSnapshotProjection row : rows) {
            Double lat = row.getLatitude();
            Double lng = row.getLongitude();
            if (lat == null || lng == null) {
                continue; // 쿼리에서 이미 걸러지지만, 좌표 없는 마커는 어차피 못 그린다.
            }
            stops.add(new Object[] {
                    row.getStopId(),
                    row.getArsId(), // null 을 빈 문자열로 바꾸지 않는다 — 클라가 "없음"을 구분해야 한다.
                    row.getStopName(),
                    round(lat),
                    round(lng)
            });
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", version);
        body.put("count", stops.size());
        body.put("columns", COLUMNS);
        body.put("stops", stops);

        BusStopSnapshotPayload rebuilt = new BusStopSnapshotPayload(version, serialize(body));
        cached.set(rebuilt);
        log.info("버스정류장 스냅샷 재빌드 version={} count={} bytes={}",
                version, stops.size(), rebuilt.json().length);
        return rebuilt;
    }

    private byte[] serialize(Map<String, Object> body) {
        try {
            return objectMapper.writeValueAsBytes(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("버스정류장 스냅샷 직렬화 실패", e);
        }
    }

    /**
     * 버전 프로브. TTL 안이면 재사용한다.
     *
     * <p>프로브 시점과 본문 생성 시점 사이에 행이 들어오면 본문이 버전보다 <b>새로울</b> 수 있다.
     * 이 방향은 안전하다 — 다음 프로브가 변경을 반영해 재빌드로 수렴한다. 반대로 본문이 버전보다
     * 오래되면 영구히 낡은 응답이 굳으므로, 프로브를 본문 생성 <b>뒤로</b> 옮기지 말 것.
     */
    private VersionProbe probe() {
        Instant now = clock.instant();
        VersionProbe cachedProbe = probeCache.get();
        if (cachedProbe != null && cachedProbe.probedAt().plus(VERSION_PROBE_TTL).isAfter(now)) {
            return cachedProbe;
        }
        VersionProbe fresh = new VersionProbe(readVersion(), now);
        probeCache.set(fresh);
        return fresh;
    }

    private String readVersion() {
        BusStopSnapshotVersionProjection probed = busStopRepository.probeSnapshotVersion();
        if (probed == null) {
            return EMPTY_VERSION;
        }
        return probed.getStopCount() + "-" + probed.getMaxUpdatedEpochSec();
    }

    private double round(double value) {
        return Math.round(value * COORDINATE_SCALE) / COORDINATE_SCALE;
    }

    private record VersionProbe(String version, Instant probedAt) {
    }
}
