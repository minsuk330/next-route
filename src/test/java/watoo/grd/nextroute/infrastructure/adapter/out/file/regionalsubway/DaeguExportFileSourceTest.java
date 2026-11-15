package watoo.grd.nextroute.infrastructure.adapter.out.file.regionalsubway;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.regionalsubway.dto.RegionalDayType;
import watoo.grd.nextroute.application.regionalsubway.dto.RegionalDeparture;
import watoo.grd.nextroute.application.regionalsubway.dto.RegionalStationSeed;
import watoo.grd.nextroute.application.regionalsubway.service.RegionalStationSeedCatalog;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 커밋된 대구 내보내기 파일(2026-11-16)이 공식 응답과 같게 읽히는지. 기대값은 같은 날 따로 받은 공식 API 응답과 대조한 값.
 */
class DaeguExportFileSourceTest {

    private final RegionalStationSeedCatalog catalog = new RegionalStationSeedCatalog(new ObjectMapper());
    private final DaeguExportFileSource source = new DaeguExportFileSource(new ObjectMapper(), catalog);

    private List<RegionalDeparture> toward(String line, String station, String terminalSide, RegionalDayType day) {
        RegionalStationSeed s = catalog.findByName(line, station);
        List<RegionalStationSeed> l = catalog.line(line);
        int endSeq = terminalSide.equals("first") ? 0 : l.size() - 1;
        return source.fetch(s).stream()
                .filter(d -> d.dayType() == day)
                .filter(d -> {
                    int t = catalog.findByName(line, d.terminalName()).seq();
                    return endSeq == 0 ? t < s.seq() : t > s.seq();
                })
                .toList();
    }

    @Test
    @DisplayName("반월당(2호선) 문양 방면: 토요일 140편, 공휴일 124편, 공휴일 막차 23:45 이곡행 — 공식 API 응답과 같다")
    void banwoldangLine2() {
        // 대구2 시드 순서: 영남대(0) → 문양(28)
        List<RegionalDeparture> sat = toward("DG2", "반월당", "last", RegionalDayType.SA);
        List<RegionalDeparture> hol = toward("DG2", "반월당", "last", RegionalDayType.HO);
        assertThat(sat).hasSize(140);
        assertThat(hol).hasSize(124);
        assertThat(hol.get(0).depSeconds()).isEqualTo(5 * 3600 + 42 * 60);
        assertThat(hol.get(hol.size() - 1)).isEqualTo(new RegionalDeparture(RegionalDayType.HO, 23 * 3600 + 45 * 60, "이곡"));
    }

    @Test
    @DisplayName("화원(1호선) 평일 설화명곡 방면(상행) 첫차 06:12, 막차 24:02(=86520), 하양 방면(하행) 첫차 05:27. 종착역 반대 방향 '-' 는 버린다")
    void hwawonAndTerminals() {
        // 대구1 시드 순서: 하양(0) → 설화명곡(34). 상행(설화명곡 방면)은 seq 증가 쪽
        assertThat(toward("DG1", "화원", "first", RegionalDayType.WD).get(0).depSeconds()).isEqualTo(5 * 3600 + 27 * 60);
        List<RegionalDeparture> wd = toward("DG1", "화원", "last", RegionalDayType.WD);
        assertThat(wd.get(0).depSeconds()).isEqualTo(6 * 3600 + 12 * 60);
        assertThat(wd.get(wd.size() - 1).depSeconds()).isEqualTo(86400 + 2 * 60);
        // 설화명곡은 한쪽 방향만 열차가 있다(평일 147편)
        RegionalStationSeed seolhwa = catalog.findByName("DG1", "설화명곡");
        assertThat(source.fetch(seolhwa).stream().filter(d -> d.dayType() == RegionalDayType.WD)).hasSize(147);
    }

    @Test
    @DisplayName("괄호 없는 열차는 그 방향 노선 끝이 종착 — 반월당(1호선) 하행 막차 23:42 방촌행, 하양행은 끝역")
    void defaultTerminalIsLineEnd() {
        List<RegionalDeparture> down = toward("DG1", "반월당", "first", RegionalDayType.WD);
        assertThat(down).extracting(RegionalDeparture::terminalName).contains("하양", "안심", "방촌");
        assertThat(down.get(down.size() - 1)).isEqualTo(new RegionalDeparture(RegionalDayType.WD, 23 * 3600 + 42 * 60, "방촌"));
    }
}
