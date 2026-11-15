package watoo.grd.nextroute.application.regionalsubway.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.regionalsubway.dto.RegionalDayType;
import watoo.grd.nextroute.application.regionalsubway.dto.RegionalDeparturesView;
import watoo.grd.nextroute.application.regionalsubway.dto.RegionalDeparturesView.Departure;
import watoo.grd.nextroute.application.regionalsubway.dto.RegionalDeparturesView.Direction;
import watoo.grd.nextroute.application.regionalsubway.dto.RegionalStationSeed;
import watoo.grd.nextroute.application.regionalsubway.port.out.RegionalDepartureReadPort;
import watoo.grd.nextroute.application.subway.service.HolidayCalendar;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.IntPredicate;

/**
 * 공식 시간표로 다음 열차를 고른다.
 *
 * <p>방향은 종착역 seq 로 정한다. 내릴 역이 seq 증가 쪽이면 {@code terminal_seq >= alight_seq} 인 열차만
 * → 내릴 역 전에 끝나는 열차(대구1 안심행, 대구2 이곡행, 광주 소태행)는 빠진다.
 * 막차 뒤에는 다음 서비스일의 요일을 다시 판정해(토 23:50 → 일요일 공휴일표) 첫차로 채운다.
 */
@Service
@RequiredArgsConstructor
public class RegionalDepartureQueryService {

    private final RegionalStationSeedCatalog catalog;
    private final RegionalDepartureReadPort departures;
    private final HolidayCalendar holidayCalendar;

    /**
     * @param alightSeq 내릴 역 seq. null 이면 역 화면(두 방향)
     * @param at        기준 시각(검색 결과는 그 leg 에 닿는 시각, 역 화면은 지금)
     */
    public Optional<RegionalDeparturesView> next(String stationKey, Integer alightSeq, int limit, Instant at) {
        Optional<RegionalStationSeed> found = catalog.findByKey(stationKey);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        RegionalStationSeed station = found.get();
        int lim = Math.max(1, Math.min(limit, 10));
        RegionalServiceDay today = RegionalServiceDay.of(at);
        DayRows todayRows = rows(station, today.date());
        DayRows nextRows = rows(station, today.date().plusDays(1));

        List<Direction> directions = new ArrayList<>();
        if (alightSeq != null) {
            if (alightSeq == station.seq()) {
                return Optional.of(new RegionalDeparturesView(station, todayRows.requested, todayRows.used, List.of()));
            }
            boolean increasing = alightSeq > station.seq();
            IntPredicate reaches = increasing ? t -> t >= alightSeq : t -> t <= alightSeq;
            directions.add(direction(station, increasing, reaches, today, todayRows, nextRows, lim, at));
        } else {
            directions.add(direction(station, false, t -> t < station.seq(), today, todayRows, nextRows, lim, at));
            directions.add(direction(station, true, t -> t > station.seq(), today, todayRows, nextRows, lim, at));
            directions.removeIf(d -> d.departures().isEmpty() && isLineEnd(station, d.increasing()));
        }
        return Optional.of(new RegionalDeparturesView(station, todayRows.requested, todayRows.used, directions));
    }

    /**
     * 검색 결과·여정 leg 용: OTP 정류장 id(타는 역, 내릴 역)로. 둘 다 같은 지방 노선이어야 한다. 서울 역이면 empty.
     *
     * @param at 그 leg 에 닿는 시각(검색은 plannedBoardAt − plannedWaitSeconds, 첫 leg 는 출발 시각)
     */
    public Optional<RegionalDeparturesView> nextForLeg(String boardGtfsStopId, String alightGtfsStopId, int limit, Instant at) {
        Optional<RegionalStationSeed> board = catalog.findByGtfsStopId(boardGtfsStopId);
        Optional<RegionalStationSeed> alight = catalog.findByGtfsStopId(alightGtfsStopId);
        if (board.isEmpty() || alight.isEmpty() || !board.get().lineCode().equals(alight.get().lineCode())) {
            return Optional.empty();
        }
        return next(board.get().stationKey(), alight.get().seq(), limit, at);
    }

    private Direction direction(RegionalStationSeed station, boolean increasing, IntPredicate reaches,
                                RegionalServiceDay today, DayRows todayRows, DayRows nextRows, int limit, Instant at) {
        List<Departure> out = new ArrayList<>();
        collect(out, todayRows, today.date(), reaches, today.seconds(), limit, at);
        if (out.size() < limit) {
            collect(out, nextRows, today.date().plusDays(1), reaches, Integer.MIN_VALUE, limit, at);
        }
        return new Direction(catalog.lineEnd(station.lineCode(), increasing).stationName(), increasing, out);
    }

    private static void collect(List<Departure> out, DayRows day, LocalDate serviceDate, IntPredicate reaches,
                                int fromSeconds, int limit, Instant at) {
        for (RegionalDepartureReadPort.Row row : day.rows) {
            if (out.size() >= limit) {
                return;
            }
            if (row.depSeconds() < fromSeconds || !reaches.test(row.terminalSeq())) {
                continue;
            }
            Instant scheduled = RegionalServiceDay.instantOf(serviceDate, row.depSeconds());
            int minutes = (int) Math.max(0, Duration.between(at, scheduled).toMinutes());
            out.add(new Departure(scheduled, minutes, row.terminalName(), row.terminalSeq()));
        }
    }

    private DayRows rows(RegionalStationSeed station, LocalDate serviceDate) {
        RegionalDayType requested = RegionalDayTypePolicy.resolve(station.lineCode(), serviceDate, holidayCalendar);
        for (RegionalDayType candidate : RegionalDayTypePolicy.candidates(station.lineCode(), requested)) {
            List<RegionalDepartureReadPort.Row> rows = departures.departures(station.stationKey(), candidate.name());
            if (!rows.isEmpty()) {
                return new DayRows(requested, candidate, rows);
            }
        }
        return new DayRows(requested, null, List.of());
    }

    private boolean isLineEnd(RegionalStationSeed station, boolean increasing) {
        return catalog.lineEnd(station.lineCode(), increasing).seq() == station.seq();
    }

    private record DayRows(RegionalDayType requested, RegionalDayType used, List<RegionalDepartureReadPort.Row> rows) {
    }
}
