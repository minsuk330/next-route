package watoo.grd.nextroute.application.journey.dto;

import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.domain.journey.entity.JourneyEventType;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** bus-boarding-flow S1: leg 를 넘긴 근거(trigger)는 기록용 — 모르는 값을 거절하지 않고 버린다. */
class JourneyCommandTest {

    private static JourneyCommand withTrigger(String trigger) {
        return new JourneyCommand(UUID.randomUUID(), JourneyEventType.CLIENT_LEG_TRANSITION_DETECTED, 1L, 1, 0, 1,
                Instant.EPOCH, trigger);
    }

    @Test
    void knownTriggersAreKept() {
        for (String trigger : JourneyCommand.TRIGGERS) {
            assertThat(withTrigger(trigger).trigger()).isEqualTo(trigger);
        }
        assertThat(JourneyCommand.TRIGGERS).contains("STOP_NEAR", "MANUAL_LIVE_ACTIVITY", "MISSED_SAID_BOARDED");
    }

    @Test
    void unknownBlankOrOversizedTriggersBecomeNull() {
        assertThat(withTrigger("SOMETHING_NEW").trigger()).isNull();
        assertThat(withTrigger("").trigger()).isNull();
        assertThat(withTrigger("stop_near").trigger()).isNull();          // 대소문자를 맞춰 주지 않는다 — 계약은 정확한 값
        assertThat(withTrigger("X".repeat(10_000)).trigger()).isNull();
        assertThat(withTrigger(null).trigger()).isNull();
    }

    @Test
    void theDocumentedValuesAreTheAcceptedOnes() throws Exception {
        // API 문서(@Schema)의 값 목록은 손으로 적은 사본이다. 허용 목록과 어긋나면 여기서 잡는다.
        io.swagger.v3.oas.annotations.media.Schema schema =
                watoo.grd.nextroute.infrastructure.adapter.in.api.journey.dto.ApplyJourneyEventRequest.class
                        .getDeclaredField("trigger")
                        .getAnnotation(io.swagger.v3.oas.annotations.media.Schema.class);

        assertThat(schema).isNotNull();
        assertThat(java.util.Set.of(schema.allowableValues())).isEqualTo(JourneyCommand.TRIGGERS);
    }

    @Test
    void commandsWithoutATriggerStillBuild() {
        JourneyCommand old = new JourneyCommand(UUID.randomUUID(), JourneyEventType.JOURNEY_CANCELED, 1L, 1, null, null,
                Instant.EPOCH);

        assertThat(old.trigger()).isNull();
    }
}
