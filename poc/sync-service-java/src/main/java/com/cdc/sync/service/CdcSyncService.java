package com.cdc.sync.service;

import com.cdc.sync.model.CdcEvent;
import com.cdc.sync.monitoring.CdcMonitoringService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * CDC 동기화 서비스
 *
 * <p>CDC 이벤트를 수신하여 상대 DB의 CDC 테이블에 INSERT합니다.</p>
 *
 * <h3>주요 기능:</h3>
 * <ul>
 *   <li>ASIS 이벤트 → TOBE DB CDC 테이블 INSERT</li>
 *   <li>TOBE 이벤트 → ASIS DB CDC 테이블 INSERT</li>
 *   <li>변경 데이터 해시 생성 (무한루프 방지용)</li>
 * </ul>
 */
@Service
public class CdcSyncService {

    private static final Logger log = LoggerFactory.getLogger(CdcSyncService.class);

    private final JdbcTemplate asisJdbcTemplate;
    private final JdbcTemplate tobeJdbcTemplate;
    private final CdcMonitoringService monitoringService;

    public CdcSyncService(
            @Qualifier("asisJdbcTemplate") JdbcTemplate asisJdbcTemplate,
            @Qualifier("tobeJdbcTemplate") JdbcTemplate tobeJdbcTemplate,
            CdcMonitoringService monitoringService) {
        this.asisJdbcTemplate = asisJdbcTemplate;
        this.tobeJdbcTemplate = tobeJdbcTemplate;
        this.monitoringService = monitoringService;
    }

    /**
     * ASIS 이벤트를 TOBE DB CDC 테이블에 INSERT
     *
     * @param event CDC 이벤트
     * @param targetTable 대상 CDC 테이블명 (예: CDC_TOBE_BOOK)
     * @param topic 원본 Kafka 토픽명
     */
    public void syncAsisToTobe(CdcEvent event, String targetTable, String topic) {
        insertToCdcTable(tobeJdbcTemplate, event, targetTable, "ASIS->TOBE", topic);
    }

    /**
     * TOBE 이벤트를 ASIS DB CDC 테이블에 INSERT
     *
     * @param event CDC 이벤트
     * @param targetTable 대상 CDC 테이블명 (예: CDC_ASIS_BOOK)
     * @param topic 원본 Kafka 토픽명
     */
    public void syncTobeToAsis(CdcEvent event, String targetTable, String topic) {
        insertToCdcTable(asisJdbcTemplate, event, targetTable, "TOBE->ASIS", topic);
    }

    /**
     * CDC 테이블에 INSERT 실행
     *
     * @param jdbcTemplate 대상 DB JdbcTemplate
     * @param event CDC 이벤트
     * @param targetTable 대상 테이블명
     * @param direction 동기화 방향 (로깅용)
     * @param topic 원본 Kafka 토픽명
     */
    private void insertToCdcTable(JdbcTemplate jdbcTemplate, CdcEvent event,
                                  String targetTable, String direction, String topic) {
        // 모니터링: 수신 기록
        monitoringService.recordReceived(topic);

        // 데이터 미리보기 생성
        String dataPreview = createDataPreview(event.getData());

        try {
            Map<String, Object> data = event.getData();
            if (data == null || data.isEmpty()) {
                log.warn("[{}] Empty data for table: {}", direction, targetTable);
                return;
            }

            // 동적 INSERT SQL 생성
            List<String> columns = new ArrayList<>();
            List<Object> values = new ArrayList<>();

            // 기본 메타 컬럼 추가
            columns.add("OPERATION");
            values.add(event.getOperation());

            columns.add("SOURCE_TIMESTAMP");
            values.add(Timestamp.valueOf(event.getSourceTimestamp()));

            columns.add("CHANGE_HASH");
            values.add(event.getChangeHash());

            columns.add("PROCESSED_YN");
            values.add("N");

            // 데이터 컬럼 추가
            for (Map.Entry<String, Object> entry : data.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();

                // Debezium의 복합 타입 처리 (예: NUMBER 타입)
                if (value instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> complexValue = (Map<String, Object>) value;
                    // scale/value 형태의 NUMBER 타입 처리
                    if (complexValue.containsKey("value")) {
                        value = decodeDebeziumNumber(complexValue);
                    }
                }

                // Debezium timestamp 타입 처리 (epoch milliseconds -> java.sql.Timestamp)
                // 날짜 관련 컬럼명 체크
                String upperKey = key.toUpperCase();
                if (value instanceof Long || value instanceof Integer) {
                    if (upperKey.contains("DATE") || upperKey.contains("_AT") ||
                        upperKey.contains("TIME") || upperKey.contains("TIMESTAMP")) {
                        value = convertEpochToTimestamp(((Number) value).longValue());
                    }
                }

                columns.add(upperKey);
                values.add(value);
            }

            // INSERT SQL 생성
            String columnList = String.join(", ", columns);
            String placeholders = String.join(", ", columns.stream().map(c -> "?").toList());
            String sql = String.format("INSERT INTO %s (%s) VALUES (%s)",
                    targetTable, columnList, placeholders);

            // 실행
            jdbcTemplate.update(sql, values.toArray());

            // 모니터링: 성공 기록 (데이터 미리보기 포함)
            monitoringService.recordSuccess(topic, targetTable, event.getOperation(), event.getChangeHash(), dataPreview);

            log.info("[{}] Inserted into {}: {} - hash={}",
                    direction, targetTable, event.getOperation(),
                    event.getChangeHash().substring(0, 16));

        } catch (Exception e) {
            // 모니터링: 실패 기록 (데이터 미리보기 포함)
            monitoringService.recordFailure(topic, targetTable, event.getOperation(), e.getMessage(), dataPreview);

            log.error("[{}] Failed to insert into {}: {}",
                    direction, targetTable, e.getMessage(), e);
        }
    }

