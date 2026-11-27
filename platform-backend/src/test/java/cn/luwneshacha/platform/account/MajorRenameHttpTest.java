package cn.luwneshacha.platform.account;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cn.luwneshacha.platform.auth.*;
import cn.luwneshacha.platform.common.ApiExceptionHandler;
import cn.luwneshacha.platform.common.RequestIdFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

/** Real MVC/security/service behavior; persistence is isolated. PostgreSQL has separate tests. */
@WebMvcTest(properties = "spring.jackson.deserialization.fail-on-unknown-properties=true")
@ContextConfiguration(classes = {SecurityConfig.class, SecurityResponses.class, ApiExceptionHandler.class,
    RequestIdFilter.class, MajorController.class, MajorRenameHttpTest.Services.class})
class MajorRenameHttpTest {
  private static final String COLLEGE = College.INFORMATION_ENGINEERING.name();
  private static final String OTHER = College.EDUCATION_ARTS.name();
  @TestConfiguration(proxyBeanMethods = false)
  static class Services {
    @Bean AdminScope scope(UserRepository users) { return new AdminScope(users); }
    @Bean MajorService majors(MajorCatalogRepository majors, UserRepository users, AdminScope scope,
        MajorAssignmentLock assignments) {
      return new MajorService(majors, users, scope, assignments);
    }
  }
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @MockitoBean UserRepository users;
  @MockitoBean MajorCatalogRepository majors;
  @MockitoBean AuthService auth;
  @MockitoBean MajorAssignmentLock assignments;
  private final Map<Long, User> accounts = new HashMap<>();
  private final Map<Long, MajorCatalog> catalog = new HashMap<>();
  private User school, administrator;
  private MajorCatalog original;

  @BeforeEach void setup() {
    school = user(1, Role.ADMIN, null);
    administrator = user(2, Role.ADMIN, COLLEGE);
    original = major(10, COLLEGE, "原专业", false);
    when(users.findById(anyLong())).thenAnswer(call -> Optional.ofNullable(accounts.get(call.getArgument(0))));
    when(users.findByIdForUpdate(anyLong())).thenAnswer(call -> Optional.ofNullable(accounts.get(call.getArgument(0))));
    when(majors.findByIdForUpdate(anyLong())).thenAnswer(call -> Optional.ofNullable(catalog.get(call.getArgument(0))));
    when(majors.findByCollegeCodeAndName(anyString(), anyString())).thenAnswer(call -> catalog.values().stream()
        .filter(value -> value.getCollegeCode().equals(call.getArgument(0)) && value.getName().equals(call.getArgument(1)))
        .findFirst());
    when(majors.saveAndFlush(any())).thenAnswer(call -> {
      MajorCatalog value = call.getArgument(0);
      if (value.getId() == null) ReflectionTestUtils.setField(value, "id", 99L);
      return value;
    });
  }

