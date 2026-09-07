package com.dqs.api.config;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.jpa.EntityManagerFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

/**
 * Azure SQL: the catalogs QuoteCenter now owns — countries, clubs, routes and
 * their tariffs, fiscal document types, payment methods, exchange rates.
 *
 * These used to be read out of the legacy application's own tables, which meant
 * the DQS schema decided ours and could change under us. See azure/README.md.
 *
 * <h2>Switched off unless asked for</h2>
 *
 * Everything here is behind {@code azure.datasource.enabled}. With the flag
 * false — the default — no datasource is opened, no repository bean is created,
 * and the application starts exactly as it did before. That matters while the
 * migration is in flight: a developer with no Azure instance, and any
 * environment not yet cut over, must keep working. It also means a typo in the
 * connection string cannot take down an environment that was not using it.
 *
 * The corollary is that nothing may inject a catalog repository unconditionally
 * until every environment has the flag on. Wire a consumer to it and you have
 * made Azure a hard startup requirement.
 *
 * <h2>Not primary</h2>
 *
 * MySQL keeps {@code @Primary}. A transaction started the ordinary way belongs
 * to MySQL; touching a catalog repository inside it will start a second,
 * independent transaction against Azure. There is no two-phase commit here and
 * none is wanted: the catalogs are read-only to us, so the only thing crossing
 * the boundary is a SELECT.
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "azure.datasource.enabled", havingValue = "true")
@EnableJpaRepositories(
    basePackages            = "com.dqs.api.catalog.repository",
    entityManagerFactoryRef = "catalogEntityManagerFactory",
    transactionManagerRef   = "catalogTransactionManager")
public class AzureCatalogDataSourceConfig {

    @Bean
    public DataSource catalogDataSource(
            @Value("${azure.datasource.url}") String url,
            @Value("${azure.datasource.username}") String username,
            @Value("${azure.datasource.password}") String password,
            @Value("${azure.datasource.pool-size:5}") int poolSize) {

        // Logged so a misconfigured environment says so at boot rather than at
        // the first catalog read. The URL can carry credentials in some formats,
        // so only the host and database are shown.
        log.info("[AzureCatalog] enabled, connecting to {}", describe(url));

        HikariDataSource ds = new HikariDataSource();
        ds.setDriverClassName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
        ds.setJdbcUrl(url);
        ds.setUsername(username);
        ds.setPassword(password);
        // The catalogs are read-mostly and low traffic; a large pool here would
        // just hold idle connections open against Azure.
        ds.setMaximumPoolSize(poolSize);
        ds.setPoolName("azure-catalog");
        return ds;
    }

    @Bean
    public LocalContainerEntityManagerFactoryBean catalogEntityManagerFactory(
            EntityManagerFactoryBuilder builder,
            @Qualifier("catalogDataSource") DataSource dataSource) {

        Map<String, Object> props = new HashMap<>();
        // validate, as on the MySQL side: these tables are ours, so a mapping
        // that disagrees with the schema is a bug we want at startup.
        props.put("hibernate.hbm2ddl.auto", "validate");
        props.put("hibernate.dialect", "org.hibernate.dialect.SQLServerDialect");
        // The schema is NVARCHAR throughout — club and route names carry Spanish
        // accents. Without this Hibernate maps String to varchar and validation
        // rejects every text column at startup.
        props.put("hibernate.use_nationalized_character_data", "true");

        return builder.dataSource(dataSource)
                      .packages("com.dqs.api.catalog.model")
                      .persistenceUnit("azureCatalog")
                      .properties(props)
                      .build();
    }

    @Bean
    public PlatformTransactionManager catalogTransactionManager(
            @Qualifier("catalogEntityManagerFactory") EntityManagerFactory emf) {
        return new JpaTransactionManager(emf);
    }

    /** Host and database from a JDBC URL, without anything that might be a secret. */
    private static String describe(String url) {
        if (url == null) return "(no url)";
        int q = url.indexOf(';');
        String head = q > 0 ? url.substring(0, q) : url;
        int db = url.indexOf("databaseName=");
        String name = db < 0 ? "" : url.substring(db).split(";")[0];
        return head + (name.isEmpty() ? "" : " " + name);
    }
}
