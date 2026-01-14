package com.cdc.sync.controller;

import com.cdc.sync.service.CdcMonitoringService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CDC 모니터링 REST API 컨트롤러
 *
 * [설계 의도]
 * - @RestController: JSON 데이터 반환 (뷰 렌더링 없음)
 * - @RequestMapping: 공통 URL prefix 설정
 * - @CrossOrigin: 개발 환경에서 CORS 허용 (프로덕션에서는 제한 필요)
 *
 * [엔드포인트 설계]
 * - GET: 데이터 조회 (부수효과 없음)
 * - POST: 상태 변경 (reset 등)
 *
 * [프로덕션 고려사항]
 * - 인증/인가 추가 필요 (@PreAuthorize 등)
 * - Rate Limiting 고려
 * - CORS 정책 강화
 */
@RestController
@RequestMapping("/api/monitoring")
@CrossOrigin(origins = "*")  // TODO: 프로덕션에서는 특정 도메인만 허용
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
     *
     * [설계 의도]
     * - 여러 API를 한 번에 호출하는 대신 통합 엔드포인트 제공
     * - 프론트엔드 네트워크 요청 횟수 감소
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
