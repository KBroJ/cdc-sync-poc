package com.cdc.sync.monitoring;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CDC 모니터링 REST API 컨트롤러
 *
 * <h3>엔드포인트:</h3>
 * <ul>
 *   <li>GET /api/monitoring/stats - 전체 통계</li>
 *   <li>GET /api/monitoring/errors - 최근 에러 목록</li>
 *   <li>GET /api/monitoring/events - 최근 이벤트 목록</li>
 *   <li>GET /api/monitoring/dashboard - 대시보드 데이터</li>
 *   <li>POST /api/monitoring/reset - 통계 초기화</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/monitoring")
@CrossOrigin(origins = "*")  // 개발용 CORS 허용
public class MonitoringController {

    private final CdcMonitoringService monitoringService;

    public MonitoringController(CdcMonitoringService monitoringService) {
        this.monitoringService = monitoringService;
    }

    /**
     * 전체 통계 조회
     */
    @GetMapping("/stats")
    public ResponseEntity<CdcMonitoringService.MonitoringStats> getStats() {
        return ResponseEntity.ok(monitoringService.getStats());
    }

    /**
     * 최근 에러 목록 조회
     */
    @GetMapping("/errors")
    public ResponseEntity<List<CdcMonitoringService.ErrorLog>> getErrors() {
        return ResponseEntity.ok(monitoringService.getRecentErrors());
    }

    /**
     * 최근 이벤트 목록 조회
     */
    @GetMapping("/events")
    public ResponseEntity<List<CdcMonitoringService.EventLog>> getEvents() {
        return ResponseEntity.ok(monitoringService.getRecentEvents());
    }

    /**
     * 대시보드 통합 데이터 조회
     */
    @GetMapping("/dashboard")
    public ResponseEntity<Map<String, Object>> getDashboard() {
        Map<String, Object> dashboard = new HashMap<>();
        dashboard.put("stats", monitoringService.getStats());
        dashboard.put("recentEvents", monitoringService.getRecentEvents());
        dashboard.put("recentErrors", monitoringService.getRecentErrors());
        return ResponseEntity.ok(dashboard);
    }

    /**
     * 통계 초기화
     */
    @PostMapping("/reset")
    public ResponseEntity<Map<String, String>> resetStats() {
        monitoringService.resetStats();
        Map<String, String> response = new HashMap<>();
        response.put("status", "success");
        response.put("message", "Statistics have been reset");
        return ResponseEntity.ok(response);
    }
}
