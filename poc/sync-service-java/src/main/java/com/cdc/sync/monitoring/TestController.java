package com.cdc.sync.monitoring;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CDC 테스트 API 컨트롤러
 *
 * 대시보드에서 INSERT, UPDATE, DELETE 테스트를 실행할 수 있게 해주는 API
 */
@RestController
@RequestMapping("/api/test")
@CrossOrigin(origins = "*")
public class TestController {

    private final JdbcTemplate asisJdbcTemplate;
    private final JdbcTemplate tobeJdbcTemplate;

    public TestController(
            @Qualifier("asisJdbcTemplate") JdbcTemplate asisJdbcTemplate,
            @Qualifier("tobeJdbcTemplate") JdbcTemplate tobeJdbcTemplate) {
        this.asisJdbcTemplate = asisJdbcTemplate;
        this.tobeJdbcTemplate = tobeJdbcTemplate;
    }

    // ==================== ASIS DB 테스트 ====================

    /**
     * ASIS DB에서 INSERT 테스트 실행
     */
    @PostMapping("/asis/insert")
    public ResponseEntity<Map<String, Object>> testAsisInsert(@RequestBody(required = false) Map<String, String> request) {
        Map<String, Object> response = new HashMap<>();
        try {
            String title = request != null && request.containsKey("title")
                    ? request.get("title")
                    : "신규도서-" + System.currentTimeMillis();
            String author = request != null && request.containsKey("author")
                    ? request.get("author")
                    : "테스트저자";

            // 다음 ID 조회
            Integer nextId = asisJdbcTemplate.queryForObject(
                    "SELECT NVL(MAX(BOOK_ID), 0) + 1 FROM BOOK_INFO", Integer.class);

            // INSERT 실행
            String sql = "INSERT INTO BOOK_INFO (BOOK_ID, BOOK_TITLE, AUTHOR, CATEGORY, STATUS, REG_DATE) " +
                    "VALUES (?, ?, ?, '01', 'Y', SYSDATE)";
            int inserted = asisJdbcTemplate.update(sql, nextId, title, author);

            // 송신 메시지 구성
            Map<String, Object> sentData = new HashMap<>();
            sentData.put("BOOK_ID", nextId);
            sentData.put("BOOK_TITLE", title);
            sentData.put("AUTHOR", author);
            sentData.put("CATEGORY", "01");
            sentData.put("STATUS", "Y");

            response.put("success", true);
            response.put("operation", "INSERT");
            response.put("message", "ASIS DB INSERT 완료");
            response.put("sql", sql);
            response.put("sentData", sentData);
            response.put("rowsAffected", inserted);
            response.put("targetCdcTable", "CDC_TOBE_BOOK");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            response.put("success", false);
            response.put("operation", "INSERT");
            response.put("message", "INSERT 실패: " + e.getMessage());
            return ResponseEntity.ok(response);
        }
    }

    /**
     * ASIS DB에서 UPDATE 테스트 실행
     */
    @PostMapping("/asis/update")
    public ResponseEntity<Map<String, Object>> testAsisUpdate(@RequestBody(required = false) Map<String, String> request) {
        Map<String, Object> response = new HashMap<>();
        try {
            String title = request != null && request.containsKey("title")
                    ? request.get("title")
                    : "수정됨-" + System.currentTimeMillis();

            int bookId = request != null && request.containsKey("bookId")
                    ? Integer.parseInt(request.get("bookId"))
                    : 1;

            // 변경 전 데이터 조회
            Map<String, Object> beforeData = asisJdbcTemplate.queryForMap(
                    "SELECT BOOK_ID, BOOK_TITLE, AUTHOR, STATUS FROM BOOK_INFO WHERE BOOK_ID = ?", bookId);

            // UPDATE 실행
            String sql = "UPDATE BOOK_INFO SET BOOK_TITLE = ?, MOD_DATE = SYSDATE WHERE BOOK_ID = ?";
            int updated = asisJdbcTemplate.update(sql, title, bookId);

            // 변경 후 데이터
            Map<String, Object> afterData = new HashMap<>(beforeData);
            afterData.put("BOOK_TITLE", title);

            response.put("success", true);
            response.put("operation", "UPDATE");
            response.put("message", "ASIS DB UPDATE 완료");
            response.put("sql", sql);
            response.put("beforeData", beforeData);
            response.put("afterData", afterData);
            response.put("rowsAffected", updated);
            response.put("targetCdcTable", "CDC_TOBE_BOOK");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            response.put("success", false);
            response.put("operation", "UPDATE");
            response.put("message", "UPDATE 실패: " + e.getMessage());
            return ResponseEntity.ok(response);
        }
    }

