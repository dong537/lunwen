package cn.luwneshacha.platform.account;

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
class CollegeCatalogMigrationTest {
  @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test void expansionPreservesOldRowsAndAcceptsOnlyPublishedColleges() throws SQLException {
    String schema = "college_catalog_" + UUID.randomUUID().toString().replace("-", "");
    var configuration = Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .locations("classpath:db/migration").schemas(schema).defaultSchema(schema);
    configuration.target("6").load().migrate();
    try (Connection connection = DriverManager.getConnection(
        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
      connection.setSchema(schema);
      insertUser(connection, "old_education", College.EDUCATION_ARTS.name());
      insertUser(connection, "old_unassigned", null);
      insertMajor(connection, College.EDUCATION_ARTS.name(), "原有专业");
      String oldUsers = scalar(connection, "SELECT jsonb_agg(to_jsonb(u) ORDER BY u.id)::text FROM sys_user u");
      String oldMajors = scalar(connection, "SELECT jsonb_agg(to_jsonb(m) ORDER BY m.id)::text FROM major_catalog m");

      var versionSeven = configuration.target("7").load();
      versionSeven.migrate();
      assertEquals("7", versionSeven.info().current().getVersion().getVersion());
      assertEquals(oldUsers, scalar(connection, "SELECT jsonb_agg(to_jsonb(u) ORDER BY u.id)::text FROM sys_user u"));
      assertEquals(oldMajors, scalar(connection, "SELECT jsonb_agg(to_jsonb(m) ORDER BY m.id)::text FROM major_catalog m"));
      for (var college : College.values()) {
        insertUser(connection, "new_college_" + college.ordinal(), college.name());
        insertMajor(connection, college.name(), "新增专业");
      }
      assertEquals(Integer.toString(College.values().length + 2), scalar(connection, "SELECT count(*)::text FROM sys_user"));
      assertEquals(Integer.toString(College.values().length + 1), scalar(connection, "SELECT count(*)::text FROM major_catalog"));
      assertEquals("23514", assertThrows(SQLException.class,
          () -> insertUser(connection, "invalid_college", "UNRECOGNIZED")).getSQLState());
      assertEquals("23514", assertThrows(SQLException.class,
          () -> insertMajor(connection, "UNRECOGNIZED", "无效学院专业")).getSQLState());
      assertEquals("23502", assertThrows(SQLException.class,
          () -> insertMajor(connection, null, "缺学院专业")).getSQLState());
    }
  }

  private void insertUser(Connection connection, String username, String college) throws SQLException {
    try (var statement = connection.prepareStatement("""
        INSERT INTO sys_user (username,password_hash,role,name,college_code,created_at,updated_at)
        VALUES (?, 'test-only-hash', 'TEACHER', '迁移测试教师', ?,
            '2026-11-28T00:00:00Z', '2026-11-28T00:00:00Z')
        """)) {
      statement.setString(1, username);
      statement.setString(2, college);
      statement.executeUpdate();
    }
  }

  private void insertMajor(Connection connection, String college, String name) throws SQLException {
    try (var statement = connection.prepareStatement("INSERT INTO major_catalog(college_code,name) VALUES (?,?)")) {
      statement.setString(1, college);
      statement.setString(2, name);
      statement.executeUpdate();
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
