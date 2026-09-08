package watoo.grd.nextroute.application.route.dto.v2;

/** 요금: availability(KNOWN/KNOWN_ZERO/UNKNOWN) + nullable amount. UNKNOWN 이면 amount=null(0 아님). */
public record FareV2(String availability, Integer amount) {
}
