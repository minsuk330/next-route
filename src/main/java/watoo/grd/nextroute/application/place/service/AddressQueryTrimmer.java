package watoo.grd.nextroute.application.place.service;

import java.util.regex.Pattern;

/**
 * 주소 검색어 끝의 동·층·호수와 괄호 설명을 뗀다. 카카오 주소 검색은 "중앙로 1305-30 101동 1203호" 같은
 * 검색어를 0건으로 돌려주지만, 뒤를 떼면 건물을 찾는다.
 *
 * <p>떼는 단위는 앞이 공백·쉼표·괄호·다른 단위일 때만이다. "신림1동"·"목3동" 같은 행정동 이름은 붙어 있어서
 * 건드리지 않는다.
 */
final class AddressQueryTrimmer {

    private static final Pattern TRAILING_UNIT = Pattern.compile(
            "(?:\\s*\\([^)]*\\)|(?<=[\\s,)동층호])\\s*(?:제|지하|[Bb])?\\d+(?:동|층|호))[\\s,]*$");
    private static final Pattern TRAILING_SEPARATOR = Pattern.compile("[\\s,]+$");
    private static final Pattern DIGIT = Pattern.compile("\\d");

    private AddressQueryTrimmer() {
    }

    /** 주소일 수 있는 검색어인지. 건물 번호가 없는 검색어는 주소 검색이 정확한 지점을 못 준다. */
    static boolean mayBeAddress(String query) {
        return query != null && DIGIT.matcher(query).find();
    }

    /** 끝의 단위를 반복해서 뗀다. 뗄 것이 없으면 그대로(공백만 정리) 돌려준다. */
    static String trim(String query) {
        if (query == null) {
            return "";
        }
        String current = TRAILING_SEPARATOR.matcher(query.strip()).replaceFirst("");
        while (true) {
            String next = TRAILING_SEPARATOR.matcher(TRAILING_UNIT.matcher(current).replaceFirst("")).replaceFirst("");
            if (next.equals(current)) {
                return current;
            }
            current = next;
        }
    }
}
