package com.cdc.sync.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * 다중 데이터소스 설정
 *
 * <p>ASIS DB와 TOBE DB 두 개의 데이터소스를 설정합니다.</p>
 *
 * <h3>사용 예시:</h3>
 * <pre>
 * {@code
 * @Autowired
 * @Qualifier("asisJdbcTemplate")
 * private JdbcTemplate asisJdbcTemplate;
 *
 * @Autowired
 * @Qualifier("tobeJdbcTemplate")
 * private JdbcTemplate tobeJdbcTemplate;
 * }
 * </pre>
 */
@Configuration
public class DataSourceConfig {

    // ===========================================
    // ASIS 데이터소스
    // ===========================================

    /**
     * ASIS DB 데이터소스 생성
     * application.yml의 asis.datasource.* 설정을 사용
     */
    @Bean(name = "asisDataSource")
    @ConfigurationProperties(prefix = "asis.datasource")
    public DataSource asisDataSource() {
        return DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .build();
    }

    /**
     * ASIS DB용 JdbcTemplate
     */
    @Bean(name = "asisJdbcTemplate")
    public JdbcTemplate asisJdbcTemplate(@Qualifier("asisDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    // ===========================================
    // TOBE 데이터소스
    // ===========================================

    /**
     * TOBE DB 데이터소스 생성
     * application.yml의 tobe.datasource.* 설정을 사용
     */
    @Bean(name = "tobeDataSource")
    @Primary  // 기본 데이터소스로 지정 (Spring 기본 동작용)
    @ConfigurationProperties(prefix = "tobe.datasource")
    public DataSource tobeDataSource() {
        return DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .build();
    }

    /**
     * TOBE DB용 JdbcTemplate
     */
    @Bean(name = "tobeJdbcTemplate")
    public JdbcTemplate tobeJdbcTemplate(@Qualifier("tobeDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
