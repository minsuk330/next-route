package watoo.grd.nextroute.infrastructure.adapter.in.api.journey;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.journey.dto.JourneyEtaView;
import watoo.grd.nextroute.application.journey.dto.JourneyEventOutcome;
import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;
import watoo.grd.nextroute.application.journey.service.JourneySnapshotEtaComposer;
import watoo.grd.nextroute.domain.journey.entity.JourneyEventResult;
import watoo.grd.nextroute.domain.journey.entity.JourneyKind;
import watoo.grd.nextroute.domain.journey.entity.JourneyStatus;
import watoo.grd.nextroute.infrastructure.adapter.in.api.journey.dto.JourneyEventResponse;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * 스냅샷 응답에 예측을 얹는 한 곳(#470)의 실패 경로를 고정한다.
 *
 * <p>쓰기 응답은 이미 커밋된 뒤라 예측 실패로 응답을 실패시키면 앱이 반영된 일을 실패로 읽는다. 조회는 반대로
 * 실패해야 한다 — 예측 없이 200 을 주면 이어지는 조건부 조회가 204 라 예측이 돌아오지 않는다.
 */
@DisplayName("스냅샷 응답에 예측을 얹는다")
class JourneySnapshotEtaAttacherTest {

    private static final Long USER_ID = 7L;
    private static final Instant JOURNEY_ETA = Instant.parse("2026-10-20T13:12:00Z");

    private JourneySnapshotEtaComposer composer;
    private JourneySnapshotEtaAttacher attacher;

    @BeforeEach
    void setUp() {
        composer = mock(JourneySnapshotEtaComposer.class);
        attacher = new JourneySnapshotEtaAttacher(composer);
    }

    @Test
    @DisplayName("쓰기 응답은 조회와 같은 합성 결과를 싣는다")
    void writeCarriesComposedEta() {
        JourneySnapshot bare = snapshot();
        given(composer.compose(USER_ID, bare)).willReturn(withEta(bare));

        JourneySnapshot result = attacher.forWrite(USER_ID, bare);

        assertThat(result.eta()).isNotNull();
        assertThat(result.eta().journeyEta()).isEqualTo(JOURNEY_ETA);
    }

    @Test
    @DisplayName("쓰기 응답은 합성이 실패해도 실패하지 않는다 — 예측 없이 내보내고 나머지는 그대로다")
    void writeSurvivesComposeFailure() {
        JourneySnapshot bare = snapshot();
        given(composer.compose(any(), any())).willThrow(new IllegalStateException("db down"));

        JourneySnapshot result = attacher.forWrite(USER_ID, bare);

        assertThat(result).isSameAs(bare);
        assertThat(result.eta()).isNull();
    }

    @Test
    @DisplayName("조회 응답은 합성이 실패하면 실패한다 — 앱이 다시 물어 예측을 받게 한다")
    void queryPropagatesComposeFailure() {
        given(composer.compose(any(), any())).willThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> attacher.forQuery(USER_ID, snapshot()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("이벤트 응답은 결과·거절 이유를 그대로 두고 스냅샷에만 예측을 얹는다")
    void eventKeepsResultAndReason() {
        JourneySnapshot bare = snapshot();
        given(composer.compose(USER_ID, bare)).willReturn(withEta(bare));

        JourneyEventResponse response = attacher.forEvent(USER_ID,
                new JourneyEventOutcome(JourneyEventResult.CONFLICT, "EVENT_ID_REUSED", bare));

        assertThat(response.result()).isEqualTo(JourneyEventResult.CONFLICT);
        assertThat(response.reason()).isEqualTo("EVENT_ID_REUSED");
        assertThat(response.journey().eta().journeyEta()).isEqualTo(JOURNEY_ETA);
    }

    private static JourneySnapshot snapshot() {
        return JourneySnapshot.of(1L, JourneyStatus.ACTIVE, JourneyKind.NAVIGATION, 1L, 0L, 1, 0,
                Instant.parse("2026-10-20T13:03:00Z"), null,
                Instant.parse("2026-10-20T13:00:00Z"), List.of());
    }

    private static JourneySnapshot withEta(JourneySnapshot snapshot) {
        return snapshot.withEta(new JourneyEtaView(null, null, null, JOURNEY_ETA, null, null),
                snapshot.recommendedPollSeconds());
    }
}
