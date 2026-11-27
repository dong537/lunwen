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
import cn.luwneshacha.platform.storage.ObjectPresigner;
import cn.luwneshacha.platform.storage.ObjectStorage;
import cn.luwneshacha.platform.submission.*;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

/** Actual MVC/security/services with persistence and storage boundaries isolated; not a PostgreSQL integration test. */
@WebMvcTest
@ContextConfiguration(classes = {SecurityConfig.class, SecurityResponses.class, ApiExceptionHandler.class,
    RequestIdFilter.class, SubmissionController.class, CollegeAdminHttpSecurityTest.Services.class})
class CollegeAdminHttpSecurityTest {
  @TestConfiguration(proxyBeanMethods = false)
  static class Services {
    @Bean SubmissionService submissions(SubmissionRepository submissions, UserRepository users, ObjectStorage storage,
        PlatformTransactionManager transactions, ObjectPresigner presigner) {
      return new SubmissionService(submissions, users, storage, transactions, presigner,
          new TransferProperties(TransferProperties.Mode.DIRECT, 600));
    }
  }

  @Autowired MockMvc mvc;
  @MockitoBean UserRepository users;
  @MockitoBean SubmissionRepository submissions;
  @MockitoBean ObjectStorage storage;
  @MockitoBean ObjectPresigner presigner;
  @MockitoBean PlatformTransactionManager transactions;
  @MockitoBean AuthService auth;
  private final Map<Long, User> database = new HashMap<>();
  private User administrator, school, student;

  @BeforeEach void setup() throws Exception {
    administrator = user(42, Role.ADMIN, null, College.INFORMATION_ENGINEERING.name());
    school = user(1, Role.ADMIN, null, null);
    student = user(100, Role.STUDENT, 42L, College.INFORMATION_ENGINEERING.name());
    when(users.findById(anyLong())).thenAnswer(call -> Optional.ofNullable(database.get(call.getArgument(0))));
    var item = new Submission(100L, "paper.docx", 1, "submissions/test", Instant.now());
    ReflectionTestUtils.setField(item, "id", 7L);
    when(submissions.findById(7L)).thenReturn(Optional.of(item));
    when(submissions.submissionNumbers(anyList())).thenReturn(List.<Object[]>of(new Object[]{7L, 1L}));
    when(storage.get("submissions/test/original.docx"))
        .thenAnswer(call -> new ObjectStorage.StoredObject(new ByteArrayInputStream(new byte[]{1}), 1));
    when(presigner.downloadUrl(anyString(), anyString(), any()))
        .thenReturn(new ObjectPresigner.DownloadTicket(URI.create("https://storage.example.test/original.docx").toURL(),
            "paper.docx", Instant.now().plusSeconds(600)));
  }

  @Test void collegeAdminLoadsTransferModeDetailsAndBothDownloadPaths() throws Exception {
    mvc.perform(get("/api/submissions/transfer-mode").with(authentication(SessionAuthentication.forUser(administrator))))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.mode").value("direct"));
    mvc.perform(get("/api/submissions/7").with(authentication(SessionAuthentication.forUser(administrator))))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value("7"));
    mvc.perform(get("/api/submissions/7/files/original/url").with(authentication(SessionAuthentication.forUser(administrator))))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.filename").value("paper.docx"));
    var result = mvc.perform(get("/api/submissions/7/files/original")
            .with(authentication(SessionAuthentication.forUser(administrator))))
        .andExpect(request().asyncStarted()).andReturn();
    result.getAsyncResult(2000);
    mvc.perform(asyncDispatch(result)).andExpect(status().isOk()).andExpect(content().bytes(new byte[]{1}));
  }

  @Test void schoolAdminCannotReadTransferModeDetailsOrFiles() throws Exception {
    for (String path : List.of("/api/submissions/transfer-mode", "/api/submissions/7",
        "/api/submissions/7/files/original", "/api/submissions/7/files/original/url"))
      mvc.perform(get(path).with(authentication(SessionAuthentication.forUser(school)))).andExpect(status().isForbidden());
    verifyNoInteractions(storage, presigner);
  }

  @Test void collegeAdminCannotAccessStatisticsUploadOrAnotherSupervisorsPaper() throws Exception {
    for (String path : List.of("/api/teacher/statistics", "/api/teacher/statistics/insights"))
      mvc.perform(get(path).with(authentication(SessionAuthentication.forUser(administrator)))).andExpect(status().isForbidden());
    for (String path : List.of("/api/submissions", "/api/submissions/uploads", "/api/submissions/7/upload-complete"))
      mvc.perform(post(path).with(authentication(SessionAuthentication.forUser(administrator))).with(csrf()))
          .andExpect(status().isForbidden());
    student.updateProfile("学生", null, 99L, College.INFORMATION_ENGINEERING.name());
    mvc.perform(get("/api/submissions/7").with(authentication(SessionAuthentication.forUser(administrator))))
        .andExpect(status().isForbidden());
    student.updateProfile("学生", null, 42L, College.EDUCATION_ARTS.name());
    mvc.perform(get("/api/submissions/7/files/original/url").with(authentication(SessionAuthentication.forUser(administrator))))
        .andExpect(status().isForbidden());
    verifyNoInteractions(storage, presigner);
  }

  @Test void filterRechecksCurrentPasswordAndAccountStatusBeforeServingContent() throws Exception {
    var earlier = SessionAuthentication.forUser(administrator);
    administrator.changePassword("hash", true);
    mvc.perform(get("/api/submissions/7").with(authentication(earlier)))
        .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("PASSWORD_CHANGE_REQUIRED"));
    administrator.changePassword("hash", false);
    administrator.setActive(false);
    mvc.perform(get("/api/submissions/7").with(authentication(earlier)))
        .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("ACCOUNT_DISABLED"));
    verifyNoInteractions(storage, presigner);
  }

  private User user(long id, Role role, Long teacherId, String college) {
    var value = new User("account" + id, "hash", role, "姓名", null, teacherId, college, false);
    ReflectionTestUtils.setField(value, "id", id);
    database.put(id, value);
    return value;
  }
}
