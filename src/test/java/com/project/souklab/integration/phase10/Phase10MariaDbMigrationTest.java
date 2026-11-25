package com.project.souklab.integration.phase10;

import com.project.souklab.analytics.AnalyticsEvent;
import com.project.souklab.analytics.AnalyticsMetric;
import com.project.souklab.security.Permission;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.mariadb.MariaDBContainer;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "PHASE10_MARIADB_INTEGRATION", matches = "true")
class Phase10MariaDbMigrationTest {
    @Test
    void appliesAnalyticsMigrationsAndCreatesProductionInvariants() throws SQLException {
        try (MariaDBContainer database = new MariaDBContainer("mariadb:12.3")
                .withDatabaseName("souklab")
                .withUsername("phase10")
                .withPassword("phase10-password")) {
            database.start();
            Flyway.configure()
                    .dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
                    .locations("filesystem:src/main/resources/db/migration")
                    .load()
                    .migrate();

            try (Connection connection = database.createConnection(""); Statement statement = connection.createStatement()) {
                assertThat(migrationVersion(statement)).isEqualTo("24");
                assertThat(enumContains(statement, "audit_logs", "action", "CATALOG_TECHNIQUE_CREATED")).isTrue();
                assertThat(tableContains(statement, "permissions", "permission_key", Permission.Admin.CATALOG.value())).isTrue();
                assertThat(enumContains(statement, "audit_logs", "action", "PAYMENT_PAID")).isTrue();
                assertThat(enumContains(statement, "audit_logs", "action", "PAYMENT_FAILED")).isTrue();
                assertThat(enumContains(statement, "audit_logs", "action", "PAYMENT_CANCELED")).isTrue();
                assertThat(enumContains(statement, "audit_logs", "action", "SUBSCRIPTION_ACTIVATED")).isTrue();
                assertThat(tableExists(statement, "activity_events")).isTrue();
                assertThat(tableExists(statement, "analytics_outbox_events")).isTrue();
                assertThat(tableExists(statement, "analytics_processed_events")).isTrue();
                assertThat(tableExists(statement, "daily_kpi_rollups")).isTrue();
                assertThat(tableExists(statement, "analytics_jobs")).isTrue();
                assertThat(tableExists(statement, "analytics_job_artifacts")).isTrue();
                assertThat(tableExists(statement, "analytics_maintenance_jobs")).isTrue();
                assertThat(columnExists(statement, "content_reports", "resolved_at")).isTrue();
                assertThat(columnExists(statement, "payments", "manual_grant")).isTrue();
                assertThat(columnExists(statement, "analytics_outbox_events", "next_attempt_at")).isTrue();
                assertThat(tableContains(statement, "permissions", "permission_key", Permission.Analytics.ADMIN.value())).isTrue();
                assertThat(tableContains(statement, "permissions", "permission_key", Permission.Financial.ADMIN.value())).isTrue();
                assertThat(indexExists(statement, "analytics_outbox_events", "idx_analytics_outbox_next_attempt")).isTrue();
                assertThat(indexExists(statement, "payments", "idx_payment_manual_status_created")).isTrue();
                assertThat(indexExists(statement, "analytics_job_artifacts", "idx_analytics_artifact_job")).isTrue();

                String loginRollupKey = AnalyticsMetric.EventRollup.PREFIX.value()
                        + AnalyticsEvent.Authentication.Login.SUCCEEDED.value();
                statement.executeUpdate("INSERT INTO daily_kpi_rollups "
                        + "(id, rollup_date, kpi_key, metric_value, source_version, created_at, updated_at) "
                        + "VALUES (UUID(), '2026-01-01', '" + loginRollupKey + "', 1, 1, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)) "
                        + "ON DUPLICATE KEY UPDATE metric_value = metric_value + 1, updated_at = CURRENT_TIMESTAMP(6)");
                statement.executeUpdate("INSERT INTO daily_kpi_rollups "
                        + "(id, rollup_date, kpi_key, metric_value, source_version, created_at, updated_at) "
                        + "VALUES (UUID(), '2026-01-01', '" + loginRollupKey + "', 1, 1, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)) "
                        + "ON DUPLICATE KEY UPDATE metric_value = metric_value + 1, updated_at = CURRENT_TIMESTAMP(6)");
                try (ResultSet result = statement.executeQuery("SELECT metric_value FROM daily_kpi_rollups "
                        + "WHERE rollup_date = '2026-01-01' AND kpi_key = '" + loginRollupKey + "'")) {
                    result.next();
                    assertThat(result.getLong(1)).isEqualTo(2L);
                }
            }
        }
    }

    private String migrationVersion(Statement statement) throws SQLException {
        try (ResultSet result = statement.executeQuery("SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1")) {
            result.next();
            return result.getString(1);
        }
    }

    private boolean tableExists(Statement statement, String table) throws SQLException {
        try (ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = '" + table + "'")) {
            result.next();
            return result.getInt(1) == 1;
        }
    }

    private boolean columnExists(Statement statement, String table, String column) throws SQLException {
        try (ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = '" + table + "' AND column_name = '" + column + "'")) {
            result.next();
            return result.getInt(1) == 1;
        }
    }

    private boolean tableContains(Statement statement, String table, String column, String value) throws SQLException {
        try (ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM `" + table + "` WHERE `" + column + "` = '" + value + "'")) {
            result.next();
            return result.getInt(1) == 1;
        }
    }

    private boolean indexExists(Statement statement, String table, String index) throws SQLException {
        try (ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = '" + table + "' AND index_name = '" + index + "'")) {
            result.next();
            return result.getInt(1) > 0;
        }
    }

    private boolean enumContains(Statement statement, String table, String column, String value) throws SQLException {
        try (ResultSet result = statement.executeQuery("SELECT COLUMN_TYPE FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = '" + table + "' AND column_name = '" + column + "'")) {
            result.next();
            return result.getString(1).contains("'" + value + "'");
        }
    }
}
