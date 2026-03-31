package watoo.grd.nextroute.infrastructure.adapter.in.api.route.search;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class RoutePageController {

    @Value("${kakao.map.js-key:}")
    private String kakaoMapJsKey;

    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("kakaoMapJsKey", kakaoMapJsKey);
        return "index";
    }
}
