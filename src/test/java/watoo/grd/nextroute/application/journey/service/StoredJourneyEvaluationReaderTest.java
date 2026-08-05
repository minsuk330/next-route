package watoo.grd.nextroute.application.journey.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import watoo.grd.nextroute.application.journey.config.JourneyEvaluatorProperties;
import watoo.grd.nextroute.application.journey.dto.CurrentLegArrival;
import watoo.grd.nextroute.application.journey.dto.TransferRiskAssessment;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegState;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;
import watoo.grd.nextroute.domain.journey.entity.TransferConnection;
import watoo.grd.nextroute.domain.journey.entity.TransferStatus;
import watoo.grd.nextroute.domain.journey.repository.TransferConnectionRepository;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * 저장된 판정을 언제 믿고 언제 버리는지.
 *
 * <p>기준이 <b>가리키는 시각</b>이다. 계산이 오래됐어도 도착이 아직 미래면 쓸 만하고, 방금
 * 계산했어도 도착이 지났으면 쓸모없다. 이미 떠난 버스 시각을 내려보내면 사용자는 오지 않을 차를
 * 기다린다.
 */
@DisplayName("저장된 평가 결과 읽기")
class StoredJourneyEvaluationReaderTest {

    private static final long JOURNEY_ID = 7L;
    private static final int REVISION = 1;
    private static final Instant NOW = Instant.parse("2026-08-05T09:00:00Z");

    private final TransferConnectionRepository transferRepository =
            mock(TransferConnectionRepository.class);
    private final JourneyEvaluatorProperties properties = new JourneyEvaluatorProperties();
    private final StoredJourneyEvaluationReader reader =
            new StoredJourneyEvaluationReader(transferRepository, properties);

    @Test
    @DisplayName("도착이 아직 미래면 저장된 값을 그대로 쓴다")
    void futureArrivalIsUsable() {
        givenStored(TransferStatus.CATCHABLE, NOW.plusSeconds(300), NOW.minusSeconds(20), 150);

        TransferRiskAssessment assessment = read();

        assertThat(assessment.status()).isEqualTo(TransferStatus.CATCHABLE);
        assertThat(assessment.nextBusArrivalAt()).isEqualTo(NOW.plusSeconds(300));
        assertThat(assessment.transferSlackSeconds()).isEqualTo(150);
    }

    @Test
    @DisplayName("계산이 오래됐어도 도착이 미래면 쓴다 — 낡음의 기준은 시간이 아니다")
    void oldCalculationWithFutureArrivalIsUsable() {
        givenStored(TransferStatus.CATCHABLE, NOW.plusSeconds(600), NOW.minusSeconds(200), 150);

        assertThat(read().status()).isEqualTo(TransferStatus.CATCHABLE);
    }

    @Test
    @DisplayName("도착이 이미 지났으면 모름으로 바꾼다 — 그 버스는 떠났다")
    void pastArrivalBecomesUnknown() {
        givenStored(TransferStatus.CATCHABLE, NOW.minusSeconds(120), NOW.minusSeconds(150), 150);

        TransferRiskAssessment assessment = read();

        assertThat(assessment.status()).isEqualTo(TransferStatus.UNKNOWN);
        assertThat(assessment.nextBusArrivalAt()).isNull();
        assertThat(assessment.transferSlackSeconds()).isNull();
        assertThat(assessment.userReadyAt()).isNull();
    }

    @Test
    @DisplayName("계산이 상한을 넘게 오래됐으면 도착이 미래여도 버린다 — 평가기가 멈춘 경우다")
    void tooOldCalculationBecomesUnknown() {
        Instant longAgo = NOW.minusSeconds(properties.getStaleAfterSeconds() + 60);
        givenStored(TransferStatus.CATCHABLE, NOW.plusSeconds(3600), longAgo, 150);

        assertThat(read().status()).isEqualTo(TransferStatus.UNKNOWN);
    }

    @Test
    @DisplayName("아직 평가된 적 없는 환승은 그대로 모름이다")
    void neverEvaluatedStaysUnknown() {
        givenStored(TransferStatus.UNKNOWN, null, null, null);

        assertThat(read().status()).isEqualTo(TransferStatus.UNKNOWN);
    }

    @Test
    @DisplayName("기다리는 단계의 저장된 도착 예측을 읽는다")
    void readsBoardingArrival() {
        JourneyLeg waitLeg = waitLeg(NOW.plusSeconds(240), NOW.minusSeconds(20));

        CurrentLegArrival arrival = reader.boardingArrival(waitLeg, NOW);

        assertThat(arrival.arrivalAt()).isEqualTo(NOW.plusSeconds(240));
        assertThat(arrival.remainingSeconds()).isEqualTo(240);
        assertThat(arrival.source()).isEqualTo(CurrentLegArrival.Source.REALTIME_ARRIVAL);
        assertThat(arrival.quality()).isEqualTo(CurrentLegArrival.Quality.GOOD);
    }

    @Test
    @DisplayName("기다리는 단계의 예측도 도착이 지났으면 모름이다")
    void pastBoardingArrivalIsUnknown() {
        JourneyLeg waitLeg = waitLeg(NOW.minusSeconds(60), NOW.minusSeconds(90));

        assertThat(reader.boardingArrival(waitLeg, NOW).quality())
                .isEqualTo(CurrentLegArrival.Quality.UNKNOWN);
    }

    @Test
    @DisplayName("예측이 아예 없으면 모름이다")
    void noStoredPredictionIsUnknown() {
        assertThat(reader.boardingArrival(waitLeg(null, null), NOW).quality())
                .isEqualTo(CurrentLegArrival.Quality.UNKNOWN);
        assertThat(reader.boardingArrival(null, NOW).quality())
                .isEqualTo(CurrentLegArrival.Quality.UNKNOWN);
    }

    // ── 준비 ────────────────────────────────────────────────────────────────

    private TransferRiskAssessment read() {
        return reader.assessments(JOURNEY_ID, REVISION, NOW).get(0);
    }

    private void givenStored(TransferStatus status, Instant nextBusArrivalAt,
                             Instant calculatedAt, Integer slackSeconds) {
        TransferConnection transfer = TransferConnection.builder()
                .journeyId(JOURNEY_ID).routeRevision(REVISION)
                .transferIndex(0).fromLegIndex(0).walkLegIndex(1).toLegIndex(2)
                .status(status)
                .build();
        transfer.applyAssessment(status, NOW.plusSeconds(120), NOW.plusSeconds(150),
                nextBusArrivalAt, slackSeconds,
                TransferAssessmentWriter.SOURCE_REALTIME, "GOOD", calculatedAt, calculatedAt);
        given(transferRepository
                .findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByTransferIndex(
                        anyLong(), anyInt()))
                .willReturn(List.of(transfer));
    }

    private JourneyLeg waitLeg(Instant predictedEndAt, Instant observedAt) {
        JourneyLeg leg = JourneyLeg.builder()
                .journeyId(JOURNEY_ID).routeRevision(REVISION).legIndex(2)
                .type(JourneyLegType.WAIT_BUS).state(JourneyLegState.ACTIVE)
                .build();
        if (predictedEndAt != null) {
            leg.applyBoardingPrediction(predictedEndAt,
                    CurrentLegArrival.Source.REALTIME_ARRIVAL.name(), "GOOD", observedAt);
        }
        ReflectionTestUtils.setField(leg, "id", 11L);
        return leg;
    }
}
