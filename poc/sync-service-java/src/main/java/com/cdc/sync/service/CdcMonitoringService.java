package com.cdc.sync.service;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * CDC 모니터링 서비스
 *
 * [설계 의도]
 * - 인메모리 통계 관리: 실시간 모니터링용 (재시작 시 초기화됨)
 * - 스레드 안전: AtomicLong, ConcurrentHashMap, synchronized 사용
 * - 순환 버퍼: 최근 N개만 유지하여 메모리 제한
 *
 * [프로덕션 고려사항]
 * - 영속성: Redis/DB 저장으로 재시작 시에도 유지
 * - 메트릭: Micrometer/Prometheus 연동 권장
 * - 알림: 임계치 초과 시 알림 발송 기능 추가
 *
 * [사용 패턴]
 * - CdcSyncService에서 이벤트 처리 시 호출
 * - MonitoringController를 통해 REST API로 조회
 */
@Service
public class CdcMonitoringService {

    // 통계 카운터 (스레드 안전)
    private final AtomicLong totalReceived = new AtomicLong(0);
    private final AtomicLong totalSuccess = new AtomicLong(0);
    private final AtomicLong totalFailed = new AtomicLong(0);

    // 테이블별 통계
    private final Map<String, TableStats> tableStats = new ConcurrentHashMap<>();

    // 최근 에러 로그 (순환 버퍼, 최대 100개)
    private final List<ErrorLog> recentErrors = new ArrayList<>();
    private static final int MAX_ERROR_LOGS = 100;

    // 최근 처리 이벤트 (순환 버퍼, 최대 50개)
    private final List<EventLog> recentEvents = new ArrayList<>();
    private static final int MAX_EVENT_LOGS = 50;

    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 이벤트 수신 기록
     */
    public void recordReceived(String topic) {
        totalReceived.incrementAndGet();
        getTableStats(topic).received.incrementAndGet();
    }

    /**
     * 성공 처리 기록
     */
    public void recordSuccess(String topic, String targetTable, String operation, String hash, String dataPreview) {
        totalSuccess.incrementAndGet();
        TableStats stats = getTableStats(topic);
        stats.success.incrementAndGet();
        stats.lastSuccess = LocalDateTime.now();

        // 최근 이벤트 기록
        addEventLog(new EventLog(
                LocalDateTime.now().format(formatter),
                "SUCCESS",
                topic,
                targetTable,
                operation,
                hash.substring(0, Math.min(16, hash.length())),
                null,
                dataPreview
        ));
    }

    /**
     * 실패 처리 기록
     */
    public void recordFailure(String topic, String targetTable, String operation, String errorMessage, String dataPreview) {
        totalFailed.incrementAndGet();
        TableStats stats = getTableStats(topic);
        stats.failed.incrementAndGet();
        stats.lastError = LocalDateTime.now();

        // 에러 로그 기록
        addErrorLog(new ErrorLog(
                LocalDateTime.now().format(formatter),
                topic,
                targetTable,
                operation,
                errorMessage
        ));

        // 최근 이벤트 기록
        addEventLog(new EventLog(
                LocalDateTime.now().format(formatter),
                "FAILED",
                topic,
                targetTable,
                operation,
                null,
                errorMessage.length() > 100 ? errorMessage.substring(0, 100) + "..." : errorMessage,
                dataPreview
        ));
    }

    /**
     * 전체 통계 반환
     */
    public MonitoringStats getStats() {
        MonitoringStats stats = new MonitoringStats();
        stats.totalReceived = totalReceived.get();
        stats.totalSuccess = totalSuccess.get();
        stats.totalFailed = totalFailed.get();
        stats.successRate = stats.totalReceived > 0
                ? (double) stats.totalSuccess / stats.totalReceived * 100
                : 0;
        stats.tableStats = new ConcurrentHashMap<>(tableStats);
        return stats;
    }

    /**
     * 최근 에러 로그 반환
     */
    public synchronized List<ErrorLog> getRecentErrors() {
        return new ArrayList<>(recentErrors);
    }

    /**
     * 최근 이벤트 로그 반환
     */
    public synchronized List<EventLog> getRecentEvents() {
        return new ArrayList<>(recentEvents);
    }

    /**
     * 통계 초기화
     */
    public void resetStats() {
        totalReceived.set(0);
        totalSuccess.set(0);
        totalFailed.set(0);
        tableStats.clear();
        synchronized (this) {
            recentErrors.clear();
            recentEvents.clear();
        }
    }

    private TableStats getTableStats(String topic) {
        return tableStats.computeIfAbsent(topic, k -> new TableStats());
    }

    private synchronized void addErrorLog(ErrorLog log) {
        recentErrors.add(0, log);
        if (recentErrors.size() > MAX_ERROR_LOGS) {
            recentErrors.remove(recentErrors.size() - 1);
        }
    }

    private synchronized void addEventLog(EventLog log) {
        recentEvents.add(0, log);
        if (recentEvents.size() > MAX_EVENT_LOGS) {
            recentEvents.remove(recentEvents.size() - 1);
        }
    }

    // ==================== DTO 클래스들 ====================

    /**
     * 모니터링 통계 DTO
     */
    public static class MonitoringStats {
        public long totalReceived;
        public long totalSuccess;
        public long totalFailed;
        public double successRate;
        public Map<String, TableStats> tableStats;
    }

    /**
     * 테이블별 통계 DTO
     */
    public static class TableStats {
        public AtomicLong received = new AtomicLong(0);
        public AtomicLong success = new AtomicLong(0);
        public AtomicLong failed = new AtomicLong(0);
        public LocalDateTime lastSuccess;
        public LocalDateTime lastError;
    }

    /**
     * 에러 로그 DTO
     */
    public static class ErrorLog {
        public String timestamp;
        public String topic;
        public String targetTable;
        public String operation;
        public String errorMessage;

        public ErrorLog(String timestamp, String topic, String targetTable, String operation, String errorMessage) {
            this.timestamp = timestamp;
            this.topic = topic;
            this.targetTable = targetTable;
            this.operation = operation;
            this.errorMessage = errorMessage;
        }
    }

    /**
     * 이벤트 로그 DTO
     */
    public static class EventLog {
        public String timestamp;
        public String status;
        public String topic;
        public String targetTable;
        public String operation;
        public String hash;
        public String errorMessage;
        public String dataPreview;  // 전송된 데이터 미리보기

        public EventLog(String timestamp, String status, String topic, String targetTable,
                        String operation, String hash, String errorMessage, String dataPreview) {
            this.timestamp = timestamp;
            this.status = status;
            this.topic = topic;
            this.targetTable = targetTable;
            this.operation = operation;
            this.hash = hash;
            this.errorMessage = errorMessage;
            this.dataPreview = dataPreview;
        }
    }
}