    /**
     * ASIS DB에서 DELETE 테스트 실행
     */
    @PostMapping("/asis/delete")
    public ResponseEntity<Map<String, Object>> testAsisDelete(@RequestBody(required = false) Map<String, String> request) {
        Map<String, Object> response = new HashMap<>();
        try {
            // 삭제할 ID (기본: 가장 큰 ID)
            int bookId;
            if (request != null && request.containsKey("bookId")) {
                bookId = Integer.parseInt(request.get("bookId"));
            } else {
                Integer maxId = asisJdbcTemplate.queryForObject(
                        "SELECT MAX(BOOK_ID) FROM BOOK_INFO WHERE BOOK_ID > 3", Integer.class);
                if (maxId == null) {
                    response.put("success", false);
                    response.put("operation", "DELETE");
                    response.put("message", "삭제할 데이터 없음 (기본 데이터 BOOK_ID 1-3은 보호됨)");
                    return ResponseEntity.ok(response);
                }
                bookId = maxId;
            }

            // 삭제 전 데이터 조회
            Map<String, Object> deletedData = asisJdbcTemplate.queryForMap(
                    "SELECT BOOK_ID, BOOK_TITLE, AUTHOR, STATUS FROM BOOK_INFO WHERE BOOK_ID = ?", bookId);

            // DELETE 실행
            String sql = "DELETE FROM BOOK_INFO WHERE BOOK_ID = ?";
            int deleted = asisJdbcTemplate.update(sql, bookId);

            response.put("success", true);
            response.put("operation", "DELETE");
            response.put("message", "ASIS DB DELETE 완료");
            response.put("sql", sql);
            response.put("deletedData", deletedData);
            response.put("rowsAffected", deleted);
            response.put("targetCdcTable", "CDC_TOBE_BOOK");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            response.put("success", false);
            response.put("operation", "DELETE");
            response.put("message", "DELETE 실패: " + e.getMessage());
            return ResponseEntity.ok(response);
        }
    }

    // ==================== TOBE DB 테스트 ====================

    /**
     * TOBE DB에서 INSERT 테스트 실행
     */
    @PostMapping("/tobe/insert")
    public ResponseEntity<Map<String, Object>> testTobeInsert(@RequestBody(required = false) Map<String, String> request) {
        Map<String, Object> response = new HashMap<>();
        try {
            String title = request != null && request.containsKey("title")
                    ? request.get("title")
                    : "신규도서-" + System.currentTimeMillis();
            String author = request != null && request.containsKey("author")
                    ? request.get("author")
                    : "테스트저자";

            // 다음 ID 조회
            Integer nextId = tobeJdbcTemplate.queryForObject(
                    "SELECT NVL(MAX(BOOK_ID), 0) + 1 FROM TB_BOOK", Integer.class);

            // INSERT 실행
            String sql = "INSERT INTO TB_BOOK (BOOK_ID, TITLE, AUTHOR_NAME, CATEGORY_CD, IS_ACTIVE, CREATED_AT, CREATED_BY) " +
                    "VALUES (?, ?, ?, 'LIT', 1, SYSTIMESTAMP, 'TEST')";
            int inserted = tobeJdbcTemplate.update(sql, nextId, title, author);

            // 송신 메시지 구성
            Map<String, Object> sentData = new HashMap<>();
            sentData.put("BOOK_ID", nextId);
            sentData.put("TITLE", title);
            sentData.put("AUTHOR_NAME", author);
            sentData.put("CATEGORY_CD", "LIT");
            sentData.put("IS_ACTIVE", 1);

            response.put("success", true);
            response.put("operation", "INSERT");
            response.put("message", "TOBE DB INSERT 완료");
            response.put("sql", sql);
            response.put("sentData", sentData);
            response.put("rowsAffected", inserted);
            response.put("targetCdcTable", "CDC_ASIS_BOOK");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            response.put("success", false);
            response.put("operation", "INSERT");
            response.put("message", "INSERT 실패: " + e.getMessage());
            return ResponseEntity.ok(response);
        }
    }

