package cn.luwneshacha.platform.account;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.luwneshacha.platform.auth.CurrentUser;
import cn.luwneshacha.platform.common.ApiException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** Service results with an isolated persistence boundary; real JPQL/atomicity/locks have PostgreSQL tests. */
class MajorAccountSyncServiceTest {
  private static final String COLLEGE = College.INFORMATION_ENGINEERING.name();
  private final List<User> accounts = new ArrayList<>();
  private final User actor = user(1, Role.ADMIN, COLLEGE, null);
  private final MajorCatalog original = new MajorCatalog(COLLEGE, "原专业");
  private final MajorCatalogRepository majors = mock(MajorCatalogRepository.class);
  private final UserRepository users = mock(UserRepository.class);
  private MajorService service;

  @BeforeEach void setup() {
    when(users.renameAssignedMajor(anyString(), anyString(), anyString(), any(Instant.class))).thenAnswer(call -> {
      String college = call.getArgument(0), oldName = call.getArgument(1), newName = call.getArgument(2);
      Instant updatedAt = call.getArgument(3);
      int updated = 0;
      for (User user : accounts) {
        if (!college.equals(user.getCollegeCode()) || !oldName.equals(user.getMajor())
            || (user.getRole() != Role.STUDENT && user.getRole() != Role.TEACHER)) continue;
        user.updateProfile(user.getName(), newName, user.getTeacherId(), user.getCollegeCode(), user.getGrade());
        ReflectionTestUtils.setField(user, "updatedAt", updatedAt);
        updated++;
      }
      return updated;
    });
    ReflectionTestUtils.setField(original, "id", 10L);
    when(users.findByIdForUpdate(1L)).thenReturn(Optional.of(actor));
    when(majors.findByIdForUpdate(10L)).thenReturn(Optional.of(original));
    when(majors.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
    service = new MajorService(majors, users, new AdminScope(users), mock(MajorAssignmentLock.class));
  }

  @Test void renamedDirectoryPropagatesOnlyToSameCollegeMatchingStudentAndTeacherProfiles() {
    User student = user(2, Role.STUDENT, COLLEGE, "原专业");
    User teacher = user(3, Role.TEACHER, COLLEGE, "原专业");
    User inactive = user(4, Role.STUDENT, COLLEGE, "原专业");
    inactive.setActive(false);
    User foreign = user(5, Role.STUDENT, College.EDUCATION_ARTS.name(), "原专业");
    User different = user(6, Role.TEACHER, COLLEGE, "历史专业");
    User administrator = user(7, Role.ADMIN, COLLEGE, "原专业");
    var before = Instant.parse("2020-01-01T00:00:00Z");
    var result = service.rename(CurrentUser.from(actor), 10, new MajorService.RenameMajor(" 新专业 "));
    assertEquals("新专业", result.name());
    for (User updated : List.of(student, teacher, inactive)) {
      assertEquals("新专业", updated.getMajor());
      assertTrue(updated.getUpdatedAt().isAfter(before));
      assertEquals("name" + updated.getId(), updated.getName());
      assertEquals("hash", updated.getPasswordHash());
    }
    assertFalse(inactive.isActive());
    for (User excluded : List.of(foreign, different, administrator)) {
      assertEquals(excluded == different ? "历史专业" : "原专业", excluded.getMajor());
      assertEquals(before, excluded.getUpdatedAt());
    }
  }

  @Test void identicalNameDoesNotTouchExistingProfileTimestamps() {
    User student = user(2, Role.STUDENT, COLLEGE, "原专业");
    Instant before = student.getUpdatedAt();
    assertEquals("原专业", service.rename(CurrentUser.from(actor), 10, new MajorService.RenameMajor(" 原专业 ")).name());
    assertEquals("原专业", student.getMajor());
    assertEquals(before, student.getUpdatedAt());
  }

  @Test void duplicateAndOutOfScopeFailuresLeaveAccountProfilesUnchanged() {
    User student = user(2, Role.STUDENT, COLLEGE, "原专业");
    when(majors.findByCollegeCodeAndName(COLLEGE, "已有专业"))
        .thenReturn(Optional.of(new MajorCatalog(COLLEGE, "已有专业")));
    assertEquals("MAJOR_EXISTS", assertThrows(ApiException.class,
        () -> service.rename(CurrentUser.from(actor), 10, new MajorService.RenameMajor("已有专业"))).code());
    actor.updateProfile("院管", null, null, College.EDUCATION_ARTS.name());
    assertEquals(403, assertThrows(ApiException.class,
        () -> service.rename(CurrentUser.from(actor), 10, new MajorService.RenameMajor("新专业"))).status().value());
    assertEquals("原专业", original.getName());
    assertEquals("原专业", student.getMajor());
    assertEquals(Instant.parse("2020-01-01T00:00:00Z"), student.getUpdatedAt());
  }

  @Test void accountPersistenceFailurePropagatesInsteadOfReturningSuccess() {
    var failure = new org.springframework.dao.DataAccessResourceFailureException("Unavailable persistence");
    when(users.renameAssignedMajor(anyString(), anyString(), anyString(), any(Instant.class))).thenThrow(failure);
    assertSame(failure, assertThrows(org.springframework.dao.DataAccessResourceFailureException.class,
        () -> service.rename(CurrentUser.from(actor), 10, new MajorService.RenameMajor("新专业"))));
  }

  private User user(long id, Role role, String college, String major) {
    User user = new User("account" + id, "hash", role, "name" + id, major, null, college, false);
    ReflectionTestUtils.setField(user, "id", id);
    ReflectionTestUtils.setField(user, "updatedAt", Instant.parse("2020-01-01T00:00:00Z"));
    accounts.add(user);
    return user;
  }
}
