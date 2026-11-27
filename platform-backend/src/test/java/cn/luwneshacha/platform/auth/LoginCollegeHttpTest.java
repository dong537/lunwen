package cn.luwneshacha.platform.auth;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cn.luwneshacha.platform.account.*;
import cn.luwneshacha.platform.common.ApiExceptionHandler;
import cn.luwneshacha.platform.common.RequestIdFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Real HTTP/security/password and auth policy; repository/view boundaries isolated, not a PostgreSQL integration test. */
@WebMvcTest
@ContextConfiguration(classes = {AuthController.class, SecurityConfig.class, SecurityResponses.class,
    ApiExceptionHandler.class, RequestIdFilter.class, LoginCollegeHttpTest.Services.class})
class LoginCollegeHttpTest {
  private static final String PASSWORD = "LoginPassword42";
  private static final String COLLEGE = College.INFORMATION_ENGINEERING.name();
  @TestConfiguration(proxyBeanMethods = false)
  static class Services {
    @Bean PasswordHasher passwords() { return new PasswordHasher(); }
    @Bean AuthService auth(UserRepository users, PasswordHasher passwords) { return new AuthService(users, passwords); }
  }
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired PasswordHasher passwords;
  @MockitoBean UserRepository users;
  @MockitoBean UserService profiles;
  private User account;

  @BeforeEach void setup() {
    account = account(Role.TEACHER, COLLEGE);
    when(users.findByUsername("account42")).thenAnswer(call -> Optional.of(account));
    when(users.findById(42L)).thenAnswer(call -> Optional.of(account));
    when(profiles.current(42L)).thenAnswer(call -> UserView.from(account, null));
  }