    /**
     * 데이터 미리보기 문자열 생성
     */
    private String createDataPreview(Map<String, Object> data) {
        if (data == null || data.isEmpty()) {
            return "{}";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{");
        int count = 0;
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            if (count > 0) sb.append(", ");
            String key = entry.getKey();
            Object value = entry.getValue();

            // Debezium 복합 타입 디코딩
            if (value instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> complexValue = (Map<String, Object>) value;
                if (complexValue.containsKey("value")) {
                    value = decodeDebeziumNumber(complexValue);
                }
            }

            // 값 포맷팅
            String valueStr;
            if (value == null) {
                valueStr = "null";
            } else if (value instanceof String) {
                String str = (String) value;
                valueStr = "\"" + (str.length() > 30 ? str.substring(0, 30) + "..." : str) + "\"";
            } else {
                valueStr = String.valueOf(value);
            }

            sb.append(key).append(": ").append(valueStr);
            count++;
            if (count >= 5) {
                sb.append(", ...");
                break;
            }
        }
        sb.append("}");
        return sb.toString();
    }

    /**
     * Epoch 밀리초를 java.sql.Timestamp로 변환
     *
     * <p>Debezium은 Oracle DATE/TIMESTAMP를 epoch milliseconds로 전송합니다.</p>
     *
     * @param epochMs epoch 밀리초
     * @return java.sql.Timestamp
     */
    private Timestamp convertEpochToTimestamp(long epochMs) {
        return new Timestamp(epochMs);
    }

    /**
     * Debezium NUMBER 타입 디코딩
     *
     * <p>Debezium은 Oracle NUMBER를 다음 형식으로 전송합니다:</p>
     * <pre>
     * { "scale": 0, "value": "AQ==" }  // Base64 인코딩된 BigInteger
     * </pre>
     *
     * @param complexValue Debezium 복합 값
     * @return 디코딩된 숫자
     */
    private Object decodeDebeziumNumber(Map<String, Object> complexValue) {
        try {
            Object valueObj = complexValue.get("value");
            if (valueObj instanceof String base64Value) {
                byte[] bytes = java.util.Base64.getDecoder().decode(base64Value);
                java.math.BigInteger bigInt = new java.math.BigInteger(bytes);

                Object scaleObj = complexValue.get("scale");
                int scale = scaleObj != null ? ((Number) scaleObj).intValue() : 0;

                if (scale == 0) {
                    return bigInt.longValue();
                } else {
                    return new java.math.BigDecimal(bigInt, scale);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to decode Debezium number: {}", e.getMessage());
        }
        return complexValue;
    }

    /**
     * 데이터 해시 생성 (SHA-256)
     *
     * <p>무한루프 방지를 위해 변경 데이터의 해시를 생성합니다.</p>
     *
     * @param data 데이터 맵
     * @return SHA-256 해시 문자열
     */
    public static String generateHash(Map<String, Object> data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String dataStr = data.toString();  // 간단한 문자열 변환
            byte[] hash = digest.digest(dataStr.getBytes(StandardCharsets.UTF_8));

            // Hex 문자열로 변환
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
