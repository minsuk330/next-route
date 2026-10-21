package watoo.grd.nextroute.application.subway.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.subway.config.SubwayExpressTrackingProperties;
import watoo.grd.nextroute.application.subway.dto.SubwayExpressType;
import watoo.grd.nextroute.application.subway.dto.TrainKind;
import watoo.grd.nextroute.application.subway.service.SubwayTrainKindClassifier.Sighting;
import watoo.grd.nextroute.application.subway.service.SubwayTrainKindJudge.Judgement;
import watoo.grd.nextroute.application.subway.service.SubwayTrainKindJudge.Train;
import watoo.grd.nextroute.application.subway.service.SubwayTrainKindJudge.Verdict;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static watoo.grd.nextroute.application.subway.service.SubwayExpressFixtures.stationId;

/** 열차 종류·정차·추적 가능 판정(subway-express PR-S3). 운영 fixture 역 마스터·OTP 패턴과 번들 카탈로그로 돈다. */
class SubwayTrainKindJudgeTest {

    private final SubwayExpressTrackingProperties tracking = SubwayExpressFixtures.tracking(false);
    private final SubwayTrainKindJudge judge = SubwayExpressFixtures.judge(tracking);

    private static Train line9Express(String destination) {
        // 9호선 김포공항 → 중앙보훈병원 은 도착 API '상행'(S1 실측)
        return new Train("1009", "상행", "9600", destination, List.of(Sighting.ofTrainType("급행")));
    }

    private static Supplier<String> alight(String lineId, String name) {
        return () -> stationId(lineId, name);
    }

    @Test
    @DisplayName("일반 열차: 플래그와 무관하게 추적 가능, 탑승역 정차 true, 하차역은 보지 않음(null)")
    void normal() {
        Judgement j = judge.judge(new Train("1002", "내선", "2390", "성수", List.of(Sighting.ofTrainType("일반"))),
                "1002000222", () -> "x");

        assertThat(j.kind()).isEqualTo(TrainKind.NORMAL);
        assertThat(j.label()).isEqualTo("일반");
        assertThat(j.verdict()).isEqualTo(Verdict.TRACKABLE);
        assertThat(j.stopsAtBoarding()).isTrue();
        assertThat(j.stopsAtAlighting()).isNull();
        assertThat(j.serviceId()).isNull();
    }

    @Test
    @DisplayName("하차역(계획 체인 재쪼개기)은 급행으로 해석됐을 때만 읽는다")
    void alightingIsLazy() {
        AtomicInteger calls = new AtomicInteger();
        Supplier<String> alight = () -> {
            calls.incrementAndGet();
            return stationId("1009", "신논현");
        };

        judge.judge(new Train("1002", "내선", "2390", "성수", List.of(Sighting.ofTrainType("일반"))), "b", alight);
        judge.judge(new Train("1067", "상행", "2032", null, List.of(Sighting.ofTrainType("ITX"))), "b", alight);
        assertThat(calls).hasValue(0);

        judge.judge(line9Express("중앙보훈병원"), stationId("1009", "가양"), alight);
        assertThat(calls).hasValue(1);
    }

    @Test
    @DisplayName("9호선 급행 가양→신논현: 해석 성공·두 역 정차 — 플래그 꺼짐 NOT_TRACKABLE, 켜짐 TRACKABLE")
    void line9ExpressFlag() {
        Judgement off = judge.judge(line9Express("중앙보훈병원"), stationId("1009", "가양"), alight("1009", "신논현"));

        assertThat(off.kind()).isEqualTo(TrainKind.EXPRESS);
        assertThat(off.label()).isEqualTo("급행");
        assertThat(off.serviceId()).isEqualTo("1009-express");
        assertThat(off.stopsAtBoarding()).isTrue();
        assertThat(off.stopsAtAlighting()).isTrue();
        assertThat(off.verdict()).isEqualTo(Verdict.NOT_TRACKABLE);
        assertThat(off.trackable()).isFalse();

        tracking.setEnabled(true);
        Judgement on = judge.judge(line9Express("중앙보훈병원"), stationId("1009", "가양"), alight("1009", "신논현"));
        assertThat(on.verdict()).isEqualTo(Verdict.TRACKABLE);
        assertThat(on.trackable()).isTrue();
    }

