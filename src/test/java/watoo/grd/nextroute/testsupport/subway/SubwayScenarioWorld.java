package watoo.grd.nextroute.testsupport.subway;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import watoo.grd.nextroute.application.subway.dto.SubwayExpressType;
import watoo.grd.nextroute.application.subway.dto.SubwayPositionFetchResult;
import watoo.grd.nextroute.application.subway.dto.SubwayPositionInfo;
import watoo.grd.nextroute.application.subway.dto.SubwayRealtimeSnapshot;
import watoo.grd.nextroute.application.subway.dto.SubwayRealtimeTrain;
import watoo.grd.nextroute.application.subway.dto.SubwayTrainStatus;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 시나리오 IT 의 무대. 2호선(1002) 외선 <b>강남 → 역삼 → 선릉 → 삼성</b>.
 *
 * <p>위치 피드와 도착 피드를 <b>같은 손</b>으로 만든다. 두 원천이 서로 다른 세계를 말하면
 * 시험이 통과해도 운영과 무관한 조합을 검증한 셈이 된다.
 *
 * <p>역 표기는 세 군데서 쓰인다 — 인접 표({@code subway_segment}, 이름으로 저장), 위치 응답의
 * {@code statnNm}, 계획 경로의 역 이름. 셋이 어긋나면 출발 사건이 조용히 안 생기므로 한 상수에서 뽑는다.
 */
public final class SubwayScenarioWorld {

    public static final String LINE = "1002";
    /** 도착 API·위치 저장 공간의 방향. 2호선 wayCode 1 = 외선. */
    public static final String DIRECTION = "외선";
    public static final int WAY_CODE = 1;

    public static final Station GANGNAM = new Station("1002000222", "강남");
    public static final Station YEOKSAM = new Station("1002000221", "역삼");
    public static final Station SEOLLEUNG = new Station("1002000220", "선릉");
    public static final Station SAMSEONG = new Station("1002000219", "삼성");
    public static final List<Station> STATIONS = List.of(GANGNAM, YEOKSAM, SEOLLEUNG, SAMSEONG);

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter RECEIVED_AT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter RECEIVED_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    /** 구간 소요. 실제 강남~역삼 약 2분. */
    private static final double SEGMENT_SECONDS = 120.0;

    public record Station(String id, String name) {
    }

    /** 한 시각에 열차 하나가 어디서 무엇을 하고 있는가. {@code reportedAt} = 원천의 {@code recptnDt}. */
    public record TrainAt(String trainNo, Station station, SubwayTrainStatus status, Instant reportedAt) {
    }

    private final List<Long> segmentIds = new ArrayList<>();
    private final List<Long> stationIds = new ArrayList<>();

    // ── 시드 ──────────────────────────────────────────────────────────────────

    /**
     * 인접 표와 실시간 역 마스터를 넣는다.
     *
     * <p>인접 표는 {@code depart_station_id}/{@code arrive_station_id} 칼럼에 <b>역 이름</b>을 담는다
     * — 칼럼 이름과 달리 운영 데이터도 그렇다({@code SubwayStationAdjacency} javadoc).
     */
    public void seed(JdbcTemplate jdbc) {
        for (int i = 1; i < STATIONS.size(); i++) {
            segmentIds.add(jdbc.queryForObject("""
                    insert into subway_segment
                        (created_at, updated_at, line_id, depart_station_id, arrive_station_id,
                         distance, seq, travel_time)
                    values (now(), now(), ?, ?, ?, 1.0, ?, ?) returning id
                    """, Long.class, LINE, STATIONS.get(i - 1).name(), STATIONS.get(i).name(),
                    i, SEGMENT_SECONDS));
        }
        for (Station station : STATIONS) {
            stationIds.add(jdbc.queryForObject("""
                    insert into subway_station (created_at, updated_at, line_id, statn_id, statn_nm)
                    values (now(), now(), ?, ?, ?) returning id
                    """, Long.class, LINE, station.id(), station.name()));
        }
    }

