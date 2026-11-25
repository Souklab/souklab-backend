package com.project.souklab.integration.phase9;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.mariadb.MariaDBContainer;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfEnvironmentVariable(named = "PHASE9_MARIADB_INTEGRATION", matches = "true")
class Phase9MariaDbMigrationTest {
    @Test
    void appliesAllMigrationsAndCreatesPhase9Invariants() throws SQLException {
        try (MariaDBContainer database = new MariaDBContainer("mariadb:12.3").withDatabaseName("souklab")
                .withUsername("phase9").withPassword("phase9-password")) {
            database.start();
            Flyway.configure()
                    .dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            try (Connection connection = database.createConnection(""); Statement statement = connection.createStatement()) {
                assertThat(migrationVersion(statement)).isEqualTo("24");
                assertThat(enumContains(statement, "audit_logs", "action", "PAYMENT_PAID")).isTrue();
                assertThat(enumContains(statement, "audit_logs", "action", "PAYMENT_FAILED")).isTrue();
                assertThat(enumContains(statement, "audit_logs", "action", "PAYMENT_CANCELED")).isTrue();
                assertThat(enumContains(statement, "audit_logs", "action", "SUBSCRIPTION_ACTIVATED")).isTrue();
                assertThat(tableExists(statement, "subscription_pricing")).isTrue();
                assertThat(tableExists(statement, "subscription_plan_entitlements")).isTrue();
                assertThat(tableExists(statement, "artisan_subscriptions")).isTrue();
                assertThat(tableExists(statement, "client_subscriptions")).isTrue();
                assertThat(tableExists(statement, "payments")).isTrue();
                assertThat(tableExists(statement, "payment_webhook_logs")).isTrue();
                assertThat(indexExists(statement, "artisan_subscriptions", "uk_artisan_active_subscription")).isTrue();
                assertThat(indexExists(statement, "client_subscriptions", "uk_client_active_subscription")).isTrue();
                assertThat(indexExists(statement, "payments", "uk_payment_provider_checkout")).isTrue();
                assertThat(indexExists(statement, "payment_webhook_logs", "uk_webhook_provider_event")).isTrue();

                statement.executeUpdate("INSERT INTO users (id, email_verified, failed_login_attempts, created_at, updated_at, email, status) "
                        + "VALUES ('account-1', 0, 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6), 'phase9@example.test', 'ACTIVE')");
                statement.executeUpdate("INSERT INTO audit_logs (created_at, updated_at, id, user_id, action, details) "
                        + "VALUES (CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6), 'audit-payment-paid', 'account-1', 'PAYMENT_PAID', 'payment webhook')");
                statement.executeUpdate("INSERT INTO audit_logs (created_at, updated_at, id, user_id, action, details) "
                        + "VALUES (CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6), 'audit-subscription-active', 'account-1', 'SUBSCRIPTION_ACTIVATED', 'subscription webhook')");
                statement.executeUpdate("INSERT INTO artisan_subscriptions "
                        + "(id, account_id, status, plan_id, plan_name, billing_period, amount, currency, entitlements_snapshot, version_number, created_at, updated_at) "
                        + "VALUES ('artisan-subscription-1', 'account-1', 'ACTIVE', 'plan-1', 'Monthly', 'MONTHLY', 1000, 'DZD', '{}', 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))");
                assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO artisan_subscriptions "
                        + "(id, account_id, status, plan_id, plan_name, billing_period, amount, currency, entitlements_snapshot, version_number, created_at, updated_at) "
                        + "VALUES ('artisan-subscription-2', 'account-1', 'ACTIVE', 'plan-1', 'Monthly', 'MONTHLY', 1000, 'DZD', '{}', 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))"))
                        .isInstanceOf(SQLException.class);

                statement.executeUpdate("INSERT INTO payments "
                        + "(id, account_id, subscription_id, provider, status, provider_checkout_id, amount, currency, fees, plan_snapshot, idempotency_key, version_number, created_at, updated_at) "
                        + "VALUES ('payment-1', 'account-1', 'artisan-subscription-1', 'CHARGILY', 'PENDING', 'checkout-1', 1000, 'DZD', 0, '{}', 'key-1', 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))");
                assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO payments "
                        + "(id, account_id, subscription_id, provider, status, provider_checkout_id, amount, currency, fees, plan_snapshot, idempotency_key, version_number, created_at, updated_at) "
                        + "VALUES ('payment-2', 'account-1', 'artisan-subscription-1', 'CHARGILY', 'PENDING', 'checkout-1', 1000, 'DZD', 0, '{}', 'key-2', 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))"))
                        .isInstanceOf(SQLException.class);

                statement.executeUpdate("INSERT INTO payment_webhook_logs "
                        + "(id, provider_event_id, event_type, signature_valid, encrypted_payload, status, created_at, updated_at) "
                        + "VALUES ('webhook-1', 'event-1', 'checkout.paid', 1, 'encrypted', 'RECEIVED', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))");
                assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO payment_webhook_logs "
                        + "(id, provider_event_id, event_type, signature_valid, encrypted_payload, status, created_at, updated_at) "
                        + "VALUES ('webhook-2', 'event-1', 'checkout.paid', 1, 'encrypted', 'RECEIVED', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))"))
                        .isInstanceOf(SQLException.class);
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