    @Test
    @DisplayName("9호선 급행: 탑승역(등촌)·하차역(선유도)에 서지 않으면 플래그와 무관하게 SKIPS_STATION")
    void line9ExpressSkips() {
        for (boolean enabled : new boolean[]{false, true}) {
            tracking.setEnabled(enabled);
            Judgement board = judge.judge(line9Express("중앙보훈병원"), stationId("1009", "등촌"),
                    alight("1009", "신논현"));
            assertThat(board.stopsAtBoarding()).isFalse();
            assertThat(board.verdict()).isEqualTo(Verdict.SKIPS_STATION);

            Judgement alighting = judge.judge(line9Express("중앙보훈병원"), stationId("1009", "가양"),
                    alight("1009", "선유도"));
            assertThat(alighting.stopsAtBoarding()).isTrue();
            assertThat(alighting.stopsAtAlighting()).isFalse();
            assertThat(alighting.verdict()).isEqualTo(Verdict.SKIPS_STATION);
        }
    }

    @Test
    @DisplayName("종착 범위: 종합운동장행 급행으로 중앙보훈병원에 내리면 하차역 false — 행선지 이름이든 종착 역 id 든")
    void terminalRange() {
        tracking.setEnabled(true);
        Judgement byName = judge.judge(line9Express("종합운동장"), stationId("1009", "가양"),
                alight("1009", "중앙보훈병원"));
        assertThat(byName.stopsAtAlighting()).isFalse();
        assertThat(byName.verdict()).isEqualTo(Verdict.SKIPS_STATION);
        assertThat(byName.stopsAt(stationId("1009", "종합운동장"))).isTrue();
        assertThat(byName.stopsAt(stationId("1009", "석촌"))).isFalse();

        // 위치 기반 출발 기록은 행선지 이름 없이 종착 역 id 만 있다
        Judgement byTerminalId = judge.judge(line9Express(stationId("1009", "종합운동장")), stationId("1009", "가양"),
                alight("1009", "중앙보훈병원"));
        assertThat(byTerminalId.stopsAtAlighting()).isFalse();
        assertThat(byTerminalId.verdict()).isEqualTo(Verdict.SKIPS_STATION);

        Judgement inside = judge.judge(line9Express("종합운동장"), stationId("1009", "가양"),
                alight("1009", "신논현"));
        assertThat(inside.verdict()).isEqualTo(Verdict.TRACKABLE);
    }

    @Test
    @DisplayName("해석 실패 급행(서지 않는 역이 행선지 — 9호선 급행 '개화행')·하차역 모름은 NOT_TRACKABLE")
    void unresolvedExpress() {
        tracking.setEnabled(true);
        Judgement unresolved = judge.judge(new Train("1009", "하행", "9601", "개화",
                List.of(Sighting.ofTrainType("급행"))), stationId("1009", "가양"), alight("1009", "김포공항"));
        assertThat(unresolved.kind()).isEqualTo(TrainKind.EXPRESS);
        assertThat(unresolved.serviceId()).isNull();
        assertThat(unresolved.stopsAtBoarding()).isNull();
        assertThat(unresolved.verdict()).isEqualTo(Verdict.NOT_TRACKABLE);

        Judgement noAlight = judge.judge(line9Express("중앙보훈병원"), stationId("1009", "가양"), () -> null);
        assertThat(noAlight.stopsAtAlighting()).isNull();
        assertThat(noAlight.verdict()).isEqualTo(Verdict.NOT_TRACKABLE);
    }