    /**
     * TOBE DB에서 UPDATE 테스트 실행
     */
    @PostMapping("/tobe/update")
    public ResponseEntity<Map<String, Object>> testTobeUpdate(@RequestBody(required = false) Map<String, String> request) {
        Map<String, Object> response = new HashMap<>();
        try {
            String title = request != null && request.containsKey("title")
                    ? request.get("title")
                    : "수정됨-" + System.currentTimeMillis();

            int bookId = request != null && request.containsKey("bookId")
                    ? Integer.parseInt(request.get("bookId"))
                    : 1;

            // 변경 전 데이터 조회
            Map<String, Object> beforeData = tobeJdbcTemplate.queryForMap(
                    "SELECT BOOK_ID, TITLE, AUTHOR_NAME, IS_ACTIVE FROM TB_BOOK WHERE BOOK_ID = ?", bookId);

            // UPDATE 실행
            String sql = "UPDATE TB_BOOK SET TITLE = ?, UPDATED_AT = SYSTIMESTAMP, UPDATED_BY = 'TEST' WHERE BOOK_ID = ?";
            int updated = tobeJdbcTemplate.update(sql, title, bookId);

            // 변경 후 데이터
            Map<String, Object> afterData = new HashMap<>(beforeData);
            afterData.put("TITLE", title);

            response.put("success", true);
            response.put("operation", "UPDATE");
            response.put("message", "TOBE DB UPDATE 완료");
            response.put("sql", sql);
            response.put("beforeData", beforeData);
            response.put("afterData", afterData);
            response.put("rowsAffected", updated);
            response.put("targetCdcTable", "CDC_ASIS_BOOK");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            response.put("success", false);
            response.put("operation", "UPDATE");
            response.put("message", "UPDATE 실패: " + e.getMessage());
            return ResponseEntity.ok(response);
        }
    }

    /**
     * TOBE DB에서 DELETE 테스트 실행
     */
    @PostMapping("/tobe/delete")
    public ResponseEntity<Map<String, Object>> testTobeDelete(@RequestBody(required = false) Map<String, String> request) {
        Map<String, Object> response = new HashMap<>();
        try {
            // 삭제할 ID (기본: 가장 큰 ID)
            int bookId;
            if (request != null && request.containsKey("bookId")) {
                bookId = Integer.parseInt(request.get("bookId"));
            } else {
                Integer maxId = tobeJdbcTemplate.queryForObject(
                        "SELECT MAX(BOOK_ID) FROM TB_BOOK WHERE BOOK_ID > 3", Integer.class);
                if (maxId == null) {
                    response.put("success", false);
                    response.put("operation", "DELETE");
                    response.put("message", "삭제할 데이터 없음 (기본 데이터 BOOK_ID 1-3은 보호됨)");
                    return ResponseEntity.ok(response);
                }
                bookId = maxId;
            }

            // 삭제 전 데이터 조회
            Map<String, Object> deletedData = tobeJdbcTemplate.queryForMap(
                    "SELECT BOOK_ID, TITLE, AUTHOR_NAME, IS_ACTIVE FROM TB_BOOK WHERE BOOK_ID = ?", bookId);

            // DELETE 실행
            String sql = "DELETE FROM TB_BOOK WHERE BOOK_ID = ?";
            int deleted = tobeJdbcTemplate.update(sql, bookId);

            response.put("success", true);
            response.put("operation", "DELETE");
            response.put("message", "TOBE DB DELETE 완료");
            response.put("sql", sql);
            response.put("deletedData", deletedData);
            response.put("rowsAffected", deleted);
            response.put("targetCdcTable", "CDC_ASIS_BOOK");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            response.put("success", false);
            response.put("operation", "DELETE");
            response.put("message", "DELETE 실패: " + e.getMessage());
            return ResponseEntity.ok(response);
        }
    }

