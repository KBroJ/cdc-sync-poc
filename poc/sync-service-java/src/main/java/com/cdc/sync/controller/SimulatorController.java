package com.cdc.sync.controller;

import com.cdc.sync.config.CdcSimulatorConfig;
import com.cdc.sync.config.CdcSimulatorConfig.TableConfig;
import com.cdc.sync.config.CdcSimulatorConfig.ColumnConfig;
import com.cdc.sync.config.CdcSimulatorConfig.SyncDirection;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * CDC 시뮬레이터 REST API 컨트롤러
 *
 * [설계 의도]
 * - 동적 테이블 지원: application.yml 설정 기반으로 모든 테이블 테스트 가능
 * - RESTful 설계: {tableName}/{db}/{operation} 형태의 URL 패턴
 * - @PathVariable: URL 경로에서 동적 파라미터 추출
 *
 * [엔드포인트 패턴]
 * - GET  /api/simulator/{tableName}/{db}/data    : 원본 테이블 조회
 * - GET  /api/simulator/{tableName}/{db}/cdc     : CDC 테이블 조회
 * - GET  /api/simulator/{tableName}/{db}/staging : STAGING 테이블 조회
 * - GET  /api/simulator/sync-log                 : 동기화 로그 조회
 * - POST /api/simulator/{tableName}/{db}/insert  : INSERT 테스트
 * - POST /api/simulator/{tableName}/{db}/update  : UPDATE 테스트
 * - POST /api/simulator/{tableName}/{db}/delete  : DELETE 테스트
 *
 * [프로덕션 고려사항]
 * - JdbcTemplate 직접 사용 → Repository 패턴으로 분리 권장
 * - SQL Injection 방지: 동적 테이블명은 설정에서만 허용
 * - 트랜잭션 경계 설정 필요
 */
@RestController
@RequestMapping("/api/simulator")
@CrossOrigin(origins = "*")
public class SimulatorController {

    private final JdbcTemplate asisJdbcTemplate;
    private final JdbcTemplate tobeJdbcTemplate;
    private final CdcSimulatorConfig simulatorConfig;

    public SimulatorController(
            @Qualifier("asisJdbcTemplate") JdbcTemplate asisJdbcTemplate,
            @Qualifier("tobeJdbcTemplate") JdbcTemplate tobeJdbcTemplate,
            CdcSimulatorConfig simulatorConfig) {
        this.asisJdbcTemplate = asisJdbcTemplate;
        this.tobeJdbcTemplate = tobeJdbcTemplate;
        this.simulatorConfig = simulatorConfig;
    }

    // ==================== 데이터 조회 API ====================

    /**
     * 원본 테이블 데이터 조회
     *
     * [설계 의도]
     * - 설정 기반 동적 컬럼 조회: 테이블별 설정된 컬럼만 SELECT
     * - Oracle FETCH FIRST: 페이징 처리 (Oracle 12c+)
     */
    @GetMapping("/{tableName}/{db}/data")
    public ResponseEntity<Map<String, Object>> getData(
            @PathVariable String tableName,
            @PathVariable String db) {
        Map<String, Object> response = new HashMap<>();
        try {
            TableConfig config = getTableConfig(tableName);
            if (config == null) {
                return notFoundResponse("테이블 설정을 찾을 수 없음: " + tableName);
            }

            JdbcTemplate jdbc = getJdbcTemplate(db);
            String table = "asis".equalsIgnoreCase(db)
                    ? config.getAsis().getTable()
                    : config.getTobe().getTable();
            List<ColumnConfig> columns = "asis".equalsIgnoreCase(db)
                    ? config.getAsis().getColumns()
                    : config.getTobe().getColumns();

            String columnList = columns.stream()
                    .map(ColumnConfig::getName)
                    .collect(Collectors.joining(", "));
            String pk = "asis".equalsIgnoreCase(db)
                    ? config.getAsis().getPk()
                    : config.getTobe().getPk();

            String sql = String.format(
                    "SELECT %s FROM %s ORDER BY %s FETCH FIRST 20 ROWS ONLY",
                    columnList, table, pk);

            List<Map<String, Object>> data = jdbc.queryForList(sql);

            response.put("success", true);
            response.put("table", table);
            response.put("db", db.toUpperCase());
            response.put("count", data.size());
            response.put("data", data);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            return errorResponse("데이터 조회 실패", e);
        }
    }

