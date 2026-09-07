package watoo.grd.nextroute.application.route.routing;

/**
 * 요금. {@code amount} 는 KNOWN 일 때만 의미 있고, UNKNOWN 이면 null 이다.
 * 0 원 생성 금지 — 무료는 KNOWN_ZERO 로만 표현한다.
 */
public record Fare(FareAvailability availability, Integer amount) {

    public Fare {
        if (availability == FareAvailability.KNOWN && amount == null) {
            throw new IllegalArgumentException("KNOWN fare must carry amount");
        }
        if (availability == FareAvailability.KNOWN_ZERO) {
            amount = 0;
        }
        if (availability == FareAvailability.UNKNOWN) {
            amount = null;
        }
    }

    public static Fare unknown() {
        return new Fare(FareAvailability.UNKNOWN, null);
    }

    public static Fare free() {
        return new Fare(FareAvailability.KNOWN_ZERO, 0);
    }

    public static Fare known(int amount) {
        return new Fare(FareAvailability.KNOWN, amount);
    }
}
