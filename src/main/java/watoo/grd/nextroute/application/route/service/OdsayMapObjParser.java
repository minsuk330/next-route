package watoo.grd.nextroute.application.route.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.route.dto.OdsayMapObjFragment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ODsay mapObj 문자열을 파싱한다.
 *
 * 두 가지 형식 지원:
 *  - "3:2:322:329@17:2:534:572"         (base prefix 없음)
 *  - "0:0@3:2:322:329@17:2:534:572"     (base prefix 있음, "BaseX:BaseY@..." 형식)
 */
@Slf4j
@Component
public class OdsayMapObjParser {

    public List<OdsayMapObjFragment> parse(String mapObj) {
        if (mapObj == null || mapObj.isBlank()) {
            return Collections.emptyList();
        }

        String toParse = stripBasePrefix(mapObj.trim());

        String[] fragmentStrings = toParse.split("@");
        List<OdsayMapObjFragment> result = new ArrayList<>();

        for (String fragmentStr : fragmentStrings) {
            if (fragmentStr.isBlank()) continue;
            parseFragment(fragmentStr.trim()).ifPresent(result::add);
        }

        return Collections.unmodifiableList(result);
    }

    private String stripBasePrefix(String mapObj) {
        // "BaseX:BaseY@..." 형식이면 첫 "@" 이전 부분을 제거
        int atIndex = mapObj.indexOf('@');
        if (atIndex < 0) {
            return mapObj;
        }
        String beforeAt = mapObj.substring(0, atIndex);
        String[] parts = beforeAt.split(":");
        // 두 토큰이고 정수로 파싱되면 base prefix
        if (parts.length == 2 && isNumeric(parts[0]) && isNumeric(parts[1])) {
            return mapObj.substring(atIndex + 1);
        }
        return mapObj;
    }

    private java.util.Optional<OdsayMapObjFragment> parseFragment(String fragment) {
        String[] parts = fragment.split(":");
        if (parts.length != 4) {
            log.warn("[MapObjParser] Invalid fragment skipped: '{}'", fragment);
            return java.util.Optional.empty();
        }
        try {
            String routeId = parts[0].trim();
            int laneClass = Integer.parseInt(parts[1].trim());
            int startIdx  = Integer.parseInt(parts[2].trim());
            int endIdx    = Integer.parseInt(parts[3].trim());
            return java.util.Optional.of(new OdsayMapObjFragment(routeId, laneClass, startIdx, endIdx));
        } catch (NumberFormatException e) {
            log.warn("[MapObjParser] Non-numeric field in fragment '{}': {}", fragment, e.getMessage());
            return java.util.Optional.empty();
        }
    }

    private boolean isNumeric(String s) {
        try {
            Double.parseDouble(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
