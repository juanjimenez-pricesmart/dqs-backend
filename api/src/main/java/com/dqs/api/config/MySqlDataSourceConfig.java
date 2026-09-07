package com.dqs.api.config;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.jpa.EntityManagerFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

/**
 * The main database: MySQL, holding every quotation table.
 *
 * This used to be Spring Boot's auto-configured single datasource. Adding a
 * second one takes that away — with two candidates, nothing can be inferred —
 * so the beans are declared here instead. Behaviour is unchanged: it is
 * {@code @Primary}, so anything not explicitly asking for the other one gets
 * this, and the property names under {@code spring.datasource} are the same as
 * before.
 *
 * It scans {@code com.dqs.api.model} for entities and
 * {@code com.dqs.api.repository} for repositories. The Azure catalog entities
 * deliberately live outside both — under {@code com.dqs.api.catalog} — because
 * package scanning is recursive: a {@code model.catalog} subpackage would be
 * picked up here too, and Hibernate would try to validate the Azure tables
 * against MySQL and refuse to start.
 */
@Configuration
@EnableJpaRepositories(
    basePackages          = "com.dqs.api.repository",
    entityManagerFactoryRef = "entityManagerFactory",
    transactionManagerRef   = "transactionManager")
public class MySqlDataSourceConfig {

    /**
     * Binds the same {@code spring.datasource.*} properties the auto-configuration
     * used to.
     *
     * It has to go through DataSourceProperties rather than DataSourceBuilder
     * bound directly: Hikari's own setter is {@code jdbcUrl}, so binding
     * {@code spring.datasource.url} straight onto the builder leaves the URL
     * unset and the application fails at startup with "jdbcUrl is required with
     * driverClassName". initializeDataSourceBuilder does that translation.
     */
    @Primary
    @Bean
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties dataSourceProperties() {
        return new DataSourceProperties();
    }

    @Primary
    @Bean
    @ConfigurationProperties("spring.datasource.hikari")
    public DataSource dataSource(DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().build();
    }

    @Primary
    @Bean
    public LocalContainerEntityManagerFactoryBean entityManagerFactory(
            EntityManagerFactoryBuilder builder,
            @Qualifier("dataSource") DataSource dataSource) {

        Map<String, Object> props = new HashMap<>();
        props.put("hibernate.hbm2ddl.auto", "validate");
        props.put("hibernate.dialect", "org.hibernate.dialect.MySQLDialect");

        return builder.dataSource(dataSource)
                      .packages("com.dqs.api.model")
                      .persistenceUnit("mysql")
                      .properties(props)
                      .build();
    }

    @Primary
    @Bean
    public PlatformTransactionManager transactionManager(
            @Qualifier("entityManagerFactory") EntityManagerFactory emf) {
        return new JpaTransactionManager(emf);
    }
}
