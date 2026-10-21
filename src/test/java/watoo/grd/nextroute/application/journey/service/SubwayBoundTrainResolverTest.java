package watoo.grd.nextroute.application.journey.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import watoo.grd.nextroute.application.subway.dto.SubwayRealtimeSnapshot;
import watoo.grd.nextroute.application.subway.dto.SubwayRealtimeTrain;
import watoo.grd.nextroute.application.subway.dto.TrainIdentity;
import watoo.grd.nextroute.application.subway.port.out.SubwayRealtimeCachePort;
import watoo.grd.nextroute.application.subway.port.out.SubwayTrainLogPort;
import watoo.grd.nextroute.application.subway.service.SubwayExpressFixtures;
import watoo.grd.nextroute.domain.journey.entity.SubwayBindingTrainKind;
import watoo.grd.nextroute.domain.journey.entity.SubwayLegBinding;
import watoo.grd.nextroute.domain.journey.repository.SubwayLegBindingRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 배포 전 바인딩(V72 종류 null)의 지연 분류와 급행 패턴 재구성(subway-express PR-S4). 운영 fixture·번들 카탈로그로 돈다.
 */
@DisplayName("묶인 열차 종류 — 배포 전 행 분류·급행 패턴")
class SubwayBoundTrainResolverTest {

    private static final LocalDate DAY = LocalDate.parse("2026-10-21");