    @Test
    @DisplayName("ITX·직통은 표시는 하되 추적 불가. ITX 는 위치 기록의 원시 directAt 9 로도 안다")
    void itxAndDirect() {
        tracking.setEnabled(true);
        Judgement itx = judge.judge(new Train("1067", "상행", "2032", null,
                List.of(Sighting.ofDirectAt(SubwayExpressType.UNKNOWN, "9"))), "x", () -> "y");
        assertThat(itx.kind()).isEqualTo(TrainKind.ITX);
        assertThat(itx.label()).isEqualTo("ITX-청춘");
        assertThat(itx.verdict()).isEqualTo(Verdict.NOT_TRACKABLE);

        Judgement direct = judge.judge(new Train("1065", "상행", "A1043", "인천공항2터미널",
                List.of(Sighting.ofTrainType("급행"))), "x", () -> "y");
        assertThat(direct.kind()).isEqualTo(TrainKind.DIRECT);
        assertThat(direct.label()).isEqualTo("직통");
        assertThat(direct.verdict()).isEqualTo(Verdict.NOT_TRACKABLE);
    }

    @Test
    @DisplayName("4호선 급행(4401–4408)은 '일반'·directAt 0 으로 와도 번호로 급행 — 사당 승차, 안산 하차는 정차, 한대앞은 통과")
    void line4ExpressByTrainNo() {
        tracking.setEnabled(true);
        Train train = new Train("1004", "하행", "4405", "오이도",
                List.of(Sighting.ofTrainType("일반"), Sighting.ofDirectAt(SubwayExpressType.NORMAL, "0")));

        Judgement ansan = judge.judge(train, stationId("1004", "사당"), alight("1004", "안산"));
        assertThat(ansan.kind()).isEqualTo(TrainKind.EXPRESS);
        assertThat(ansan.serviceId()).isEqualTo("1004-ansan");
        assertThat(ansan.verdict()).isEqualTo(Verdict.TRACKABLE);

        Judgement skip = judge.judge(train, stationId("1004", "사당"), alight("1004", "한대앞"));
        assertThat(skip.stopsAtAlighting()).isFalse();
        assertThat(skip.verdict()).isEqualTo(Verdict.SKIPS_STATION);
    }

    @Test
    @DisplayName("1호선 구로 급행(14xx)은 '급행'으로 와도 일반 — 추적 가능, 급행 서비스 없음")
    void line1AllStopExpressIsNormal() {
        Judgement j = judge.judge(new Train("1001", "하행", "1406", "구로", List.of(Sighting.ofTrainType("급행"))),
                stationId("1001", "용산"), () -> stationId("1001", "구로"));

        assertThat(j.kind()).isEqualTo(TrainKind.NORMAL);
        assertThat(j.verdict()).isEqualTo(Verdict.TRACKABLE);
        assertThat(j.serviceId()).isNull();
    }

    @Test
    @DisplayName("근거 없는 UNKNOWN: 일반 열차만 다니는 노선(2호선)은 예전처럼 고를 수 있고, 급행·ITX 노선에선 막는다")
    void unknown() {
        Judgement line2 = judge.judge(new Train("1002", "내선", "2394", "성수", List.of(Sighting.ofTrainType(null))),
                "x", () -> "y");
        assertThat(line2.kind()).isEqualTo(TrainKind.UNKNOWN);
        assertThat(line2.label()).isNull();
        assertThat(line2.verdict()).isEqualTo(Verdict.TRACKABLE);

        for (String lineId : List.of("1009", "1067", "1001")) {
            Judgement j = judge.judge(new Train(lineId, "상행", "9", null, List.of()), "x", () -> "y");
            assertThat(j.kind()).as(lineId).isEqualTo(TrainKind.UNKNOWN);
            assertThat(j.verdict()).as(lineId).isEqualTo(Verdict.NOT_TRACKABLE);
        }
    }
}
