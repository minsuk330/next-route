package watoo.grd.nextroute.global.error.code;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import watoo.grd.nextroute.global.error.ErrorCode;

@RequiredArgsConstructor
@Getter
public enum JourneyErrorCode implements ErrorCode {

  JOURNEY_NOT_FOUND("JOURNEY_NOT_FOUND", "여정을 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
  ACTIVE_JOURNEY_EXISTS("ACTIVE_JOURNEY_EXISTS", "이미 진행 중인 여정이 있습니다.", HttpStatus.CONFLICT),
  SNAPSHOT_EXPIRED("SNAPSHOT_EXPIRED", "검색 결과가 만료되었습니다.", HttpStatus.GONE),
  SNAPSHOT_OWNER_MISMATCH("SNAPSHOT_OWNER_MISMATCH", "다른 사용자의 검색 결과입니다.", HttpStatus.FORBIDDEN),
  JOURNEY_COMPILE_FAILED("JOURNEY_COMPILE_FAILED", "이 경로로는 여정을 시작할 수 없습니다.", HttpStatus.UNPROCESSABLE_ENTITY),
  STALE_ROUTE_REVISION("STALE_ROUTE_REVISION", "이전 경로 개정본입니다.", HttpStatus.CONFLICT),
  JOURNEY_NOT_ACTIVE("JOURNEY_NOT_ACTIVE", "진행 중인 여정이 아닙니다.", HttpStatus.CONFLICT),
  JOURNEY_LEG_NOT_FOUND("JOURNEY_LEG_NOT_FOUND", "여정 단계를 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
  STALE_PROGRESS_LEG("STALE_PROGRESS_LEG", "이미 지나온 단계의 진행입니다.", HttpStatus.CONFLICT),
  PROGRESS_TIMESTAMP_CONFLICT("PROGRESS_TIMESTAMP_CONFLICT",
      "같은 관측 시각에 다른 진행 내용이 왔습니다.", HttpStatus.CONFLICT),
  REROUTE_NOT_ELIGIBLE("REROUTE_NOT_ELIGIBLE", "지금은 재탐색할 수 있는 상태가 아닙니다.",
      HttpStatus.CONFLICT),
  REROUTE_PROPOSAL_NOT_FOUND("REROUTE_PROPOSAL_NOT_FOUND", "제안을 찾을 수 없습니다.",
      HttpStatus.NOT_FOUND),
  REROUTE_CANDIDATE_NOT_FOUND("REROUTE_CANDIDATE_NOT_FOUND", "그 후보가 제안에 없습니다.",
      HttpStatus.NOT_FOUND),
  /** 만료·상태·명령 번호·개정본·지나간 단계·스냅샷 불일치가 전부 이 코드로 온다. */
  REROUTE_PROPOSAL_STALE("REROUTE_PROPOSAL_STALE", "제안이 더 이상 유효하지 않습니다.",
      HttpStatus.CONFLICT),
  /**
   * 주행 중 중간 하차를 반영하려는데 <b>그 정류장에서 더는 내릴 수 없다</b>(PR-4).
   *
   * <p>{@link #REROUTE_PROPOSAL_STALE} 과 가르는 이유는 앱이 할 일이 다르기 때문이다. 이쪽은
   * 제안이 낡은 것이 아니라 <b>버스가 그 사이 더 간 것</b>이라, 앱은 목록을 새로 받아 남은 자리를
   * 다시 보여 주면 된다. 여정은 아무것도 바뀌지 않았다(트랜잭션째 되돌아간다).
   */
  REROUTE_ALIGHT_UNAVAILABLE("REROUTE_ALIGHT_UNAVAILABLE",
      "그 정류장에서는 내릴 수 없습니다. 목록을 새로 확인해 주세요.", HttpStatus.CONFLICT),
  SUBWAY_LEG_REQUIRED("SUBWAY_LEG_REQUIRED", "지하철 단계가 아닙니다.",
      HttpStatus.UNPROCESSABLE_ENTITY),
  SUBWAY_BINDING_NOT_FOUND("SUBWAY_BINDING_NOT_FOUND", "고정된 열차가 없습니다.",
      HttpStatus.NOT_FOUND),
  /**
   * 판정 워커가 그 고정 행을 잡고 있어 대기 상한 안에 못 들어갔다.
   *
   * <p><b>5xx 가 아닌 것이 계약이다.</b> 서버가 고장 난 것이 아니라 지금은 순서가 아니라는 뜻이고,
   * 앱은 같은 관측을 그대로 다시 보내면 된다 — 감사행 유일성이 중복을 흡수한다.
   */
  SUBWAY_BINDING_BUSY("SUBWAY_BINDING_BUSY", "잠시 후 다시 시도해 주세요.",
      HttpStatus.CONFLICT),
  /**
   * Live Activity 토큰을 등록하려는데 그 기기의 FCM 토큰이 우리 표에 없다.
   *
   * <p>등록 순서가 있다 — 기기 주소({@code POST /api/me/push-token})가 먼저고 Activity 주소가
   * 나중이다. 둘이 함께 있어야 발송이 성립하므로, 없는 채로 받아 두면 조용히 못 보내는 행이
   * 쌓인다.
   */
  PUSH_TOKEN_NOT_FOUND("PUSH_TOKEN_NOT_FOUND", "먼저 기기 푸시 토큰을 등록해 주세요.",
      HttpStatus.BAD_REQUEST);

  private final String code;
  private final String message;
  private final HttpStatus httpStatus;






}
