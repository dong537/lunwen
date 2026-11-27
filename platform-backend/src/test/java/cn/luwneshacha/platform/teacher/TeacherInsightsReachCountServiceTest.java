package cn.luwneshacha.platform.teacher;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.luwneshacha.platform.account.Role;
import cn.luwneshacha.platform.account.User;
import cn.luwneshacha.platform.account.UserRepository;
import cn.luwneshacha.platform.auth.CurrentUser;
import cn.luwneshacha.platform.common.ApiException;
import cn.luwneshacha.platform.common.ApiResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

/** Public service/JSON mapping and access behavior only; aggregation is tested with real PostgreSQL. */
class TeacherInsightsReachCountServiceTest {
  private final UserRepository users = mock(UserRepository.class);
  private final TeacherInsightsRepository rows = mock(TeacherInsightsRepository.class);
  private final User teacher = new User("teacher42", "hash", Role.TEACHER, "教师", null, null, false);
  private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
  private TeacherInsightsService service;

  @BeforeEach void setup() {
    ReflectionTestUtils.setField(teacher, "id", 42L);
    when(users.findById(42L)).thenReturn(Optional.of(teacher));
    when(users.findAll(org.mockito.ArgumentMatchers.<Specification<User>>any(), any(Pageable.class))).thenReturn(Page.empty());
    when(rows.latestReviewSummary(anyLong(), nullable(Integer.class), anyBoolean(), any(Instant.class)))
        .thenReturn(List.<Object[]>of(new Object[] {3L, 3L, 0L, 1L, 1L, 1L}));
    service = new TeacherInsightsService(new TeacherStatisticsService(users, mock(TeacherStatisticsRepository.class)), rows, users);
  }

  @Test void exposesDatabaseReachCountsAsIntegersIndependentOfHeatmapAndStudentPage() {
    when(rows.topRuleRows(anyLong(), nullable(Integer.class), anyBoolean(), any(Instant.class), any(Pageable.class)))
        .thenReturn(List.of(new Object[] {"BODY-LINE", "正文行距", 17L, 3L},
            new Object[] {"FIG-CAPTION", "图题", 5L, 1L}));
    JsonNode result = response(null, "ALL");
    JsonNode first = result.path("topRules").get(0), second = result.path("topRules").get(1);
    assertTrue(first.has("affectedStudents"), first.toString());
    assertTrue(first.path("affectedStudents").isIntegralNumber(), first.toString());
    assertEquals(3, first.path("affectedStudents").asLong());
    assertEquals(17, first.path("count").asLong());
    assertEquals("BODY-LINE", first.path("ruleId").asText());
    assertEquals("正文行距", first.path("description").asText());
    assertEquals(1, second.path("affectedStudents").asLong());
    assertEquals(5, second.path("count").asLong());
    assertTrue(result.path("heatmap").path("students").isEmpty());
    assertTrue(result.path("students").path("items").isEmpty());
  }

  @ParameterizedTest
  @CsvSource({"2023,30,2023,false,30", "UNSET,90,,true,90", ",ALL,,false,0"})
  void forwardsCurrentTeacherAndExistingGradeDateAndTopTenFilters(String grade, String range,
      Integer year, boolean unset, int days) {
    response(grade, range);
    Instant since = days == 0 ? Instant.EPOCH : LocalDate.now(ZoneId.of("Asia/Shanghai"))
        .minusDays(days - 1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();
    verify(rows).topRuleRows(eq(42L), eq(year), eq(unset), eq(since),
        argThat(limit -> limit.getPageNumber() == 0 && limit.getPageSize() == 10));
  }

  @Test void rejectsForgedTeacherRoleAndRevokedAccountBeforeQueryingStatistics() {
    var current = CurrentUser.from(teacher);
    for (Role role : List.of(Role.STUDENT, Role.ADMIN)) {
      User stored = new User("changedrole", "hash", role, "当前角色", null, null, false);
      ReflectionTestUtils.setField(stored, "id", 42L);
      when(users.findById(42L)).thenReturn(Optional.of(stored));
      assertEquals(403, assertThrows(ApiException.class, () -> service.insights(current, null, "ALL", 1, 20)).status().value());
    }
    when(users.findById(42L)).thenReturn(Optional.of(teacher));
    teacher.setActive(false);
    assertEquals(401, assertThrows(ApiException.class, () -> service.insights(current, null, "ALL", 1, 20)).status().value());
    assertEquals(401, assertThrows(ApiException.class, () -> service.insights(null, null, "ALL", 1, 20)).status().value());
    verifyNoInteractions(rows);
  }

  private JsonNode response(String grade, String range) {
    return json.valueToTree(ApiResponse.ok(service.insights(CurrentUser.from(teacher), grade, range, 1, 20))).path("data");
  }
}