    /**
     * CDC 테이블 데이터 조회
     */
    @GetMapping("/{tableName}/{db}/cdc")
    public ResponseEntity<Map<String, Object>> getCdcData(
            @PathVariable String tableName,
            @PathVariable String db) {
        Map<String, Object> response = new HashMap<>();
        try {
            TableConfig config = getTableConfig(tableName);
            if (config == null) {
                return notFoundResponse("테이블 설정을 찾을 수 없음: " + tableName);
            }

            String cdcTable = "asis".equalsIgnoreCase(db)
                    ? config.getCdcTable().getAsis()
                    : config.getCdcTable().getTobe();

            if (cdcTable == null || cdcTable.isEmpty()) {
                return notFoundResponse(db.toUpperCase() + "에 CDC 테이블이 설정되어 있지 않음");
            }

            JdbcTemplate jdbc = getJdbcTemplate(db);
            String sql = String.format(
                    "SELECT * FROM %s ORDER BY CDC_SEQ DESC FETCH FIRST 20 ROWS ONLY",
                    cdcTable);

            List<Map<String, Object>> data = jdbc.queryForList(sql);

            response.put("success", true);
            response.put("table", cdcTable);
            response.put("db", db.toUpperCase());
            response.put("count", data.size());
            response.put("data", data);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            return errorResponse("CDC 테이블 조회 실패", e);
        }
    }

    /**
     * STAGING 테이블 데이터 조회
     */
    @GetMapping("/{tableName}/{db}/staging")
    public ResponseEntity<Map<String, Object>> getStagingData(
            @PathVariable String tableName,
            @PathVariable String db) {
        Map<String, Object> response = new HashMap<>();
        try {
            TableConfig config = getTableConfig(tableName);
            if (config == null) {
                return notFoundResponse("테이블 설정을 찾을 수 없음: " + tableName);
            }

            String stagingTable = "asis".equalsIgnoreCase(db)
                    ? config.getStagingTable().getAsis()
                    : config.getStagingTable().getTobe();

            if (stagingTable == null || stagingTable.isEmpty()) {
                return notFoundResponse(db.toUpperCase() + "에 STAGING 테이블이 설정되어 있지 않음");
            }

            JdbcTemplate jdbc = getJdbcTemplate(db);
            String sql = String.format(
                    "SELECT * FROM %s ORDER BY STAGING_SEQ DESC FETCH FIRST 20 ROWS ONLY",
                    stagingTable);

            List<Map<String, Object>> data = jdbc.queryForList(sql);

            response.put("success", true);
            response.put("table", stagingTable);
            response.put("db", db.toUpperCase());
            response.put("count", data.size());
            response.put("data", data);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            return errorResponse("STAGING 테이블 조회 실패", e);
        }
    }

    /**
     * 동기화 로그 조회
     *
     * [설계 의도]
     * - 양쪽 DB의 로그를 병합하여 통합 뷰 제공
     * - 시간순 정렬로 최신 이벤트 먼저 표시
     */
    @GetMapping("/sync-log")
    public ResponseEntity<Map<String, Object>> getSyncLog(
            @RequestParam(required = false) String table,
            @RequestParam(required = false) String db) {
        Map<String, Object> response = new HashMap<>();
        try {
            // 양쪽 DB의 CDC_SYNC_LOG를 조회하여 병합
            StringBuilder sql = new StringBuilder(
                    "SELECT * FROM CDC_SYNC_LOG WHERE 1=1");

            if (table != null && !table.isEmpty()) {
                sql.append(" AND UPPER(TABLE_NAME) LIKE '%").append(table.toUpperCase()).append("%'");
            }
            sql.append(" ORDER BY LOG_TIME DESC FETCH FIRST 50 ROWS ONLY");

            List<Map<String, Object>> asisLogs = List.of();
            List<Map<String, Object>> tobeLogs = List.of();

            if (db == null || "asis".equalsIgnoreCase(db)) {
                try {
                    asisLogs = asisJdbcTemplate.queryForList(sql.toString());
                    asisLogs.forEach(log -> log.put("SOURCE_DB", "ASIS"));
                } catch (Exception ignored) {}
            }

            if (db == null || "tobe".equalsIgnoreCase(db)) {
                try {
                    tobeLogs = tobeJdbcTemplate.queryForList(sql.toString());
                    tobeLogs.forEach(log -> log.put("SOURCE_DB", "TOBE"));
                } catch (Exception ignored) {}
            }

            // 병합 및 시간순 정렬
            List<Map<String, Object>> allLogs = new java.util.ArrayList<>();
            allLogs.addAll(asisLogs);
            allLogs.addAll(tobeLogs);
            allLogs.sort((a, b) -> {
                Object timeA = a.get("LOG_TIME");
                Object timeB = b.get("LOG_TIME");
                if (timeA == null) return 1;
                if (timeB == null) return -1;
                return timeB.toString().compareTo(timeA.toString());
            });

            // 최대 50개만 반환
            if (allLogs.size() > 50) {
                allLogs = allLogs.subList(0, 50);
            }

            response.put("success", true);
            response.put("count", allLogs.size());
            response.put("data", allLogs);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            return errorResponse("동기화 로그 조회 실패", e);
        }
    }