  @Test void collegeAdminRenamesAndPreservesIdentityCollegeAndDisabledStatus() throws Exception {
    rename(administrator, 10, Map.of("name", "  新专业  "))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value("10"))
        .andExpect(jsonPath("$.data.collegeCode").value(COLLEGE))
        .andExpect(jsonPath("$.data.name").value("新专业"))
        .andExpect(jsonPath("$.data.active").value(false));
    assertEquals("新专业", original.getName());
    assertFalse(original.isActive());
  }

  @Test void schoolAdminRenamesAnotherCollegeAndUnchangedNameIsAccepted() throws Exception {
    major(11, OTHER, "其他学院专业", true);
    rename(school, 11, Map.of("name", "其他学院专业"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.active").value(true));
  }

  @Test void disabledDuplicateIsRejectedWithoutChangingOriginal() throws Exception {
    major(11, COLLEGE, "已有专业", false);
    rename(administrator, 10, Map.of("name", " 已有专业 "))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("MAJOR_EXISTS"));
    assertEquals("原专业", original.getName());
  }

  @Test void sameNameInAnotherCollegeDoesNotConflict() throws Exception {
    major(11, OTHER, "其他学院专业", true);
    rename(administrator, 10, Map.of("name", "其他学院专业"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.name").value("其他学院专业"));
  }

  @Test void blankMissingAndOverlongNamesAreRejected() throws Exception {
    for (Map<String, String> body : java.util.List.of(Map.<String, String>of(), Map.of("name", "   "),
        Map.of("name", "专".repeat(65))))
      rename(administrator, 10, body).andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    assertEquals("原专业", original.getName());
  }

  @ParameterizedTest
  @ValueSource(strings = {"\u00A0", "\u202F", "\u2007", " \t\u00A0\u202F\u2007\n", "\u3000\u2003"})
  void renameRejectsNamesContainingOnlyUnicodeWhitespace(String name) throws Exception {
    rename(administrator, 10, Map.of("name", name)).andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    assertEquals("原专业", original.getName());
    verify(majors, never()).saveAndFlush(any());
  }

  @ParameterizedTest
  @ValueSource(strings = {"\u00A0", "\u202F", "\u2007", " \t\u00A0\u202F\u2007\n", "\u3000\u2003"})
  void createRejectsNamesContainingOnlyUnicodeWhitespace(String name) throws Exception {
    mvc.perform(post("/api/admin/majors").with(authentication(session(administrator))).with(csrf())
        .contentType("application/json").content(json.writeValueAsBytes(Map.of("name", name, "collegeCode", COLLEGE))))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    verify(majors, never()).saveAndFlush(any());
  }

  @ParameterizedTest
  @ValueSource(strings = {"计算机 科学", "计算机\u00A0科学", "数据\u202F科学", "数字\u2007媒体"})
  void legitimateInternalSpacesArePreserved(String name) throws Exception {
    mvc.perform(post("/api/admin/majors").with(authentication(session(administrator))).with(csrf())
        .contentType("application/json").content(json.writeValueAsBytes(Map.of("name", name, "collegeCode", COLLEGE))))
        .andExpect(status().isCreated()).andExpect(jsonPath("$.data.name").value(name));
    rename(administrator, 10, Map.of("name", name)).andExpect(status().isOk())
        .andExpect(jsonPath("$.data.name").value(name));
    assertEquals(name, original.getName());
  }

  @Test void nonNameFieldsCannotChangeIdentityCollegeOrActiveState() throws Exception {
    for (Map<String, Object> body : java.util.List.of(Map.<String, Object>of("name", "新专业", "id", "99"),
        Map.<String, Object>of("name", "新专业", "collegeCode", OTHER),
        Map.<String, Object>of("name", "新专业", "active", true)))
      rename(administrator, 10, body).andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    assertEquals("原专业", original.getName());
  }

  @Test void missingAndCrossCollegeTargetsAreRejected() throws Exception {
    rename(administrator, 99, Map.of("name", "新专业"))
        .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("MAJOR_NOT_FOUND"));
    major(11, OTHER, "跨院专业", true);
    rename(administrator, 11, Map.of("name", "新专业"))
        .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("COLLEGE_SCOPE_FORBIDDEN"));
    assertEquals("跨院专业", catalog.get(11L).getName());
  }

  @Test void currentDatabaseCollegeOverridesEarlierSessionAuthority() throws Exception {
    var previous = session(administrator);
    administrator.updateProfile("管理员", null, null, OTHER);
    mvc.perform(patch("/api/admin/majors/10").with(authentication(previous)).with(csrf())
            .contentType("application/json").content("{\"name\":\"新专业\"}"))
        .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("COLLEGE_SCOPE_FORBIDDEN"));
    assertEquals("原专业", original.getName());
  }

  @Test void teachersStudentsUnauthenticatedAndMissingCsrfCannotRename() throws Exception {
    for (Role role : java.util.List.of(Role.TEACHER, Role.STUDENT))
      rename(user(20 + role.ordinal(), role, COLLEGE), 10, Map.of("name", "新专业"))
          .andExpect(status().isForbidden());
    mvc.perform(patch("/api/admin/majors/10").with(csrf()).contentType("application/json")
        .content("{\"name\":\"新专业\"}")).andExpect(status().isUnauthorized());
    mvc.perform(patch("/api/admin/majors/10").with(authentication(session(administrator)))
        .contentType("application/json").content("{\"name\":\"新专业\"}"))
        .andExpect(status().isForbidden());
    assertEquals("原专业", original.getName());
  }

  @Test void disabledAndPasswordRestrictedActorsCannotRename() throws Exception {
    var previous = session(administrator);
    administrator.setActive(false);
    mvc.perform(patch("/api/admin/majors/10").with(authentication(previous)).with(csrf())
        .contentType("application/json").content("{\"name\":\"新专业\"}"))
        .andExpect(status().isUnauthorized());
    administrator.setActive(true);
    administrator.changePassword("hash", true);
    mvc.perform(patch("/api/admin/majors/10").with(authentication(previous)).with(csrf())
        .contentType("application/json").content("{\"name\":\"新专业\"}"))
        .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("PASSWORD_CHANGE_REQUIRED"));
    assertEquals("原专业", original.getName());
  }

  private org.springframework.test.web.servlet.ResultActions rename(User actor, long id, Map<String, ?> body) throws Exception {
    return mvc.perform(patch("/api/admin/majors/" + id).with(authentication(session(actor)))
        .with(csrf()).contentType("application/json").content(json.writeValueAsBytes(body)));
  }
  private static org.springframework.security.authentication.UsernamePasswordAuthenticationToken session(User user) {
    var token = org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(
        CurrentUser.from(user), null, org.springframework.security.core.authority.AuthorityUtils.createAuthorityList(
            "ROLE_" + user.getRole().name()));
    token.setDetails(PasswordHasher.fingerprint(user.getPasswordHash()));
    return token;
  }
  private User user(long id, Role role, String college) {
    var value = new User("account" + id, "hash", role, "姓名", null, null, college, false);
    ReflectionTestUtils.setField(value, "id", id);
    accounts.put(id, value);
    return value;
  }
  private MajorCatalog major(long id, String college, String name, boolean active) {
    var value = new MajorCatalog(college, name);
    ReflectionTestUtils.setField(value, "id", id);
    value.setActive(active);
    catalog.put(id, value);
    return value;
  }
}
