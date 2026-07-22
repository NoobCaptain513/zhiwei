package com.zihan.zhiwei.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;

@Configuration
@ConditionalOnProperty(prefix = "zhiwei.ai.rag", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RagDataSourceConfig {

    @Bean(name = "pgvectorDataSource")
    public DataSource pgvectorDataSource(
            @Value("${zhiwei.ai.datasource.pgvector.jdbc-url}") String jdbcUrl,
            @Value("${zhiwei.ai.datasource.pgvector.username}") String username,
            @Value("${zhiwei.ai.datasource.pgvector.password}") String password,
            @Value("${zhiwei.ai.datasource.pgvector.driver-class-name:org.postgresql.Driver}") String driver,
            @Value("${zhiwei.ai.datasource.pgvector.maximum-pool-size:10}") int maxPool) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(jdbcUrl);
        ds.setUsername(username);
        ds.setPassword(password);
        ds.setDriverClassName(driver);
        ds.setMaximumPoolSize(maxPool);
        ds.setPoolName("pgvector-pool");

        // 启动时自动建表/索引（幂等）
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
        populator.addScript(new ClassPathResource("db/pgvector/schema.sql"));
        populator.setContinueOnError(false);
        populator.execute(ds);
        return ds;
    }

    @Bean(name = "pgvectorJdbcTemplate")
    public JdbcTemplate pgvectorJdbcTemplate(@Qualifier("pgvectorDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}