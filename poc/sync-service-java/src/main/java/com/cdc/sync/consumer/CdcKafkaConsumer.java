package com.cdc.sync.consumer;

import com.cdc.sync.model.CdcEvent;
import com.cdc.sync.service.CdcSyncService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * CDC Kafka Consumer
 *
 * <p>Debezium이 발행한 CDC 이벤트를 수신하여 처리합니다.</p>
 *
 * <h3>구독 토픽:</h3>
 * <ul>
 *   <li>ASIS 토픽: asis.ASIS_USER.* (BOOK_INFO, MEMBER_INFO, LEGACY_CODE)</li>
 *   <li>TOBE 토픽: tobe.TOBE_USER.* (TB_BOOK, TB_MEMBER, TB_NEW_SERVICE)</li>
 * </ul>
 *
 * <h3>처리 흐름:</h3>
 * <ol>
 *   <li>Kafka 메시지 수신</li>
 *   <li>JSON 파싱 → CdcEvent 변환</li>
 *   <li>토픽에 따라 대상 DB CDC 테이블에 INSERT</li>
 * </ol>
 */
@Component
public class CdcKafkaConsumer {

    private static final Logger log = LoggerFactory.getLogger(CdcKafkaConsumer.class);

    private final CdcSyncService syncService;
    private final ObjectMapper objectMapper;

    public CdcKafkaConsumer(CdcSyncService syncService) {
        this.syncService = syncService;
        this.objectMapper = new ObjectMapper();
    }

    // ===========================================
    // ASIS -> TOBE 동기화 리스너
    // ===========================================

    /**
     * ASIS BOOK_INFO 테이블 변경 이벤트 처리
     */
    @KafkaListener(topics = "asis.ASIS_USER.BOOK_INFO", groupId = "cdc-sync-service")
    public void consumeAsisBookInfo(ConsumerRecord<String, String> record) {
        processAsisEvent(record, "CDC_TOBE_BOOK");
    }

    /**
     * ASIS MEMBER_INFO 테이블 변경 이벤트 처리
     */
    @KafkaListener(topics = "asis.ASIS_USER.MEMBER_INFO", groupId = "cdc-sync-service")
    public void consumeAsisMemberInfo(ConsumerRecord<String, String> record) {
        processAsisEvent(record, "CDC_TOBE_MEMBER");
    }

    /**
     * ASIS LEGACY_CODE 테이블 변경 이벤트 처리
     */
    @KafkaListener(topics = "asis.ASIS_USER.LEGACY_CODE", groupId = "cdc-sync-service")
    public void consumeAsisLegacyCode(ConsumerRecord<String, String> record) {
        processAsisEvent(record, "CDC_TOBE_LEGACY_CODE");
    }

    // ===========================================
    // TOBE -> ASIS 동기화 리스너
    // ===========================================

    /**
     * TOBE TB_BOOK 테이블 변경 이벤트 처리
     */
    @KafkaListener(topics = "tobe.TOBE_USER.TB_BOOK", groupId = "cdc-sync-service")
    public void consumeTobeBook(ConsumerRecord<String, String> record) {
        processTobeEvent(record, "CDC_ASIS_BOOK");
    }

    /**
     * TOBE TB_MEMBER 테이블 변경 이벤트 처리
     */
    @KafkaListener(topics = "tobe.TOBE_USER.TB_MEMBER", groupId = "cdc-sync-service")
    public void consumeTobeMember(ConsumerRecord<String, String> record) {
        processTobeEvent(record, "CDC_ASIS_MEMBER");
    }

    /**
     * TOBE TB_NEW_SERVICE 테이블 변경 이벤트 처리
     */
    @KafkaListener(topics = "tobe.TOBE_USER.TB_NEW_SERVICE", groupId = "cdc-sync-service")
    public void consumeTobeNewService(ConsumerRecord<String, String> record) {
        processTobeEvent(record, "CDC_ASIS_NEW_SERVICE");
    }

    // ===========================================
    // 내부 처리 메서드
    // ===========================================

    /**
     * ASIS 이벤트 처리 (-> TOBE DB로 전송)
     */
    private void processAsisEvent(ConsumerRecord<String, String> record, String targetTable) {
        String topic = record.topic();
        try {
            CdcEvent event = parseDebeziumMessage(record.value());
            if (event != null) {
                syncService.syncAsisToTobe(event, targetTable, topic);
            }
        } catch (Exception e) {
            log.error("Failed to process ASIS event for {}: {}", targetTable, e.getMessage(), e);
        }
    }

    /**
     * TOBE 이벤트 처리 (-> ASIS DB로 전송)
     */
    private void processTobeEvent(ConsumerRecord<String, String> record, String targetTable) {
        String topic = record.topic();
        try {
            CdcEvent event = parseDebeziumMessage(record.value());
            if (event != null) {
                syncService.syncTobeToAsis(event, targetTable, topic);
            }
        } catch (Exception e) {
            log.error("Failed to process TOBE event for {}: {}", targetTable, e.getMessage(), e);
        }
    }

    /**
     * Debezium 메시지 파싱
     *
     * <p>Debezium JSON 메시지를 CdcEvent 객체로 변환합니다.</p>
     *
     * @param message JSON 문자열
     * @return CdcEvent 객체
     */
    private CdcEvent parseDebeziumMessage(String message) {
        if (message == null || message.isEmpty()) {
            return null;
        }

        try {
            Map<String, Object> json = objectMapper.readValue(
                    message, new TypeReference<Map<String, Object>>() {});

            // payload 추출 (Debezium envelope 구조)
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = json.containsKey("payload")
                    ? (Map<String, Object>) json.get("payload")
                    : json;

            CdcEvent event = new CdcEvent();

            // Operation
            String op = (String) payload.get("op");
            event.setOperation(CdcEvent.convertOperation(op));

            // Before/After 데이터
            @SuppressWarnings("unchecked")
            Map<String, Object> before = (Map<String, Object>) payload.get("before");
            @SuppressWarnings("unchecked")
            Map<String, Object> after = (Map<String, Object>) payload.get("after");

            event.setBefore(before);
            event.setAfter(after);

            // Source 정보
            @SuppressWarnings("unchecked")
            Map<String, Object> source = (Map<String, Object>) payload.get("source");
            event.setSource(source);

            // Timestamp
            Object tsMs = payload.get("ts_ms");
            if (tsMs instanceof Number) {
                event.setSourceTimestamp(CdcEvent.convertTimestamp(((Number) tsMs).longValue()));
            } else {
                event.setSourceTimestamp(CdcEvent.convertTimestamp(null));
            }

            // Hash 생성
            Map<String, Object> data = event.getData();
            if (data != null) {
                event.setChangeHash(CdcSyncService.generateHash(data));
            }

            log.debug("Parsed CDC event: {}", event);
            return event;

        } catch (JsonProcessingException e) {
            log.error("Failed to parse Debezium message: {}", e.getMessage());
            return null;
        }
    }
}
