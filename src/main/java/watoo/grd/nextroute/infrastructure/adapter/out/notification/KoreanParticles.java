package watoo.grd.nextroute.infrastructure.adapter.out.notification;

/**
 * 노선 이름 뒤에 붙일 조사(이/가, 을/를)를 고른다(#428 PR-2).
 *
 * <p>기준은 <b>마지막 글자를 소리 내 읽었을 때 받침이 있는가</b>다. 노선 이름은 한글로만 끝나지 않는다 —
 * "M4403" 은 숫자 3("삼")으로, "M" 버스는 영문으로 끝난다. 그래서 숫자·영문은 한국어 읽기로 받침을 판단한다.
 * <ul>
 *   <li>숫자: 0(영)·1(일)·3(삼)·6(육)·7(칠)·8(팔)은 받침 있음, 2(이)·4(사)·5(오)·9(구)는 없음.
 *       0 으로 끝나는 큰 수(십·백·천·만)도 모두 받침이 있어 마지막 자리만 보면 된다</li>
 *   <li>영문: 엘·엠·엔·알(L·M·N·R)만 받침 있음, 나머지는 받침 없는 쪽으로 읽는다</li>
 * </ul>
 *
 * <p>끝의 괄호·공백 같은 기호는 건너뛰고 앞 글자를 본다("2호선(본선)" → "선"). 읽을 글자가 하나도 없으면
 * "이(가)"처럼 둘 다 적는다 — 틀린 조사보다 어색한 병기가 낫다.
 */
final class KoreanParticles {

    private static final int HANGUL_FIRST = 0xAC00;
    private static final int HANGUL_LAST = 0xD7A3;
    private static final int JONGSEONG_COUNT = 28;

    private KoreanParticles() {
    }

    /** 주격 조사. 받침 있으면 "이", 없으면 "가". */
    static String subject(String word) {
        return pick(word, "이", "가");
    }

    /** 목적격 조사. 받침 있으면 "을", 없으면 "를". */
    static String object(String word) {
        return pick(word, "을", "를");
    }

    private static String pick(String word, String withFinal, String withoutFinal) {
        Boolean hasFinal = hasFinalConsonant(word);
        if (hasFinal == null) {
            return withFinal + "(" + withoutFinal + ")";
        }
        return hasFinal ? withFinal : withoutFinal;
    }

    /** 마지막으로 읽히는 글자에 받침이 있는가. 읽을 글자가 없으면 null. */
    static Boolean hasFinalConsonant(String word) {
        if (word == null) {
            return null;
        }
        for (int i = word.length() - 1; i >= 0; i--) {
            char c = word.charAt(i);
            if (c >= HANGUL_FIRST && c <= HANGUL_LAST) {
                return (c - HANGUL_FIRST) % JONGSEONG_COUNT != 0;
            }
            if (c >= '0' && c <= '9') {
                return switch (c) {
                    case '0', '1', '3', '6', '7', '8' -> true;
                    default -> false;
                };
            }
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) {
                char upper = Character.toUpperCase(c);
                return upper == 'L' || upper == 'M' || upper == 'N' || upper == 'R';
            }
        }
        return null;
    }
}