  @Test void anonymousDirectoryContainsOnlyTheAuthoritativeCollegeOptions() throws Exception {
    var result = mvc.perform(get("/api/auth/colleges"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.error").isEmpty()).andReturn();
    assertEquals(json.valueToTree(College.options()), json.readTree(result.getResponse().getContentAsString()).get("data"));
    verifyNoInteractions(users, profiles);
    mvc.perform(get("/api/admin/catalog")).andExpect(status().isUnauthorized());
  }

  @Test void directoryContainsFullRequestedSchoolCollegeList() throws Exception {
    var result = mvc.perform(get("/api/auth/colleges")).andExpect(status().isOk()).andReturn();
    var names = new java.util.HashSet<String>();
    json.readTree(result.getResponse().getContentAsString()).path("data")
        .forEach(option -> names.add(option.path("name").asText()));
    assertEquals(10, names.size());
    assertTrue(names.contains("教育与艺术学院"), "原学院按用户确认保留");
    assertTrue(names.containsAll(java.util.List.of("石油工程学院", "地球科学与工程学院", "化学工程学院",
        "智能制造与控制工程学院", "能源与电气工程学院", "信息科学与工程学院", "经济管理学院",
        "文学院", "生物医药与护理学院")), "公开目录须包含用户提供的完整9学院名称");
  }

  @ParameterizedTest @EnumSource(College.class)
  void everyPublishedCollegeCanBeUsedForMatchingAccountLogin(College college) throws Exception {
    account = account(Role.TEACHER, college.name());
    login(new MockHttpSession(), PASSWORD, college.name(), true).andExpect(status().isOk())
        .andExpect(jsonPath("$.data.collegeCode").value(college.name()))
        .andExpect(jsonPath("$.data.collegeName").value(college.displayName()));
  }

  @Test void expiredSessionCanStillLoadPublicDirectoryAndCannotReadProtectedProfile() throws Exception {
    var previous = SessionAuthentication.forUser(account);
    account.changePassword(passwords.encode("ChangedPassword42"), false);
    var session = new MockHttpSession();
    mvc.perform(get("/api/auth/colleges").session(session).with(authentication(previous)))
        .andExpect(status().isOk());
    assertTrue(session.isInvalid());
    mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
  }

  @Test void mandatoryPasswordChangeDoesNotBlockDirectoryOrUnlockBusinessApis() throws Exception {
    account.changePassword(account.getPasswordHash(), true);
    var authentication = SessionAuthentication.forUser(account);
    mvc.perform(get("/api/auth/colleges").with(authentication(authentication))).andExpect(status().isOk());
    mvc.perform(get("/api/teacher/students").with(authentication(authentication)))
        .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("PASSWORD_CHANGE_REQUIRED"));
    mvc.perform(post("/api/auth/colleges").with(authentication(authentication)).with(csrf()))
        .andExpect(status().isForbidden());
  }

  @ParameterizedTest @EnumSource(Role.class)
  void matchingCollegeEstablishesSessionWithTheDatabaseRole(Role role) throws Exception {
    account = account(role, COLLEGE);
    var session = new MockHttpSession();
    login(session, PASSWORD, COLLEGE, true).andExpect(status().isOk())
        .andExpect(jsonPath("$.data.role").value(role.name()))
        .andExpect(jsonPath("$.data.collegeCode").value(COLLEGE));
    assertNotNull(session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY));
    mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk())
        .andExpect(jsonPath("$.data.collegeCode").value(COLLEGE));
  }

  @Test void assignedAccountMustSelectCollegeAndCannotCreateSessionByOmittingIt() throws Exception {
    for (String selection : new String[]{null, "", " "}) {
      var session = new MockHttpSession();
      login(session, PASSWORD, selection, true).andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.error.code").value("COLLEGE_REQUIRED"));
      assertUnauthenticated(session);
    }
    var session = new MockHttpSession();
    login(session, PASSWORD, null, false).andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("COLLEGE_REQUIRED"));
    assertUnauthenticated(session);
  }

  @Test void mismatchedCollegeCannotEstablishSessionAndCanBeCorrected() throws Exception {
    var session = new MockHttpSession();
    login(session, PASSWORD, College.EDUCATION_ARTS.name(), true).andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("LOGIN_COLLEGE_MISMATCH"));
    assertUnauthenticated(session);
    login(session, PASSWORD, COLLEGE, true).andExpect(status().isOk());
  }

  @Test void invalidCodeIsRejectedAfterCredentialsAreVerified() throws Exception {
    var session = new MockHttpSession();
    login(session, PASSWORD, "OTHER_COLLEGE", true).andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_COLLEGE"));
    assertUnauthenticated(session);
  }

  @Test void wrongPasswordRetainsCredentialsErrorRegardlessOfCollege() throws Exception {
    var session = new MockHttpSession();
    login(session, "wrong-password", "OTHER_COLLEGE", true).andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("BAD_CREDENTIALS"));
    assertUnauthenticated(session);
    verifyNoInteractions(profiles);
  }

  @Test void disabledAccountRemainsDisabledDespiteMatchingCollege() throws Exception {
    account.setActive(false);
    var session = new MockHttpSession();
    login(session, PASSWORD, COLLEGE, true).andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("ACCOUNT_DISABLED"));
    assertUnauthenticated(session);
  }

  @ParameterizedTest @EnumSource(Role.class)
  void schoolAdminAndLegacyUnassignedAccountsCanLeaveCollegeEmpty(Role role) throws Exception {
    account = account(role, null);
    for (String selection : new String[]{null, ""}) {
      var session = new MockHttpSession();
      login(session, PASSWORD, selection, true).andExpect(status().isOk())
          .andExpect(jsonPath("$.data.collegeCode").isEmpty());
    }
  }

  @Test void unassignedAccountCannotAdoptACollegeThroughLoginSelection() throws Exception {
    account = account(Role.ADMIN, null);
    var session = new MockHttpSession();
    login(session, PASSWORD, COLLEGE, true).andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("LOGIN_COLLEGE_MISMATCH"));
    assertNull(account.getCollegeCode());
    assertUnauthenticated(session);
  }

  @Test void rejectedAccountSwitchPreservesExistingAdminSessionAndSessionId() throws Exception {
    account = account(Role.ADMIN, null);
    var session = new MockHttpSession();
    login(session, PASSWORD, null, true).andExpect(status().isOk());
    String originalSessionId = session.getId();
    var student = new User("student99", passwords.encode(PASSWORD), Role.STUDENT, "其他账号",
        null, null, COLLEGE, false);
    ReflectionTestUtils.setField(student, "id", 99L);
    when(users.findByUsername("student99")).thenReturn(Optional.of(student));
    when(profiles.current(99L)).thenReturn(UserView.from(student, null));
    mvc.perform(post("/api/auth/login").session(session).with(csrf()).contentType("application/json")
        .content(json.writeValueAsString(Map.of("username", "student99", "password", PASSWORD,
            "collegeCode", College.EDUCATION_ARTS.name()))))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("LOGIN_COLLEGE_MISMATCH"));
    assertEquals(originalSessionId, session.getId());
    mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk())
        .andExpect(jsonPath("$.data.username").value("account42"))
        .andExpect(jsonPath("$.data.role").value("ADMIN")).andExpect(jsonPath("$.data.collegeCode").isEmpty());
  }

  @Test void loginStillRequiresCsrf() throws Exception {
    mvc.perform(post("/api/auth/login").contentType("application/json")
            .content(json.writeValueAsString(Map.of("username", "account42", "password", PASSWORD, "collegeCode", COLLEGE))))
        .andExpect(status().isForbidden());
    verifyNoInteractions(users, profiles);
  }

  private ResultActions login(MockHttpSession session, String password, String college, boolean includeCollege) throws Exception {
    var body = new LinkedHashMap<String, Object>();
    body.put("username", "account42");
    body.put("password", password);
    if (includeCollege) body.put("collegeCode", college);
    return mvc.perform(post("/api/auth/login").session(session).with(csrf())
        .contentType("application/json").content(json.writeValueAsString(body)));
  }

  private void assertUnauthenticated(MockHttpSession session) throws Exception {
    assertNull(session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY));
    mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isUnauthorized());
  }

  private User account(Role role, String college) {
    var value = new User("account42", passwords.encode(PASSWORD), role, "测试账号", null, null, college, false);
    ReflectionTestUtils.setField(value, "id", 42L);
    return value;
  }
}