    /**
     * 통계 요약 조회
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats(
            @RequestParam(required = false) String table) {
        Map<String, Object> response = new HashMap<>();
        try {
            Map<String, Object> stats = new HashMap<>();

            // ASIS CDC_SYNC_LOG 통계
            try {
                String sql = "SELECT STATUS, COUNT(*) as CNT FROM CDC_SYNC_LOG" +
                        (table != null ? " WHERE UPPER(TABLE_NAME) LIKE '%" + table.toUpperCase() + "%'" : "") +
                        " GROUP BY STATUS";
                List<Map<String, Object>> asisStats = asisJdbcTemplate.queryForList(sql);
                stats.put("asis", asisStats);
            } catch (Exception ignored) {
                stats.put("asis", List.of());
            }

            // TOBE CDC_SYNC_LOG 통계
            try {
                String sql = "SELECT STATUS, COUNT(*) as CNT FROM CDC_SYNC_LOG" +
                        (table != null ? " WHERE UPPER(TABLE_NAME) LIKE '%" + table.toUpperCase() + "%'" : "") +
                        " GROUP BY STATUS";
                List<Map<String, Object>> tobeStats = tobeJdbcTemplate.queryForList(sql);
                stats.put("tobe", tobeStats);
            } catch (Exception ignored) {
                stats.put("tobe", List.of());
            }

            response.put("success", true);
            response.put("stats", stats);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            return errorResponse("통계 조회 실패", e);
        }
    }

    // ==================== CUD 테스트 API ====================

    /**
     * INSERT 테스트
     *
     * [설계 의도]
     * - 동적 INSERT: 설정 기반으로 컬럼/값 자동 생성
     * - PK 자동 채번: NUMBER 타입은 MAX+1, VARCHAR는 타임스탬프 기반
     * - 기본값 처리: 설정의 default 또는 자동 생성
     */
    @PostMapping("/{tableName}/{db}/insert")
    public ResponseEntity<Map<String, Object>> testInsert(
            @PathVariable String tableName,
            @PathVariable String db,
            @RequestBody(required = false) Map<String, Object> requestData) {
        Map<String, Object> response = new HashMap<>();
        try {
            TableConfig config = getTableConfig(tableName);
            if (config == null) {
                return notFoundResponse("테이블 설정을 찾을 수 없음: " + tableName);
            }

            // 방향 검증
            if (!isOperationAllowed(config, db)) {
                return errorResponse("이 테이블은 " + db.toUpperCase() + "에서 테스트할 수 없습니다", null);
            }

            JdbcTemplate jdbc = getJdbcTemplate(db);
            var dbConfig = "asis".equalsIgnoreCase(db) ? config.getAsis() : config.getTobe();
            String table = dbConfig.getTable();
            String pk = dbConfig.getPk();

            // 다음 ID 조회 (NUMBER 타입 PK인 경우)
            Object nextId;
            ColumnConfig pkColumn = dbConfig.getColumns().stream()
                    .filter(c -> c.getName().equals(pk))
                    .findFirst().orElse(null);

            if (pkColumn != null && "NUMBER".equalsIgnoreCase(pkColumn.getType())) {
                nextId = jdbc.queryForObject(
                        String.format("SELECT NVL(MAX(%s), 0) + 1 FROM %s", pk, table),
                        Integer.class);
            } else {
                // VARCHAR 타입 PK (예: CODE_ID)
                nextId = requestData != null && requestData.containsKey(pk)
                        ? requestData.get(pk)
                        : pk + "_" + System.currentTimeMillis();
            }

            // INSERT SQL 생성
            Map<String, Object> insertData = new HashMap<>();
            insertData.put(pk, nextId);

            StringBuilder columns = new StringBuilder(pk);
            StringBuilder values = new StringBuilder("?");
            List<Object> params = new java.util.ArrayList<>();
            params.add(nextId);

            for (ColumnConfig col : dbConfig.getNonPkColumns()) {
                Object value = null;
                if (requestData != null && requestData.containsKey(col.getName())) {
                    value = requestData.get(col.getName());
                } else if (col.getDefault() != null) {
                    value = col.getDefault();
                } else if (col.isRequired()) {
                    // 필수 컬럼에 기본값 생성
                    value = generateDefaultValue(col);
                }

                if (value != null) {
                    columns.append(", ").append(col.getName());
                    values.append(", ?");
                    params.add(value);
                    insertData.put(col.getName(), value);
                }
            }

            String sql = String.format("INSERT INTO %s (%s) VALUES (%s)",
                    table, columns, values);
            int inserted = jdbc.update(sql, params.toArray());

            response.put("success", true);
            response.put("operation", "INSERT");
            response.put("message", db.toUpperCase() + " DB INSERT 완료");
            response.put("table", table);
            response.put("sql", sql);
            response.put("insertedData", insertData);
            response.put("rowsAffected", inserted);
            response.put("targetCdcTable", getTargetCdcTable(config, db));
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            return errorResponse("INSERT 실패", e);
        }
    }