    // ==================== 데이터 조회 ====================

    /**
     * ASIS DB 현재 데이터 조회
     */
    @GetMapping("/asis/data")
    public ResponseEntity<Map<String, Object>> getAsisData() {
        Map<String, Object> response = new HashMap<>();
        try {
            List<Map<String, Object>> books = asisJdbcTemplate.queryForList(
                    "SELECT BOOK_ID, BOOK_TITLE, AUTHOR, STATUS FROM BOOK_INFO ORDER BY BOOK_ID FETCH FIRST 10 ROWS ONLY");
            response.put("success", true);
            response.put("table", "BOOK_INFO");
            response.put("count", books.size());
            response.put("data", books);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.ok(response);
        }
    }

    /**
     * TOBE DB 현재 데이터 조회
     */
    @GetMapping("/tobe/data")
    public ResponseEntity<Map<String, Object>> getTobeData() {
        Map<String, Object> response = new HashMap<>();
        try {
            List<Map<String, Object>> books = tobeJdbcTemplate.queryForList(
                    "SELECT BOOK_ID, TITLE, AUTHOR_NAME, IS_ACTIVE FROM TB_BOOK ORDER BY BOOK_ID FETCH FIRST 10 ROWS ONLY");
            response.put("success", true);
            response.put("table", "TB_BOOK");
            response.put("count", books.size());
            response.put("data", books);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.ok(response);
        }
    }

    /**
     * TOBE DB CDC 테이블 조회
     */
    @GetMapping("/tobe/cdc")
    public ResponseEntity<Map<String, Object>> getTobeCdc() {
        Map<String, Object> response = new HashMap<>();
        try {
            List<Map<String, Object>> cdcData = tobeJdbcTemplate.queryForList(
                    "SELECT CDC_SEQ, OPERATION, BOOK_ID, BOOK_TITLE, PROCESSED_YN, CHANGE_HASH " +
                            "FROM CDC_TOBE_BOOK ORDER BY CDC_SEQ DESC FETCH FIRST 10 ROWS ONLY");
            response.put("success", true);
            response.put("table", "CDC_TOBE_BOOK");
            response.put("count", cdcData.size());
            response.put("data", cdcData);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.ok(response);
        }
    }

    /**
     * ASIS DB CDC 테이블 조회
     */
    @GetMapping("/asis/cdc")
    public ResponseEntity<Map<String, Object>> getAsisCdc() {
        Map<String, Object> response = new HashMap<>();
        try {
            List<Map<String, Object>> cdcData = asisJdbcTemplate.queryForList(
                    "SELECT CDC_SEQ, OPERATION, BOOK_ID, BOOK_TITLE, PROCESSED_YN, CHANGE_HASH " +
                            "FROM CDC_ASIS_BOOK ORDER BY CDC_SEQ DESC FETCH FIRST 10 ROWS ONLY");
            response.put("success", true);
            response.put("table", "CDC_ASIS_BOOK");
            response.put("count", cdcData.size());
            response.put("data", cdcData);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.ok(response);
        }
    }
}