    private final SubwayLegBindingRepository repository = mock(SubwayLegBindingRepository.class);
    private final SubwayRealtimeCachePort cache = mock(SubwayRealtimeCachePort.class);
    private final SubwayTrainLogPort trainLog = mock(SubwayTrainLogPort.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final SubwayBoundTrainResolver resolver = new SubwayBoundTrainResolver(repository,
            SubwayExpressFixtures.classifier(), SubwayExpressFixtures.resolver(), cache, trainLog, meters);

    private static SubwayLegBinding legacy(String trainNo, String lineId, String direction) {
        SubwayLegBinding binding = SubwayLegBinding.selected(10L, 1L, 1, 1, trainNo, DAY, lineId, direction);
        ReflectionTestUtils.setField(binding, "id", 5L);
        return binding;
    }

    private void rows(SubwayRealtimeTrain... rows) {
        given(cache.readSnapshot()).willReturn(Optional.of(SubwayRealtimeSnapshot.builder().trains(List.of(rows)).build()));
    }

    private static SubwayRealtimeTrain row(String lineId, String direction, String trainNo, String trainType,
                                           String destination) {
        return SubwayRealtimeTrain.builder().lineId(lineId).direction(direction).trainNo(trainNo)
                .stationId(lineId + "000910").trainType(trainType).destinationName(destination).build();
    }

    @Test
    @DisplayName("배포 전 급행 바인딩: 실시간 '급행' → EXPRESS·1009-express 로 한 번 적는다(세대 그대로), 패턴을 돌려준다")
    void classifiesLegacyExpressBinding() {
        SubwayLegBinding binding = legacy("9600", "1009", "상행");
        rows(row("1009", "상행", "9600", "급행", "중앙보훈병원"), row("1009", "상행", "9100", "일반", "중앙보훈병원"));
        given(trainLog.lastSeen(any())).willReturn(Optional.empty());
        given(repository.classifyIfUnclassified(5L, 0, "EXPRESS", "1009-express", "2026-10-21")).willReturn(1);

        SubwayBoundTrainResolver.BoundTrain bound = resolver.resolve(binding, identity(binding));

        verify(repository).classifyIfUnclassified(5L, 0, "EXPRESS", "1009-express", "2026-10-21");
        assertThat(binding.getTrainKind()).isEqualTo(SubwayBindingTrainKind.EXPRESS);
        assertThat(binding.getVersion()).isZero();
        assertThat(bound.kind()).isEqualTo(SubwayBindingTrainKind.EXPRESS);
        assertThat(bound.pattern()).isNotNull();
        assertThat(bound.pattern().stopsAt(SubwayExpressFixtures.stationId("1009", "염창"))).isTrue();
        assertThat(bound.pattern().stopsAt(SubwayExpressFixtures.stationId("1009", "등촌"))).isFalse();
        assertThat(bound.label()).isEqualTo("급행");
        assertThat(bound.kindName()).isEqualTo("EXPRESS");

        // 다음 tick 에는 다시 분류하지 않는다
        resolver.resolve(binding, identity(binding));
        verify(repository).classifyIfUnclassified(anyLong(), anyInt(), anyString(), any(), any());
    }

    @Test
    @DisplayName("근거가 하나도 없으면 UNKNOWN — 링은 계획 체인(예전 동작)")
    void noEvidenceIsUnknown() {
        SubwayLegBinding binding = legacy("2390", "1002", "내선");
        given(cache.readSnapshot()).willReturn(Optional.empty());
        given(trainLog.lastSeen(any())).willReturn(Optional.empty());
        given(repository.classifyIfUnclassified(5L, 0, "UNKNOWN", null, null)).willReturn(1);

        SubwayBoundTrainResolver.BoundTrain bound = resolver.resolve(binding, identity(binding));

        assertThat(bound.kind()).isEqualTo(SubwayBindingTrainKind.UNKNOWN);
        assertThat(bound.label()).isNull();
        assertThat(bound.pattern()).isNull();
    }

    @Test
    @DisplayName("'일반' 행 → NORMAL")
    void normalRows() {
        SubwayLegBinding binding = legacy("2390", "1002", "내선");
        rows(row("1002", "내선", "2390", "일반", "성수"));
        given(trainLog.lastSeen(any())).willReturn(Optional.empty());
        given(repository.classifyIfUnclassified(5L, 0, "NORMAL", null, null)).willReturn(1);

        assertThat(resolver.resolve(binding, identity(binding)).kind()).isEqualTo(SubwayBindingTrainKind.NORMAL);
    }

    @Test
    @DisplayName("급행인데 서비스를 못 정하면(개화행 급행 — 개화에 서지 않음) UNKNOWN + subway.express.unresolved")
    void unresolvedLegacyExpress() {
        SubwayLegBinding binding = legacy("9601", "1009", "하행");
        rows(row("1009", "하행", "9601", "급행", "개화"));
        given(trainLog.lastSeen(any())).willReturn(Optional.empty());
        given(repository.classifyIfUnclassified(5L, 0, "UNKNOWN", null, null)).willReturn(1);

        assertThat(resolver.resolve(binding, identity(binding)).kind()).isEqualTo(SubwayBindingTrainKind.UNKNOWN);
        assertThat(meters.counter("subway.express.unresolved", "line", "1009", "stage", "classify").count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("그 사이 다른 열차를 골랐으면(조건부 UPDATE 0 행) 메모리 값도 그대로 미분류")
    void concurrentReselectLeavesUnclassified() {
        SubwayLegBinding binding = legacy("2390", "1002", "내선");
        rows(row("1002", "내선", "2390", "일반", "성수"));
        given(trainLog.lastSeen(any())).willReturn(Optional.empty());
        given(repository.classifyIfUnclassified(anyLong(), anyInt(), anyString(), any(), any())).willReturn(0);

        assertThat(resolver.resolve(binding, identity(binding)).kind()).isNull();
        assertThat(binding.getTrainKind()).isNull();
    }

    @Test
    @DisplayName("묶인 급행 서비스가 카탈로그에 없으면 패턴 없음 + 지표(stage=ring) — 빈 체인으로 링을 그리지 않는다")
    void missingServiceIsUnresolved() {
        SubwayLegBinding binding = SubwayLegBinding.selected(10L, 1L, 1, 1, "9600", DAY, "1009", "상행",
                SubwayBindingTrainKind.EXPRESS, "1009-gone", "2026-01-01");

        SubwayBoundTrainResolver.BoundTrain bound = resolver.resolve(binding, identity(binding));

        assertThat(bound.kind()).isEqualTo(SubwayBindingTrainKind.EXPRESS);
        assertThat(bound.pattern()).isNull();
        assertThat(meters.counter("subway.express.unresolved", "line", "1009", "stage", "ring").count()).isEqualTo(1.0);
        verify(repository, never()).classifyIfUnclassified(anyLong(), anyInt(), anyString(), any(), any());
    }

    @Test
    @DisplayName("일반 바인딩은 실시간을 읽지 않는다")
    void classifiedNormalDoesNotReadRealtime() {
        SubwayLegBinding binding = SubwayLegBinding.selected(10L, 1L, 1, 1, "2390", DAY, "1002", "내선",
                SubwayBindingTrainKind.NORMAL, null, null);

        SubwayBoundTrainResolver.BoundTrain bound = resolver.resolve(binding, identity(binding));

        assertThat(bound.kind()).isEqualTo(SubwayBindingTrainKind.NORMAL);
        assertThat(bound.label()).isEqualTo("일반");
        verify(cache, never()).readSnapshot();
    }

    private static TrainIdentity identity(SubwayLegBinding binding) {
        return new TrainIdentity(binding.getTrainServiceDate(), binding.getTrainLineId(), binding.getTrainDirection(),
                binding.getTrainNo());
    }
}
