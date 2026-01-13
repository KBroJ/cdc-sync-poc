package com.cdc.sync;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * CDC Sync Service 메인 애플리케이션
 *
 * Kafka에서 Debezium CDC 이벤트를 수신하여 상대 DB의 CDC 테이블에 INSERT합니다.
 *
 * 동작 흐름:
 * 1. ASIS DB 변경 → Debezium → Kafka → 이 서비스 → TOBE DB CDC 테이블
 * 2. TOBE DB 변경 → Debezium → Kafka → 이 서비스 → ASIS DB CDC 테이블
 *
 *
 * 환경변수:
 *   KAFKA_BOOTSTRAP_SERVERS: Kafka 브로커 주소 (기본값: kafka:29092)
 *   ASIS_DB_HOST, ASIS_DB_PORT, ASIS_DB_USER, ASIS_DB_PASSWORD
 *   TOBE_DB_HOST, TOBE_DB_PORT, TOBE_DB_USER, TOBE_DB_PASSWORD
 *
 * @author CDC Team
 * @version 1.0.0
 */
@SpringBootApplication
public class SyncServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(SyncServiceApplication.class, args);
    }
}