    /**
     * UPDATE 테스트
     */
    @PostMapping("/{tableName}/{db}/update")
    public ResponseEntity<Map<String, Object>> testUpdate(
            @PathVariable String tableName,
            @PathVariable String db,
            @RequestBody(required = false) Map<String, Object> requestData) {
        Map<String, Object> response = new HashMap<>();
        try {
            TableConfig config = getTableConfig(tableName);
            if (config == null) {
                return notFoundResponse("테이블 설정을 찾을 수 없음: " + tableName);
            }

            if (!isOperationAllowed(config, db)) {
                return errorResponse("이 테이블은 " + db.toUpperCase() + "에서 테스트할 수 없습니다", null);
            }

            JdbcTemplate jdbc = getJdbcTemplate(db);
            var dbConfig = "asis".equalsIgnoreCase(db) ? config.getAsis() : config.getTobe();
            String table = dbConfig.getTable();
            String pk = dbConfig.getPk();

            // PK 값 결정
            Object pkValue = requestData != null ? requestData.get(pk) : null;
            if (pkValue == null) {
                pkValue = jdbc.queryForObject(
                        String.format("SELECT MIN(%s) FROM %s", pk, table),
                        Object.class);
            }

            if (pkValue == null) {
                return errorResponse("업데이트할 데이터가 없습니다", null);
            }

            // 변경 전 데이터 조회
            Map<String, Object> beforeData = jdbc.queryForMap(
                    String.format("SELECT * FROM %s WHERE %s = ?", table, pk), pkValue);

            // UPDATE할 컬럼 결정 (첫 번째 비 PK 컬럼)
            ColumnConfig updateCol = dbConfig.getNonPkColumns().stream()
                    .filter(c -> !"NUMBER".equalsIgnoreCase(c.getType()) || requestData != null)
                    .findFirst()
                    .orElse(dbConfig.getNonPkColumns().isEmpty() ? null : dbConfig.getNonPkColumns().get(0));

            if (updateCol == null) {
                return errorResponse("업데이트할 컬럼이 없습니다", null);
            }

            Object newValue = requestData != null && requestData.containsKey(updateCol.getName())
                    ? requestData.get(updateCol.getName())
                    : "수정됨_" + System.currentTimeMillis();

            String sql = String.format("UPDATE %s SET %s = ? WHERE %s = ?",
                    table, updateCol.getName(), pk);
            int updated = jdbc.update(sql, newValue, pkValue);

            Map<String, Object> afterData = new HashMap<>(beforeData);
            afterData.put(updateCol.getName(), newValue);

            response.put("success", true);
            response.put("operation", "UPDATE");
            response.put("message", db.toUpperCase() + " DB UPDATE 완료");
            response.put("table", table);
            response.put("sql", sql);
            response.put("beforeData", beforeData);
            response.put("afterData", afterData);
            response.put("rowsAffected", updated);
            response.put("targetCdcTable", getTargetCdcTable(config, db));
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            return errorResponse("UPDATE 실패", e);
        }
    }

