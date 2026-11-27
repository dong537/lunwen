package cn.luwneshacha.platform.submission;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.luwneshacha.platform.account.*;
import cn.luwneshacha.platform.auth.CurrentUser;
import cn.luwneshacha.platform.common.ApiException;
import cn.luwneshacha.platform.storage.ObjectPresigner;
import cn.luwneshacha.platform.storage.ObjectStorage;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

class CollegeAdminSubmissionAccessTest {
  private static final String COLLEGE = College.INFORMATION_ENGINEERING.name();
  private final UserRepository users = mock(UserRepository.class);
  private final SubmissionRepository submissions = mock(SubmissionRepository.class);
  private final ObjectStorage storage = mock(ObjectStorage.class);
  private final User admin = user(42, Role.ADMIN, null, COLLEGE);
  private final User student = user(100, Role.STUDENT, 42L, COLLEGE);
  private SubmissionService service;

  @BeforeEach void setup() {
    when(users.findById(42L)).thenReturn(Optional.of(admin));
    when(users.findById(100L)).thenReturn(Optional.of(student));
    var item = new Submission(100L, "paper.docx", 1, "submissions/test", Instant.now());
    ReflectionTestUtils.setField(item, "id", 7L);
    when(submissions.findById(7L)).thenReturn(Optional.of(item));
    when(submissions.submissionNumbers(anyList())).thenReturn(List.<Object[]>of(new Object[]{7L, 1L}));
    when(submissions.findByStudentIdOrderByCreatedAtDescIdDesc(eq(100L), any())).thenReturn(Page.empty());
    service = new SubmissionService(submissions, users, storage, mock(PlatformTransactionManager.class),
        mock(ObjectPresigner.class), new TransferProperties(TransferProperties.Mode.DIRECT, 600));
  }

  @Test void collegeAdminReadsOnlyOwnSameCollegeStudentAndHistory() {
    assertEquals("100", service.student(actor(), 100L).id());
    assertEquals("7", service.detail(actor(), 7L).id());
    assertEquals(0, service.studentSubmissions(actor(), 100L, 1, 20).total());
    student.updateProfile("学生", null, 99L, COLLEGE);
    assertEquals(403, assertThrows(ApiException.class, () -> service.student(actor(), 100L)).status().value());
    assertThrows(ApiException.class, () -> service.detail(actor(), 7L));
    student.updateProfile("学生", null, 42L, College.EDUCATION_ARTS.name());
    assertThrows(ApiException.class, () -> service.studentSubmissions(actor(), 100L, 1, 20));
    assertThrows(ApiException.class, () -> service.detail(actor(), 7L));
  }

  @Test void downloadRechecksCollegeAndSupervisionAfterPlanCreation() throws Exception {
    when(storage.get("submissions/test/original.docx"))
        .thenReturn(new ObjectStorage.StoredObject(new ByteArrayInputStream(new byte[]{1}), 1));
    var plan = service.prepareDownload(actor(), 7L, "original");
    try (var result = service.openDownload(plan)) { assertEquals(1, result.size()); }
    student.updateProfile("学生", null, 99L, COLLEGE);
    assertThrows(ApiException.class, () -> service.openDownload(plan));
    student.updateProfile("学生", null, 42L, College.EDUCATION_ARTS.name());
    assertThrows(ApiException.class, () -> service.openDownload(plan));
    verify(storage, times(1)).get(anyString());
  }

  @Test void schoolAdminOrRemovedCollegeCannotUseSupervisionEvenWithOldSession() {
    admin.updateProfile("管理员", null, null, null);
    assertThrows(ApiException.class, () -> service.student(actor(), 100L));
    assertThrows(ApiException.class, () -> service.detail(actor(), 7L));
    assertThrows(ApiException.class, () -> service.students(actor(), "", 1, 20));
  }

  @Test void transferModeUsesCurrentDatabaseQualificationWithoutExpandingSchoolAdminAccess() {
    assertEquals("direct", service.transferMode(actor()));
    assertEquals("direct", service.transferMode(CurrentUser.from(student)));
    User teacher = user(99, Role.TEACHER, null, null);
    when(users.findById(99L)).thenReturn(Optional.of(teacher));
    assertEquals("direct", service.transferMode(CurrentUser.from(teacher)));
    var earlier = actor();
    admin.updateProfile("管理员", null, null, null);
    assertThrows(ApiException.class, () -> service.transferMode(earlier));
    admin.updateProfile("院管", null, null, COLLEGE);
    admin.changePassword("hash", true);
    assertThrows(ApiException.class, () -> service.transferMode(earlier));
    admin.changePassword("hash", false);
    admin.setActive(false);
    assertThrows(ApiException.class, () -> service.transferMode(earlier));
    assertEquals(401, assertThrows(ApiException.class, () -> service.transferMode(null)).status().value());
  }

  private CurrentUser actor() { return new CurrentUser(42L, "admin42", Role.ADMIN, "院管", false); }
  private static User user(long id, Role role, Long teacher, String college) {
    var user = new User("account" + id, "hash", role, "姓名", null, teacher, college, false);
    ReflectionTestUtils.setField(user, "id", id);
    return user;
  }
}
