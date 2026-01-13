package com.cdc.sync.monitoring;

import com.cdc.sync.config.CdcSimulatorConfig;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * CDC 동기화 시뮬레이터 대시보드 컨트롤러
 *
 * Thymeleaf 템플릿을 사용하여 동적 대시보드를 제공합니다.
 */
@Controller
public class DashboardController {

    private final CdcSimulatorConfig simulatorConfig;

    public DashboardController(CdcSimulatorConfig simulatorConfig) {
        this.simulatorConfig = simulatorConfig;
    }

    /**
     * 메인 대시보드 페이지
     *
     * @param tableName 선택된 테이블명 (기본값: BOOK)
     * @param model Thymeleaf 모델
     * @return 템플릿 이름
     */
    @GetMapping("/simulator")
    public String simulator(
            @RequestParam(value = "table", defaultValue = "BOOK") String tableName,
            Model model) {

        // 테이블 목록
        model.addAttribute("tables", simulatorConfig.getTables());

        // 선택된 테이블
        CdcSimulatorConfig.TableConfig selectedTable = simulatorConfig.findByName(tableName);
        if (selectedTable == null && !simulatorConfig.getTables().isEmpty()) {
            selectedTable = simulatorConfig.getTables().get(0);
        }
        model.addAttribute("selectedTable", selectedTable);

        return "simulator";
    }

    /**
     * 기존 대시보드 URL 호환성 유지
     * /dashboard 접근 시 /simulator로 리다이렉트
     */
    @GetMapping("/dashboard")
    public String dashboard() {
        return "redirect:/simulator";
    }

    /**
     * 루트 URL 접근 시 시뮬레이터로 리다이렉트
     */
    @GetMapping("/")
    public String root() {
        return "redirect:/simulator";
    }
}
