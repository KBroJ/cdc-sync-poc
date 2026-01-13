package com.cdc.sync.monitoring;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * CDC 테스트 API 컨트롤러
 *
 * <p>대시보드에서 테스트를 실행할 수 있게 해주는 API</p>
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

    /**
     * ASIS DB에서 UPDATE 테스트 실행
     */
    @PostMapping("/asis/update")
    public ResponseEntity<Map<String, Object>> testAsisUpdate(@RequestBody(required = false) Map<String, String> request) {
        Map<String, Object> response = new HashMap<>();
        try {
            String title = request != null && request.containsKey("title")
                    ? request.get("title")
                    : "테스트-" + System.currentTimeMillis();

            int bookId = request != null && request.containsKey("bookId")
                    ? Integer.parseInt(request.get("bookId"))
                    : 1;

            // UPDATE 실행
            String sql = "UPDATE BOOK_INFO SET BOOK_TITLE = ? WHERE BOOK_ID = ?";
            int updated = asisJdbcTemplate.update(sql, title, bookId);

            response.put("success", true);
            response.put("message", "ASIS DB UPDATE 완료");
            response.put("detail", Map.of(
                    "table", "BOOK_INFO",
                    "bookId", bookId,
                    "newTitle", title,
                    "rowsAffected", updated
            ));
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "UPDATE 실패: " + e.getMessage());
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
                    : "테스트-" + System.currentTimeMillis();

            int bookId = request != null && request.containsKey("bookId")
                    ? Integer.parseInt(request.get("bookId"))
                    : 1;

            // UPDATE 실행
            String sql = "UPDATE TB_BOOK SET TITLE = ? WHERE BOOK_ID = ?";
            int updated = tobeJdbcTemplate.update(sql, title, bookId);

            response.put("success", true);
            response.put("message", "TOBE DB UPDATE 완료");
            response.put("detail", Map.of(
                    "table", "TB_BOOK",
                    "bookId", bookId,
                    "newTitle", title,
                    "rowsAffected", updated
            ));
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "UPDATE 실패: " + e.getMessage());
            return ResponseEntity.ok(response);
        }
    }

    /**
     * ASIS DB 현재 데이터 조회
     */
    @GetMapping("/asis/data")
    public ResponseEntity<Map<String, Object>> getAsisData() {
        Map<String, Object> response = new HashMap<>();
        try {
            var books = asisJdbcTemplate.queryForList(
                    "SELECT BOOK_ID, BOOK_TITLE, AUTHOR, STATUS FROM BOOK_INFO ORDER BY BOOK_ID FETCH FIRST 5 ROWS ONLY");
            response.put("success", true);
            response.put("table", "BOOK_INFO");
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
            var cdcData = tobeJdbcTemplate.queryForList(
                    "SELECT CDC_SEQ, OPERATION, BOOK_ID, BOOK_TITLE, PROCESSED_YN, CHANGE_HASH " +
                    "FROM CDC_TOBE_BOOK ORDER BY CDC_SEQ DESC FETCH FIRST 10 ROWS ONLY");
            response.put("success", true);
            response.put("table", "CDC_TOBE_BOOK");
            response.put("data", cdcData);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.ok(response);
        }
    }

    /**
     * 의도적 에러 발생 테스트 (존재하지 않는 테이블 업데이트)
     */
    @PostMapping("/error")
    public ResponseEntity<Map<String, Object>> testError() {
        Map<String, Object> response = new HashMap<>();
        try {
            // 존재하지 않는 테이블에 UPDATE 시도
            asisJdbcTemplate.update("UPDATE NON_EXISTENT_TABLE SET COL = 'test' WHERE ID = 1");
            response.put("success", true);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "예상대로 에러 발생: " + e.getMessage());
            return ResponseEntity.ok(response);
        }
    }
}
