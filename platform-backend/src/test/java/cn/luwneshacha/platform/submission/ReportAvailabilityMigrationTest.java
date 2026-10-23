package cn.luwneshacha.platform.submission;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class ReportAvailabilityMigrationTest {
  @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test void migrationKeepsHistoricalReportsUnavailableAndPreservesExistingData() throws SQLException {
    String schema = "report_availability_" + UUID.randomUUID().toString().replace("-", "");
    var configuration = Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .locations("classpath:db/migration")
        .schemas(schema)
        .defaultSchema(schema);
    Flyway versionOne = configuration.target("1").load();
    versionOne.migrate();
    assertEquals("1", versionOne.info().current().getVersion().getVersion());

    try (Connection connection = DriverManager.getConnection(
        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
      connection.setSchema(schema);
      try (var statement = connection.createStatement()) {
        statement.executeUpdate("""
            INSERT INTO sys_user (id, username, password_hash, role, name, major,
                must_change_password, is_active, created_at, updated_at)
            VALUES (101, 'migration_student', 'test-only-hash', 'STUDENT', '迁移测试学生', '计算机',
                false, true, '2026-10-01T01:02:03Z', '2026-10-02T04:05:06Z')
            """);
        statement.executeUpdate("""
            INSERT INTO submission (id, student_id, original_filename, file_size, status,
                storage_dir, problem_count, by_scope, error_message, attempts,
                started_at, finished_at, created_at, updated_at)
            VALUES (201, 101, '历史论文.docx', 4096, 'SUCCESS', 'migration/history', 7,
                '{"正文": 5, "参考文献": 2}'::jsonb, NULL, 2,
                '2026-10-03T01:02:04Z', '2026-10-03T01:03:05Z',
                '2026-10-03T01:02:03Z', '2026-10-03T01:03:05Z')
            """);
      }
      String historicalSubmission = scalar(connection,
          "SELECT to_jsonb(s)::text FROM submission s WHERE id = 201");
      String historicalUser = scalar(connection,
          "SELECT to_jsonb(u)::text FROM sys_user u WHERE id = 101");

      configuration.target("latest").load().migrate();

      assertEquals(historicalSubmission, scalar(connection,
          "SELECT (to_jsonb(s) - 'report_available')::text FROM submission s WHERE id = 201"));
      assertEquals(historicalUser, scalar(connection,
          "SELECT to_jsonb(u)::text FROM sys_user u WHERE id = 101"));
      try (var statement = connection.prepareStatement("""
          SELECT data_type, is_nullable, column_default
          FROM information_schema.columns
          WHERE table_schema = ? AND table_name = 'submission' AND column_name = 'report_available'
          """)) {
        statement.setString(1, schema);
        try (var column = statement.executeQuery()) {
          assertTrue(column.next(), "report_available column must exist after migration");
          assertEquals("boolean", column.getString("data_type"));
          assertEquals("NO", column.getString("is_nullable"));
          assertEquals("false", column.getString("column_default"));
          assertFalse(column.next());
        }
      }
      assertEquals("false", scalar(connection,
          "SELECT report_available::text FROM submission WHERE id = 201"));

      try (var statement = connection.createStatement()) {
        statement.executeUpdate("""
            INSERT INTO submission (id, student_id, original_filename, file_size, status,
                storage_dir, created_at, updated_at)
            VALUES (202, 101, '新论文.docx', 2048, 'PENDING', 'migration/new',
                '2026-10-04T01:02:03Z', '2026-10-04T01:02:03Z')
            """);
      }
      assertEquals("false", scalar(connection,
          "SELECT report_available::text FROM submission WHERE id = 202"));
    }
  }

  private String scalar(Connection connection, String sql) throws SQLException {
    try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
      assertTrue(result.next());
      String value = result.getString(1);
      assertFalse(result.next());
      return value;
    }
  }
}