    /**
     * DELETE 테스트
     */
    @PostMapping("/{tableName}/{db}/delete")
    public ResponseEntity<Map<String, Object>> testDelete(
            @PathVariable String tableName,
            @PathVariable String db,
            @RequestBody(required = false) Map<String, Object> requestData) {
        Map<String, Object> response = new HashMap<>();
        try {
            TableConfig config = getTableConfig(tableName);
            if (config == null) {
                return notFoundResponse("테이블 설정을 찾을 수 없음: " + tableName);
            }

            if (!isOperationAllowed(config, db)) {
                return errorResponse("이 테이블은 " + db.toUpperCase() + "에서 테스트할 수 없습니다", null);
            }

            JdbcTemplate jdbc = getJdbcTemplate(db);
            var dbConfig = "asis".equalsIgnoreCase(db) ? config.getAsis() : config.getTobe();
            String table = dbConfig.getTable();
            String pk = dbConfig.getPk();

            // 삭제할 PK 값 결정 (기본값: 가장 큰 ID 중 기본 데이터 제외)
            Object pkValue = requestData != null ? requestData.get(pk) : null;
            if (pkValue == null) {
                try {
                    // 기본 데이터(1-3) 제외하고 가장 큰 값
                    pkValue = jdbc.queryForObject(
                            String.format("SELECT MAX(%s) FROM %s WHERE %s > 3", pk, table, pk),
                            Object.class);
                } catch (Exception e) {
                    // NUMBER가 아닌 경우 그냥 MAX
                    pkValue = jdbc.queryForObject(
                            String.format("SELECT MAX(%s) FROM %s", pk, table),
                            Object.class);
                }
            }

            if (pkValue == null) {
                response.put("success", false);
                response.put("operation", "DELETE");
                response.put("message", "삭제할 데이터 없음 (기본 데이터는 보호됨)");
                return ResponseEntity.ok(response);
            }

            // 삭제 전 데이터 조회
            Map<String, Object> deletedData = jdbc.queryForMap(
                    String.format("SELECT * FROM %s WHERE %s = ?", table, pk), pkValue);

            String sql = String.format("DELETE FROM %s WHERE %s = ?", table, pk);
            int deleted = jdbc.update(sql, pkValue);

            response.put("success", true);
            response.put("operation", "DELETE");
            response.put("message", db.toUpperCase() + " DB DELETE 완료");
            response.put("table", table);
            response.put("sql", sql);
            response.put("deletedData", deletedData);
            response.put("rowsAffected", deleted);
            response.put("targetCdcTable", getTargetCdcTable(config, db));
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            return errorResponse("DELETE 실패", e);
        }
    }

    // ==================== 헬퍼 메서드 ====================

    private TableConfig getTableConfig(String tableName) {
        return simulatorConfig.findByName(tableName);
    }

    private JdbcTemplate getJdbcTemplate(String db) {
        return "asis".equalsIgnoreCase(db) ? asisJdbcTemplate : tobeJdbcTemplate;
    }

    private boolean isOperationAllowed(TableConfig config, String db) {
        SyncDirection direction = config.getDirection();
        if ("asis".equalsIgnoreCase(db)) {
            return direction == SyncDirection.ASIS_TO_TOBE || direction == SyncDirection.BIDIRECTIONAL;
        } else {
            return direction == SyncDirection.TOBE_TO_ASIS || direction == SyncDirection.BIDIRECTIONAL;
        }
    }

    private String getTargetCdcTable(TableConfig config, String sourceDb) {
        // 소스 DB의 변경이 타겟 DB의 CDC 테이블로 전달됨
        if ("asis".equalsIgnoreCase(sourceDb)) {
            return config.getCdcTable().getTobe();
        } else {
            return config.getCdcTable().getAsis();
        }
    }

    private Object generateDefaultValue(ColumnConfig col) {
        if ("NUMBER".equalsIgnoreCase(col.getType())) {
            return 0;
        } else if ("CHAR".equalsIgnoreCase(col.getType())) {
            return "Y";
        } else {
            return "테스트_" + System.currentTimeMillis();
        }
    }

    private ResponseEntity<Map<String, Object>> notFoundResponse(String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("message", message);
        return ResponseEntity.ok(response);
    }

    private ResponseEntity<Map<String, Object>> errorResponse(String message, Exception e) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("message", message + (e != null ? ": " + e.getMessage() : ""));
        return ResponseEntity.ok(response);
    }
}
