package watoo.grd.nextroute.infrastructure.adapter.in.api.journey;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;
import watoo.grd.nextroute.application.journey.exception.ActiveJourneyExistsException;
import watoo.grd.nextroute.global.error.ErrorResponse;

class JourneyControllerAdviceTest {

  private JourneyControllerAdvice advice;
  private MockHttpServletRequest request;

  @BeforeEach
  void setUp() {
    advice = new JourneyControllerAdvice();
    request = new MockHttpServletRequest("POST", "/api/journeys");
  }

  @Test
  void activeJourneyExists_putsCurrentJourneyInDetailsOnly() {
    JourneySnapshot current = JourneySnapshot.of(
        42L, null, 1L, 0L, 1, 0, null, null,
        Instant.parse("2026-08-04T00:00:00Z"), List.of());

    ResponseEntity<ErrorResponse<JourneySnapshot>> response = advice.handleActiveExists(
        new ActiveJourneyExistsException(current), request);

    assertError(response, HttpStatus.CONFLICT, "ACTIVE_JOURNEY_EXISTS");
    assertThat(response.getBody().details()).isSameAs(current);
  }

  private static void assertError(ResponseEntity<? extends ErrorResponse<?>> response,
                                  HttpStatus status,
                                  String code) {
    assertThat(response.getStatusCode()).isEqualTo(status);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().code()).isEqualTo(code);
    assertThat(response.getBody().path()).isEqualTo("/api/journeys");
    assertThat(response.getBody().fieldErrors()).isEmpty();
  }
}
