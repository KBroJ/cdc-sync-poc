package com.cdc.sync.consumer;

import com.cdc.sync.domain.CdcEvent;
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
 * CDC Kafka Consumer (메시지 수신 레이어)
 *
 * [설계 의도]
 * - Debezium이 발행한 CDC 이벤트를 수신하여 Service 레이어에 전달
 * - 메시지 파싱 로직을 Consumer에 캡슐화
 * - @KafkaListener: Spring Kafka의 선언적 리스너
 *
 * [구독 토픽]
 * - ASIS 토픽: asis.ASIS_USER.* (BOOK_INFO, MEMBER_INFO, LEGACY_CODE)
 * - TOBE 토픽: tobe.TOBE_USER.* (TB_BOOK, TB_MEMBER, TB_NEW_SERVICE)
 *
 * [처리 흐름]
 * 1. Kafka 메시지 수신
 * 2. JSON 파싱 → CdcEvent 변환
 * 3. 토픽에 따라 CdcSyncService 호출
 * 4. Service가 상대 DB CDC 테이블에 INSERT
 *
 * [프로덕션 고려사항]
 * - 에러 처리: Dead Letter Queue 적용
 * - 재시도: @Retryable 또는 Kafka RetryTemplate
 * - 배치 처리: @KafkaListener(batch = true)
 * - 동적 토픽: 토픽 패턴 매칭 또는 동적 등록
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
     * ASIS 이벤트 처리 (→ TOBE DB로 전송)
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
     * TOBE 이벤트 처리 (→ ASIS DB로 전송)
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
     * [Debezium JSON 구조]
     * {
     *   "schema": { ... },
     *   "payload": {
     *     "before": { ... },
     *     "after": { ... },
     *     "source": { ... },
     *     "op": "c|u|d|r",
     *     "ts_ms": 1234567890123
     *   }
     * }
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
