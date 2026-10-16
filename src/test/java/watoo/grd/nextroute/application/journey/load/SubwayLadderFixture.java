package watoo.grd.nextroute.application.journey.load;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 지하철 부하 사다리(#427)용 여정을 대량으로 심는다. 버스판({@link JourneyLoadFixture})과 같은 방식(JDBC 배치).
 *
 * <pre>
 * leg 0 WALK(완료) → leg 1 RIDE_SUBWAY(진행 중) → leg 2 TRANSFER_WALK → leg 3 WAIT_SUBWAY(환승 대상)
 * </pre>
 *
 * <h2>관심 키 두 가지</h2>
 * <ul>
 *   <li><b>실제 원천(기본)</b>: 심는 순간 서울 실시간 도착 ALL 한 장에서 열차가 있는 (노선, 역 id, 역 이름, 방향)을 골라
 *       {@link #readKeys} 로 읽는다(run_subway_ladder.sh 가 TSV 로 만든다). 방향은 앱 규칙
 *       ({@code SubwayDirectionNormalizer.toWayCode}) — 상행 1·하행 2, 2호선 내선 0·외선 1</li>
 *   <li><b>가상 모델</b>: 아래 노선망(가짜 공급자 subway_model.py 와 같은 규칙)</li>
 * </ul>
 * 어느 쪽이든 지우기는 역 id 가 아니라 여정 표시({@code destination_summary = {"ladder":"LOAD-ST"}})로 찾는다.
 *
 * <h2>가상 노선망 — 가짜 공급자(load/ladder/subway_model.py)와 같은 규칙</h2>
 * 노선 {@code lines} × 역 {@code stationsPerLine}. 역 번호 n = 노선 순번 × 역 수 + 노선 안 순서, 역 id
 * {@code LOADST{n}}, 이름 {@code 부하역{n}}. 방향은 ODsay wayCode 1(상행)·2(하행). 2호선(1002)은 내·외선이라 쓰지
 * 않는다. 관심 키 = (노선, 역, 방향) — 모든 키에 열차가 다니므로 여정은 키에 고르게 흩는다.
 *
 * <h2>등급 비율을 측정 내내 유지한다</h2>
 * 재평가 등급은 "환승 준비 시각까지 남은 시간"으로 정해진다(5분 이내 긴급, 15분 이내 주의, 그 밖 여유,
 * {@code InterestIndex.tierOf}). 준비 시각 = 타는 단계 시작 + 계획 소요 + 하차 버퍼 30초 + 환승 도보 180초.
 * 한 번 심고 두면 시간이 갈수록 모두 긴급으로 쏠리고(지난 준비 시각도 긴급), 준비 시각을 지난 여정은 계속 긴급이다.
 * 그래서 여정마다 <b>주기 L</b>을 두고, 준비 시각에 닿으면 하네스가 타는 단계 시작을 "지금"으로 되돌린다
 * (run_ladder.sh 의 표본기, {@link #RECYCLE_SQL}). 주기 L 인 여정은 한 주기에서 긴급 5분, 주의 min(10, L−5)분,
 * 여유 max(0, L−15)분을 보낸다. 세 부류를 섞어 정상 상태 비율을 긴급 20 / 주의 30 / 여유 50% 로 맞춘다.
 *
 * <pre>
 *   부류 A  5%  L =  5분 → 긴급 100%
 *   부류 B 15%  L = 15분 → 긴급 1/3, 주의 2/3
 *   부류 C 80%  L = 40분 → 긴급 1/8, 주의 1/4, 여유 5/8
 *   합계: 긴급 .05+.05+.10 = .20, 주의 .10+.20 = .30, 여유 .50
 * </pre>
 * 시작 위상은 주기 안에 고르게 편다(준비 시각 = 지금 + (0, L]).
 *
 * <p>실제 평가 시점의 등급은 위험 판정(빠듯·놓침이면 긴급)이 섞여 이 비율보다 긴급이 많다 — 요약기가 평가 시점의
 * 등급으로 다시 센다.
 */
public final class SubwayLadderFixture {

    static final String STATION_PREFIX = "LOADST";
    static final String STATION_NAME_PREFIX = "부하역";
    static final String TOKEN_PREFIX = "loadtest-";
    /** 사다리 여정 표시. 실제 역 id 를 쓰는 회차도 지울 수 있게 역이 아니라 여정에 단다. */
    static final String MARKER_JSON = "{\"ladder\":\"LOAD-ST\"}";
    static final String MARKER_SQL = "destination_summary->>'ladder' = 'LOAD-ST'";
    /** 하차 버퍼(30초) + 환승 도보(180초). 준비 시각 = 타는 단계 시작 + 계획 소요 + 이 값. */
    static final int READY_OFFSET_SECONDS = 30 + 180;
    static final List<String> DEFAULT_LINES =
            List.of("1001", "1003", "1004", "1005", "1006", "1007", "1008", "1009");

    /**
     * 준비 시각을 지난 여정의 타는 단계를 다시 시작시킨다(주기 되돌림). 하네스 표본기가 10초마다 부른다.
     * 이 SQL 은 사다리 여정(표시 {@link #MARKER_JSON})만 건드린다.
     */
    public static final String RECYCLE_SQL = """
            update journey_leg r set activated_at = now()
              from journey_session s
             where s.id = r.journey_id and s.destination_summary->>'ladder' = 'LOAD-ST'
               and r.leg_index = 1 and r.type = 'RIDE_SUBWAY' and r.state = 'ACTIVE'
               and r.activated_at + make_interval(secs => r.planned_duration_sec + 210) <= now()
            """;

    private static final int BATCH = 500;

    /** 주기 부류. */
    public enum CycleClass {
        A(300), B(900), C(2400);

        final int cycleSeconds;

        CycleClass(int cycleSeconds) {
            this.cycleSeconds = cycleSeconds;
        }

        public int cycleSeconds() {
            return cycleSeconds;
        }

        /** 타는 단계 계획 소요 = 주기 − 하차·도보. */
        public int rideSeconds() {
            return cycleSeconds - READY_OFFSET_SECONDS;
        }
    }

    /** (노선, 역, 방향) 하나. wayCode 는 ODsay 방향 코드(앱이 방향 문자열로 바꾼다). */
    public record Key(String lineId, String stationId, String stationName, int wayCode) {
        /** 가상 노선망의 역 n. */
        public static Key synthetic(String lineId, int n, int wayCode) {
            return new Key(lineId, STATION_PREFIX + n, STATION_NAME_PREFIX + n, wayCode);
        }
    }

    /** 여정 하나의 계획. */
    public record Plan(Key key, CycleClass cycle, int phaseSeconds) {
        /** 타는 단계 시작 = 지금 − 위상. 준비 시각 = 지금 − 위상 + 주기 ∈ (지금, 지금 + 주기]. */
        public Instant activatedAt(Instant now) {
            return now.minusSeconds(phaseSeconds);
        }

        public Instant readyAt(Instant now) {
            return activatedAt(now).plusSeconds(cycle.cycleSeconds());
        }

        /**
         * 심은 시각 seededAt 에서 at 까지 주기 되돌림({@link #RECYCLE_SQL})을 거친 준비 시각. 준비 시각에 닿는 순간
         * 다시 시작하므로 준비 시각은 늘 (at, at + 주기] 안에 있다.
         */
        public Instant readyAt(Instant seededAt, Instant at) {
            Instant first = readyAt(seededAt);
            if (at.isBefore(first)) {
                return first;
            }
            long cycles = Duration.between(first, at).getSeconds() / cycle.cycleSeconds() + 1;
            return first.plusSeconds(cycles * cycle.cycleSeconds());
        }
    }

    public record Seeded(List<Long> journeyIds, List<Long> userIds, List<Plan> plans) {
    }

    private final JdbcTemplate jdbc;

    public SubwayLadderFixture(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 모든 관심 키. 순서는 고정 씨앗으로 섞어 같은 노선이 몰리지 않게 한다. */
    public static List<Key> keys(List<String> lines, int stationsPerLine) {
        List<Key> keys = new ArrayList<>();
        for (int l = 0; l < lines.size(); l++) {
            for (int j = 0; j < stationsPerLine; j++) {
                int n = l * stationsPerLine + j;
                keys.add(Key.synthetic(lines.get(l), n, 1));
                keys.add(Key.synthetic(lines.get(l), n, 2));
            }
        }
        Collections.shuffle(keys, new Random(42));
        return keys;
    }

    /**
     * 여정 i 의 부류. 황금비 저불일치 수열로 고르게 섞는다(앞쪽에 몰면 만기도 몰린다).
     */
    public static CycleClass cycleOf(int index) {
        double r = frac(index * 0.6180339887498949);
        if (r < 0.05) {
            return CycleClass.A;
        }
        if (r < 0.20) {
            return CycleClass.B;
        }
        return CycleClass.C;
    }

    /**
     * 실제 원천 키 TSV(lineId, stationId, stationName, wayCode)를 읽는다. 순서는 고정 씨앗으로 섞는다.
     */
    public static List<Key> readKeys(List<String> tsvLines) {
        List<Key> keys = new ArrayList<>();
        for (String line : tsvLines) {
            String[] p = line.split("\t");
            if (p.length >= 4 && !p[0].isBlank()) {
                keys.add(new Key(p[0], p[1], p[2], Integer.parseInt(p[3].trim())));
            }
        }
        Collections.shuffle(keys, new Random(42));
        return keys;
    }

    public static List<Plan> plans(int journeys, List<String> lines, int stationsPerLine) {
        return plans(journeys, keys(lines, stationsPerLine));
    }

    public static List<Plan> plans(int journeys, List<Key> keys) {
        List<Plan> plans = new ArrayList<>(journeys);
        for (int i = 0; i < journeys; i++) {
            CycleClass cycle = cycleOf(i);
            // 위상은 다른 무리수로 — 부류와 위상이 같은 수열이면 부류 안에서 위상이 몰린다.
            int phase = (int) Math.floor(frac(i * 0.7548776662466927 + 0.5) * cycle.cycleSeconds());
            plans.add(new Plan(keys.get(i % keys.size()), cycle, phase));
        }
        return plans;
    }

    /**
     * 심은 뒤 시각 at 에 준비 시각으로 정한 등급 비율(긴급, 주의, 여유). 측정 내내 비율이 유지되는지 시험이 확인한다.
     */
    public static double[] tierShares(List<Plan> plans, Instant seededAt, Instant at) {
        double[] out = new double[3];
        for (Plan plan : plans) {
            long until = Duration.between(at, plan.readyAt(seededAt, at)).getSeconds();
            out[until <= 300 ? 0 : until <= 900 ? 1 : 2]++;
        }
        for (int i = 0; i < 3; i++) {
            out[i] /= Math.max(1, plans.size());
        }
        return out;
    }

    public Seeded seed(int journeys, List<String> lines, int stationsPerLine, Instant now) {
        return seed(journeys, keys(lines, stationsPerLine), now);
    }

    public Seeded seed(int journeys, List<Key> keys, Instant now) {
        List<Plan> plans = plans(journeys, keys);
        List<Long> users = insertUsers(journeys);
        insertPushTokens(users);
        List<Long> journeyIds = insertJourneys(users, now);
        for (int from = 0; from < journeyIds.size(); from += BATCH) {
            int to = Math.min(from + BATCH, journeyIds.size());
            List<Long> slice = journeyIds.subList(from, to);
            insertLegs(slice, plans.subList(from, to), now);
            linkSelectedOptions(slice);
            insertTransfers(slice);
        }
        return new Seeded(journeyIds, users, plans);
    }

    private List<Long> insertUsers(int count) {
        List<Long> ids = new ArrayList<>(count);
        for (int from = 0; from < count; from += BATCH) {
            int size = Math.min(BATCH, count - from);
            ids.addAll(jdbc.queryForList("""
                    insert into users (created_at, updated_at, token_generation)
                    select now(), now(), 0 from generate_series(1, ?)
                    returning id
                    """, Long.class, size));
        }
        return ids;
    }

    /** 발송처(가짜 포함)가 FCM 과 같이 사용자의 최근 토큰을 읽는다. 조용한 갱신을 받는 iOS 로 둔다. */
    private void insertPushTokens(List<Long> users) {
        List<Object[]> args = new ArrayList<>(users.size());
        for (Long user : users) {
            args.add(new Object[]{user, TOKEN_PREFIX + user});
        }
        jdbc.batchUpdate("""
                insert into user_push_token (created_at, updated_at, user_id, platform, provider, token,
                    environment, enabled, last_seen_at, quiet_update_supported)
                values (now(), now(), ?, 'IOS', 'FCM', ?, 'PRODUCTION', true, now(), true)
                """, args);
    }

    /** 다음 평가 시각은 30초 창에 고르게 편다(전부 같은 순간이면 시작 몰림을 잰다). */
    private List<Long> insertJourneys(List<Long> users, Instant now) {
        List<Long> ids = new ArrayList<>(users.size());
        for (int from = 0; from < users.size(); from += BATCH) {
            int to = Math.min(from + BATCH, users.size());
            StringBuilder sql = new StringBuilder("""
                    insert into journey_session (
                        created_at, updated_at, user_id, status, command_version, evaluation_version,
                        route_revision, current_leg_index, started_at, last_heartbeat_at,
                        guidance_lease_until, next_evaluation_at, route_snapshot, destination_summary)
                    values
                    """);
            List<Object> args = new ArrayList<>();
            for (int i = from; i < to; i++) {
                sql.append(i > from ? "," : "")
                        .append(" (now(), now(), ?, 'ACTIVE', 1, 0, 1, 1, now(), now(),"
                                + " now() + interval '3 hours', ?, ?::jsonb, ?::jsonb)");
                args.add(users.get(i));
                args.add(utc(now.plusMillis(30_000L * i / Math.max(1, users.size()))));
                args.add(SNAPSHOT);
                args.add(MARKER_JSON);
            }
            sql.append(" returning id");
            ids.addAll(jdbc.queryForList(sql.toString(), Long.class, args.toArray()));
        }
        return ids;
    }

    private void insertLegs(List<Long> journeyIds, List<Plan> plans, Instant now) {
        List<Object[]> args = new ArrayList<>(journeyIds.size() * 4);
        for (int i = 0; i < journeyIds.size(); i++) {
            Long id = journeyIds.get(i);
            Plan plan = plans.get(i);
            Key key = plan.key();
            OffsetDateTime started = utc(plan.activatedAt(now));
            args.add(new Object[]{id, 0, "WALK", "COMPLETED", null, null, 120, null, utc(now)});
            args.add(new Object[]{id, 1, "RIDE_SUBWAY", "ACTIVE", null, null, plan.cycle().rideSeconds(),
                    key.lineId(), started});
            args.add(new Object[]{id, 2, "TRANSFER_WALK", "PENDING", null, null, 180, null, null});
            args.add(new Object[]{id, 3, "WAIT_SUBWAY", "PENDING", key.stationId(), key.stationName(), 300,
                    key.lineId(), null});
        }
        jdbc.batchUpdate("""
                insert into journey_leg (
                    created_at, updated_at, journey_id, route_revision, leg_index, type, state,
                    start_stop_id, start_name, planned_duration_sec, route_id, activated_at)
                values (now(), now(), ?, 1, ?, ?, ?, ?, ?, ?, ?, ?)
                """, args);
        // 방향(wayCode)은 대기 단계의 고른 후보에 있다 — 평가기·관심 색인이 둘 다 거기서 읽는다.
        List<Object[]> options = new ArrayList<>(journeyIds.size());
        for (int i = 0; i < journeyIds.size(); i++) {
            options.add(new Object[]{plans.get(i).key().wayCode(), journeyIds.get(i)});
        }
        jdbc.batchUpdate("""
                insert into journey_leg_route_option (
                    created_at, updated_at, journey_leg_id, option_index, lane_index, route_id, route_name, way_code)
                select now(), now(), l.id, 0, 0, l.route_id, '부하선' || l.route_id, ?
                  from journey_leg l where l.journey_id = ? and l.leg_index = 3
                """, options);
    }

    private void linkSelectedOptions(List<Long> journeyIds) {
        jdbc.update("""
                update journey_leg l set selected_route_option_id = o.id
                  from journey_leg_route_option o
                 where o.journey_leg_id = l.id and l.journey_id = any (?) and l.leg_index = 3
                """, (Object) journeyIds.toArray(Long[]::new));
    }

    private void insertTransfers(List<Long> journeyIds) {
        List<Object[]> args = new ArrayList<>(journeyIds.size());
        for (Long id : journeyIds) {
            args.add(new Object[]{id});
        }
        jdbc.batchUpdate("""
                insert into transfer_connection (
                    created_at, updated_at, journey_id, route_revision, transfer_index,
                    from_leg_index, walk_leg_index, to_leg_index, status)
                values (now(), now(), ?, 1, 0, 1, 2, 3, 'UNKNOWN')
                """, args);
    }

    /**
     * 사다리 지하철 여정(표시 {@link #MARKER_JSON}, 또는 옛 회차의 대기 역 {@code LOADST})과 그 사용자·토큰을 모두
     * 지운다. 진행 중 여정 3시간 안내 lease 로 심으므로 지우지 않으면 같은 DB 를 쓰는 IT 의 선점 시험이 잡는다.
     *
     * @return 지운 여정 수
     */
    public int cleanUp() {
        List<Long> journeys = jdbc.queryForList("""
                select id from journey_session where destination_summary->>'ladder' = 'LOAD-ST'
                union
                select journey_id from journey_leg where start_stop_id like 'LOADST%'
                """, Long.class);
        Long[] ids = journeys.toArray(Long[]::new);
        List<Long> users = jdbc.queryForList(
                "select distinct user_id from journey_session where id = any (?)", Long.class, (Object) ids);
        Long[] userIds = users.toArray(Long[]::new);
        jdbc.update("update journey_leg set selected_route_option_id = null where journey_id = any (?)",
                (Object) ids);
        jdbc.update("""
                delete from journey_leg_route_option
                 where journey_leg_id in (select id from journey_leg where journey_id = any (?))
                """, (Object) ids);
        for (String table : List.of("journey_leg", "journey_revision", "transfer_connection", "journey_event",
                "journey_notification")) {
            jdbc.update("delete from " + table + " where journey_id = any (?)", (Object) ids);
        }
        jdbc.update("delete from journey_session where id = any (?)", (Object) ids);
        jdbc.update("delete from user_push_token where token like ? or user_id = any (?)",
                TOKEN_PREFIX + "%", userIds);
        jdbc.update("delete from users where id = any (?)", (Object) userIds);
        return journeys.size();
    }

    private static double frac(double x) {
        return x - Math.floor(x);
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /** 경로 응답과 비슷한 분량(조회 비용 반영). 지하철 구간(trafficType 1). */
    static final String SNAPSHOT;

    static {
        StringBuilder legs = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            legs.append(i > 0 ? "," : "")
                    .append("{\"trafficType\":").append(i == 1 || i == 3 ? 1 : 3)
                    .append(",\"sectionTime\":10,\"distance\":1200,")
                    .append("\"startName\":\"부하역").append(i).append("\",")
                    .append("\"endName\":\"부하역").append(i + 1).append("\",")
                    .append("\"passStopList\":[");
            for (int s = 0; s < 20; s++) {
                legs.append(s > 0 ? "," : "")
                        .append("{\"index\":").append(s)
                        .append(",\"stationID\":\"S").append(s)
                        .append("\",\"stationName\":\"역이름").append(s).append("\"}");
            }
            legs.append("]}");
        }
        SNAPSHOT = "{\"pathType\":1,\"info\":{\"totalTime\":25,\"totalDistance\":8200},"
                + "\"subPath\":[" + legs + "]}";
    }
}
