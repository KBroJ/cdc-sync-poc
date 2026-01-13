package com.cdc.sync.monitoring;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 대시보드 페이지 컨트롤러
 *
 * static/dashboard.html 파일로 리다이렉트합니다.
 */
@Controller
public class DashboardController {

    /**
     * 대시보드 페이지로 리다이렉트
     */
    @GetMapping("/dashboard")
    public String dashboard() {
        return "redirect:/dashboard.html";
    }
}
