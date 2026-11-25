package com.project.souklab.integration.favorite;

import com.project.souklab.security.Permission;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.mariadb.MariaDBContainer;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates the client favorites migration and current schema invariants on MariaDB.
 * Verifies table structure, constraints, indexes, permission seeding,
 * and selective client permission backfill.
 */
class ClientFavoritesMigrationTest {

    @Test
    void appliesV16MigrationAndVerifiesInvariants() throws SQLException {
        try (MariaDBContainer database = new MariaDBContainer("mariadb:12.3")
                .withDatabaseName("souklab")
                .withUsername("fav_test")
                .withPassword("fav_password")) {
            database.start();

            Flyway.configure()
                    .dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
                    .locations("filesystem:src/main/resources/db/migration")
                    .target("15")
                    .load()
                    .migrate();

            try (Connection connection = database.createConnection("");
                 Statement statement = connection.createStatement()) {

                statement.executeUpdate("INSERT INTO users (id, email, password, status, email_verified, failed_login_attempts, created_at, updated_at) "
                        + "VALUES ('client-user-1', 'client1@test.com', 'pwd', 'ACTIVE', 1, 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))");
                statement.executeUpdate("INSERT INTO clients (id, client_type, is_premium, is_verified, created_at, updated_at) "
                        + "VALUES ('client-user-1', 'INDIVIDUAL', 0, 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))");

                statement.executeUpdate("INSERT INTO users (id, email, password, status, email_verified, failed_login_attempts, created_at, updated_at) "
                        + "VALUES ('artisan-user-1', 'artisan1@test.com', 'pwd', 'ACTIVE', 1, 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))");
                statement.executeUpdate("INSERT INTO artisans (id, is_teacher, is_verified, is_premium, rating, response_rate, reviews_count, views_count, created_at, updated_at) "
                        + "VALUES ('artisan-user-1', 0, 0, 0, 0.0, 100, 0, 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))");

                statement.executeUpdate("INSERT INTO users (id, email, password, status, email_verified, failed_login_attempts, created_at, updated_at) "
                        + "VALUES ('admin-user-1', 'admin1@test.com', 'pwd', 'ACTIVE', 1, 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))");
                statement.executeUpdate("INSERT INTO user_permissions (user_id, permission_id) "
                        + "SELECT 'admin-user-1', id FROM permissions WHERE permission_key = 'permission:admin:users'");
            }

            Flyway.configure()
                    .dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
                    .locations("filesystem:src/main/resources/db/migration")
                    .load()
                    .migrate();

            try (Connection connection = database.createConnection("");
                 Statement statement = connection.createStatement()) {

                assertThat(migrationVersion(statement)).isEqualTo("24");
                assertThat(columnExists(statement, "users", "account_role")).isTrue();
                assertThat(userRole(statement, "client-user-1")).isEqualTo("CLIENT");
                assertThat(userRole(statement, "artisan-user-1")).isEqualTo("ARTISAN");
                assertThat(userRole(statement, "admin-user-1")).isEqualTo("ADMIN");
                assertThat(tableExists(statement, "client_favorite_artisans")).isTrue();
                assertThat(columnExists(statement, "client_favorite_artisans", "id")).isTrue();
                assertThat(columnExists(statement, "client_favorite_artisans", "client_id")).isTrue();
                assertThat(columnExists(statement, "client_favorite_artisans", "artisan_id")).isTrue();
                assertThat(columnExists(statement, "client_favorite_artisans", "created_at")).isTrue();
                assertThat(columnExists(statement, "client_favorite_artisans", "updated_at")).isTrue();
                assertThat(columnExists(statement, "client_favorite_artisans", "deleted_at")).isTrue();

                assertThat(indexExists(statement, "client_favorite_artisans", "uk_client_favorite_artisans_client_artisan")).isTrue();
                assertThat(indexExists(statement, "client_favorite_artisans", "idx_client_favorite_artisans_client_created")).isTrue();
                assertThat(indexExists(statement, "client_favorite_artisans", "idx_client_favorite_artisans_artisan")).isTrue();

                assertThat(tableContains(statement, "permissions", "permission_key", Permission.Client.FAVORITES.value())).isTrue();

                assertThat(userHasPermission(statement, "client-user-1", Permission.Client.FAVORITES.value())).isTrue();
                assertThat(userHasPermission(statement, "artisan-user-1", Permission.Client.FAVORITES.value())).isFalse();
                assertThat(userHasPermission(statement, "admin-user-1", Permission.Client.FAVORITES.value())).isFalse();
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

    private boolean indexExists(Statement statement, String table, String index) throws SQLException {
        try (ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = '" + table + "' AND index_name = '" + index + "'")) {
            result.next();
            return result.getInt(1) >= 1;
        }
    }

    private boolean tableContains(Statement statement, String table, String column, String value) throws SQLException {
        try (ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM `" + table + "` WHERE `" + column + "` = '" + value + "'")) {
            result.next();
            return result.getInt(1) == 1;
        }
    }

    private String userRole(Statement statement, String userId) throws SQLException {
        try (ResultSet result = statement.executeQuery("SELECT account_role FROM users WHERE id = '" + userId + "'")) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }

    private boolean userHasPermission(Statement statement, String userId, String permissionKey) throws SQLException {
        String query = "SELECT COUNT(*) FROM user_permissions up "
                + "JOIN permissions p ON up.permission_id = p.id "
                + "WHERE up.user_id = '" + userId + "' AND p.permission_key = '" + permissionKey + "'";
        try (ResultSet result = statement.executeQuery(query)) {
            result.next();
            return result.getInt(1) == 1;
        }
    }
}
