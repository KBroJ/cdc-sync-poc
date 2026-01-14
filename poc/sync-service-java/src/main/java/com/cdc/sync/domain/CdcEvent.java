package com.cdc.sync.domain;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;

/**
 * CDC 이벤트 도메인 모델
 *
 * [설계 의도]
 * - Debezium CDC 이벤트를 표현하는 도메인 객체
 * - 불변 객체로 설계하면 더 좋지만, PoC에서는 setter 허용
 * - 도메인 로직(getData, convertOperation 등)을 객체 내부에 캡슐화
 *
 * [Debezium 이벤트 구조]
 * {
 *   "schema": { ... },
 *   "payload": {
 *     "before": { 변경 전 데이터 },
 *     "after": { 변경 후 데이터 },
 *     "source": { 소스 DB 정보 },
 *     "op": "c|u|d|r",
 *     "ts_ms": 1234567890123
 *   }
 * }
 *
 * [Operation 코드]
 * - c (create): INSERT
 * - u (update): UPDATE
 * - d (delete): DELETE
 * - r (read): 스냅샷 읽기 (INSERT로 처리)
 *
 * [프로덕션 고려사항]
 * - Record 또는 불변 클래스로 변경 권장
 * - equals/hashCode 구현 필요시 추가
 * - Builder 패턴 적용 고려
 */
public class CdcEvent {

    /**
     * 오퍼레이션 타입 (INSERT, UPDATE, DELETE)
     */
    private String operation;

    /**
     * 변경 전 데이터 (UPDATE, DELETE 시)
     */
    private Map<String, Object> before;

    /**
     * 변경 후 데이터 (INSERT, UPDATE 시)
     */
    private Map<String, Object> after;

    /**
     * 원본 DB 정보 (schema, table 등)
     */
    private Map<String, Object> source;

    /**
     * 이벤트 발생 시간
     */
    private LocalDateTime sourceTimestamp;

    /**
     * 변경 데이터 해시 (무한루프 방지용)
     */
    private String changeHash;

    // ==================== Getters and Setters ====================

    public String getOperation() {
        return operation;
    }

    public void setOperation(String operation) {
        this.operation = operation;
    }

    public Map<String, Object> getBefore() {
        return before;
    }

    public void setBefore(Map<String, Object> before) {
        this.before = before;
    }

    public Map<String, Object> getAfter() {
        return after;
    }

    public void setAfter(Map<String, Object> after) {
        this.after = after;
    }

    public Map<String, Object> getSource() {
        return source;
    }

    public void setSource(Map<String, Object> source) {
        this.source = source;
    }

    public LocalDateTime getSourceTimestamp() {
        return sourceTimestamp;
    }

    public void setSourceTimestamp(LocalDateTime sourceTimestamp) {
        this.sourceTimestamp = sourceTimestamp;
    }

    public String getChangeHash() {
        return changeHash;
    }

    public void setChangeHash(String changeHash) {
        this.changeHash = changeHash;
    }

    // ==================== 도메인 로직 ====================

    /**
     * 변경된 데이터를 반환 (after 또는 before)
     *
     * [로직]
     * - DELETE: before 데이터 반환 (after는 null)
     * - INSERT/UPDATE: after 데이터 반환
     *
     * @return 변경된 데이터 Map
     */
    public Map<String, Object> getData() {
        if ("DELETE".equals(operation)) {
            return before;
        }
        return after;
    }

    /**
     * 원본 테이블명 반환
     */
    public String getSourceTable() {
        if (source != null) {
            return (String) source.get("table");
        }
        return null;
    }

    /**
     * 원본 스키마명 반환
     */
    public String getSourceSchema() {
        if (source != null) {
            return (String) source.get("schema");
        }
        return null;
    }

    @Override
    public String toString() {
        return String.format("CdcEvent{op=%s, table=%s, hash=%s}",
                operation,
                getSourceTable(),
                changeHash != null ? changeHash.substring(0, 16) : "null");
    }

    // ==================== Static Factory Methods ====================

    /**
     * Debezium 오퍼레이션 코드를 문자열로 변환
     *
     * @param debeziumOp Debezium op 코드 (c, u, d, r)
     * @return 오퍼레이션 문자열 (INSERT, UPDATE, DELETE)
     */
    public static String convertOperation(String debeziumOp) {
        if (debeziumOp == null) return "UNKNOWN";

        return switch (debeziumOp) {
            case "c" -> "INSERT";      // create
            case "u" -> "UPDATE";      // update
            case "d" -> "DELETE";      // delete
            case "r" -> "INSERT";      // read (snapshot)
            default -> "UNKNOWN";
        };
    }

    /**
     * 타임스탬프(밀리초)를 LocalDateTime으로 변환
     *
     * @param tsMs 타임스탬프 (밀리초)
     * @return LocalDateTime
     */
    public static LocalDateTime convertTimestamp(Long tsMs) {
        if (tsMs == null) {
            return LocalDateTime.now();
        }
        return LocalDateTime.ofInstant(
                Instant.ofEpochMilli(tsMs),
                ZoneId.systemDefault()
        );
    }
}
