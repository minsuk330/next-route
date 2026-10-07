package watoo.grd.nextroute.application.journey.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.domain.journey.entity.JourneyKind;
import watoo.grd.nextroute.domain.journey.entity.JourneyStatus;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * {@code kind} 는 레코드 재조립 메서드를 지나도 남아야 한다.
 *
 * <p>{@code withEta}·{@code withSubwayGuidance} 는 레코드를 통째로 다시 만든다. 새 필드를 하나 빠뜨리면
 * 예측이 붙은 응답에서만 조용히 {@code null} 이 된다(M7 의 {@code subwayGuidance} 함정과 같은 모양).
 */
@DisplayName("스냅샷 kind 보존")
class JourneySnapshotKindTest {

    private static final Instant NOW = Instant.parse("2026-10-07T03:00:00Z");

    @Test
    @DisplayName("withEta 후에도 kind 가 남는다")
    void withEtaKeepsKind() {
        JourneySnapshot snapshot = base(JourneyKind.TRACK)
                .withEta(mock(JourneyEtaView.class), 5);

        assertThat(snapshot.kind()).isEqualTo(JourneyKind.TRACK);
    }

    @Test
    @DisplayName("withSubwayGuidance 후에도 kind 가 남는다")
    void withSubwayGuidanceKeepsKind() {
        JourneySnapshot snapshot = base(JourneyKind.TRACK)
                .withSubwayGuidance(mock(SubwayGuidance.class));

        assertThat(snapshot.kind()).isEqualTo(JourneyKind.TRACK);
        assertThat(snapshot.subwayGuidance()).isNotNull();
    }

    @Test
    @DisplayName("둘을 이어 붙여도 kind 와 안내가 함께 남는다")
    void chainedKeepsBoth() {
        SubwayGuidance guidance = mock(SubwayGuidance.class);
        JourneySnapshot snapshot = base(JourneyKind.NAVIGATION)
                .withSubwayGuidance(guidance)
                .withEta(mock(JourneyEtaView.class), 5);

        assertThat(snapshot.kind()).isEqualTo(JourneyKind.NAVIGATION);
        assertThat(snapshot.subwayGuidance()).isSameAs(guidance);
    }

    private static JourneySnapshot base(JourneyKind kind) {
        return JourneySnapshot.of(1L, JourneyStatus.ACTIVE, kind, 1L, 0L, 1, 0,
                NOW.plusSeconds(180), null, NOW, List.of());
    }
}