    /** 이 무대가 넣은 행만 지운다. 로컬 DB 는 개발 데이터와 함께 쓴다. */
    public void cleanUp(JdbcTemplate jdbc) {
        segmentIds.forEach(id -> jdbc.update("delete from subway_segment where id = ?", id));
        stationIds.forEach(id -> jdbc.update("delete from subway_station where id = ?", id));
        segmentIds.clear();
        stationIds.clear();
    }

    /**
     * 이 노선의 전이 로그를 지운다. TTL 이 15분이라 앞 시나리오의 로그가 다음 시나리오 후보로
     * 섞인다 — 서로 다른 이야기인데 같은 열차 번호를 쓰기 때문에 특히 위험하다.
     */
    public void clearTrainLogs(StringRedisTemplate redis) {
        for (String pattern : List.of("subway:pos:*:" + LINE, "subway:dep:*:" + LINE + ":*",
                "subway:arr:*:" + LINE + ":*")) {
            Set<String> keys = redis.keys(pattern);
            if (keys != null && !keys.isEmpty()) {
                redis.delete(keys);
            }
        }
    }

    // ── 피드 ──────────────────────────────────────────────────────────────────

    /** 서울 위치 API 한 번의 응답. 완결 응답이다 — 불완전하면 추적기가 통째로 버린다. */
    public SubwayPositionFetchResult positionFeed(Instant fetchedAt, TrainAt... trains) {
        List<SubwayPositionInfo> rows = new ArrayList<>();
        for (TrainAt t : trains) {
            rows.add(positionRow(t));
        }
        return new SubwayPositionFetchResult(LINE, rows, rows.size(),
                SubwayPositionFetchResult.Status.COMPLETE, fetchedAt, null);
    }

    /** 같은 열차를 한 응답에 두 번 싣는다 — 코레일 공동운행 노선에서 실측된 모양. */
    public SubwayPositionFetchResult positionFeedWithDuplicate(Instant fetchedAt, TrainAt primary,
                                                               TrainAt staleCopy, TrainAt... others) {
        List<SubwayPositionInfo> rows = new ArrayList<>();
        rows.add(positionRow(staleCopy));
        rows.add(positionRow(primary));
        for (TrainAt t : others) {
            rows.add(positionRow(t));
        }
        return new SubwayPositionFetchResult(LINE, rows, rows.size(),
                SubwayPositionFetchResult.Status.COMPLETE, fetchedAt, null);
    }

    /** 도착 스냅샷. 행 하나 = "그 역에 이 열차가 이만큼 남았다". */
    public SubwayRealtimeSnapshot arrivalFeed(Instant collectedAt, SubwayRealtimeTrain... rows) {
        return SubwayRealtimeSnapshot.builder()
                .collectedAt(format(collectedAt))
                .trains(List.of(rows))
                .build();
    }

    /**
     * 도착 행.
     *
     * @param code    {@code arvlCd}: 0 진입, 1 도착, 2 출발, 3 전역출발, 99 운행중
     * @param seconds 남은 초. 사건 코드면 {@code null}
     */
    public SubwayRealtimeTrain arrivalRow(String trainNo, Station at, String code, Integer seconds,
                                          Instant reportedAt) {
        return SubwayRealtimeTrain.builder()
                .trainNo(trainNo)
                .stationId(at.id())
                .stationName(at.name())
                .lineId(LINE)
                .direction(DIRECTION)
                .arrivalCode(code)
                .arrivalSeconds(seconds)
                .receivedAt(format(reportedAt))
                .build();
    }

    private SubwayPositionInfo positionRow(TrainAt t) {
        return new SubwayPositionInfo(LINE, t.trainNo(), t.station().id(), t.station().name(),
                DIRECTION, SAMSEONG.id(), SAMSEONG.name(), t.status(), SubwayExpressType.NORMAL,
                format(t.reportedAt()),
                LocalDateTime.ofInstant(t.reportedAt(), SEOUL).format(RECEIVED_DATE));
    }

    private static String format(Instant instant) {
        return LocalDateTime.ofInstant(instant, SEOUL).format(RECEIVED_AT);
    }
}
