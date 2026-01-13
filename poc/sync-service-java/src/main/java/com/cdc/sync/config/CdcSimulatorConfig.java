package com.cdc.sync.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * CDC 시뮬레이터용 테이블 설정
 * application.yml의 cdc.simulator 섹션을 바인딩
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "cdc.simulator")
public class CdcSimulatorConfig {

    private List<TableConfig> tables = new ArrayList<>();

    /**
     * 테이블 설정
     */
    @Data
    public static class TableConfig {
        private String name;
        private String displayName;
        private SyncDirection direction;
        private DbConfig asis;
        private DbConfig tobe;
        private CdcTableConfig cdcTable;
        private StagingTableConfig stagingTable;

        /**
         * 동기화 방향 표시 텍스트
         */
        public String getDirectionDisplay() {
            return switch (direction) {
                case ASIS_TO_TOBE -> "구→신";
                case TOBE_TO_ASIS -> "신→구";
                case BIDIRECTIONAL -> "양방향";
            };
        }

        /**
         * 동기화 방향 아이콘
         */
        public String getDirectionIcon() {
            return switch (direction) {
                case ASIS_TO_TOBE -> "→";
                case TOBE_TO_ASIS -> "←";
                case BIDIRECTIONAL -> "↔";
            };
        }

        /**
         * ASIS에서 테스트 가능 여부
         */
        public boolean isAsisTestable() {
            return direction == SyncDirection.ASIS_TO_TOBE || direction == SyncDirection.BIDIRECTIONAL;
        }

        /**
         * TOBE에서 테스트 가능 여부
         */
        public boolean isTobeTestable() {
            return direction == SyncDirection.TOBE_TO_ASIS || direction == SyncDirection.BIDIRECTIONAL;
        }
    }

    /**
     * 동기화 방향
     */
    public enum SyncDirection {
        ASIS_TO_TOBE,    // 구→신 (단방향)
        TOBE_TO_ASIS,    // 신→구 (단방향)
        BIDIRECTIONAL    // 양방향
    }

    /**
     * DB별 테이블 설정
     */
    @Data
    public static class DbConfig {
        private String table;
        private String pk;
        private List<ColumnConfig> columns = new ArrayList<>();

        /**
         * 필수 컬럼만 반환
         */
        public List<ColumnConfig> getRequiredColumns() {
            return columns.stream()
                    .filter(ColumnConfig::isRequired)
                    .toList();
        }

        /**
         * PK가 아닌 컬럼만 반환
         */
        public List<ColumnConfig> getNonPkColumns() {
            return columns.stream()
                    .filter(c -> !c.getName().equals(pk))
                    .toList();
        }
    }

    /**
     * 컬럼 설정
     */
    @Data
    public static class ColumnConfig {
        private String name;
        private String type;
        private String display;
        private boolean required;
        private String defaultValue;

        public String getDefault() {
            return defaultValue;
        }

        public void setDefault(String defaultValue) {
            this.defaultValue = defaultValue;
        }
    }

    /**
     * CDC 테이블 설정
     */
    @Data
    public static class CdcTableConfig {
        private String asis;
        private String tobe;
    }

    /**
     * STAGING 테이블 설정
     */
    @Data
    public static class StagingTableConfig {
        private String asis;
        private String tobe;
    }

    /**
     * 테이블명으로 설정 찾기
     */
    public TableConfig findByName(String name) {
        return tables.stream()
                .filter(t -> t.getName().equalsIgnoreCase(name))
                .findFirst()
                .orElse(null);
    }

    /**
     * 특정 방향의 테이블만 반환
     */
    public List<TableConfig> findByDirection(SyncDirection direction) {
        return tables.stream()
                .filter(t -> t.getDirection() == direction)
                .toList();
    }
}
