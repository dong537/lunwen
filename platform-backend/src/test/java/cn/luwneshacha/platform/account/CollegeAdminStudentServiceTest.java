package cn.luwneshacha.platform.account;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.luwneshacha.platform.auth.CurrentUser;
import cn.luwneshacha.platform.auth.PasswordHasher;
import cn.luwneshacha.platform.common.ApiException;
import cn.luwneshacha.platform.submission.SubmissionRepository;
import jakarta.validation.Validation;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class CollegeAdminStudentServiceTest {
  private static final String COLLEGE = College.INFORMATION_ENGINEERING.name();
  private final UserRepository users = mock(UserRepository.class);
  private final MajorService majors = mock(MajorService.class);
  private final PasswordHasher passwords = mock(PasswordHasher.class);
  private final SubmissionRepository submissions = mock(SubmissionRepository.class);
  private final User school = user(1, Role.ADMIN, null);
  private final User college = user(42, Role.ADMIN, COLLEGE);
  private UserService service;

  @BeforeEach void setup() {
    when(users.findById(1L)).thenReturn(Optional.of(school));
    when(users.findByIdForUpdate(1L)).thenReturn(Optional.of(school));
    when(users.findById(42L)).thenReturn(Optional.of(college));
    when(users.findByIdForUpdate(42L)).thenReturn(Optional.of(college));
    when(users.saveAndFlush(any(User.class))).thenAnswer(call -> {
      User value = call.getArgument(0);
      if (value.getId() == null) ReflectionTestUtils.setField(value, "id", 100L);
      return value;
    });
    when(passwords.encode(anyString())).thenReturn("hash");
    service = new UserService(users, passwords, Validation.buildDefaultValidatorFactory().getValidator(),
        new AdminScope(users), majors, submissions);
  }

  @Test void collegeAdminCreatesOwnStudentAndSchoolAdminCanAssignTheSameAccount() {
    var request = new UserService.CreateStudent("student01", "学生", null, 2023);
    service.validateStudent(actor(college), request);
    var created = service.createStudent(actor(college), request);
    assertEquals("42", created.teacherId());
    assertEquals(COLLEGE, created.collegeCode());
    assertEquals(Role.STUDENT, created.role());
    var assigned = service.create(actor(school), new UserService.CreateUser("student02", "学生乙",
        Role.STUDENT, null, "42", COLLEGE, 2023));
    assertEquals("42", assigned.teacherId());
  }

  @Test void schoolAdminAndUnavailableCollegeAdminCannotUseStudentCreation() {
    var request = new UserService.CreateStudent("student01", "学生", null, 2023);
    assertEquals(403, assertThrows(ApiException.class, () -> service.createStudent(actor(school), request)).status().value());
    college.setActive(false);
    assertThrows(ApiException.class, () -> service.createStudent(actor(college), request));
    college.setActive(true);
    college.changePassword("hash", true);
    assertThrows(ApiException.class, () -> service.createStudent(actor(college), request));
  }

  @Test void collegeAdminWithStudentsCannotMoveCollegeAndDeletionDeactivatesInstead() {
    when(users.countByTeacherId(42L)).thenReturn(2L);
    var error = assertThrows(ApiException.class, () -> service.update(actor(school), 42L,
        new UserService.UpdateUser("院管", null, null, College.EDUCATION_ARTS.name(), null)));
    assertEquals("TEACHER_HAS_STUDENTS", error.code());
    var active = service.setActive(actor(school), 42L, false);
    assertEquals(2, active.assignedStudents());
    assertNotNull(active.message());
    assertEquals("DEACTIVATED", service.delete(actor(school), 42L).outcome());
    assertFalse(college.isActive());
    verify(users, never()).delete(college);
  }

  @Test void studentCreationRejectsCrossCollegeInactiveAndSchoolAdminSupervisors() {
    for (User invalid : List.of(user(55, Role.ADMIN, College.EDUCATION_ARTS.name()),
        user(55, Role.ADMIN, null), user(55, Role.STUDENT, COLLEGE))) {
      when(users.findByIdForUpdate(55L)).thenReturn(Optional.of(invalid));
      assertEquals("INVALID_TEACHER", assertThrows(ApiException.class, () -> service.create(actor(school),
          new UserService.CreateUser("student01", "学生", Role.STUDENT, null, "55", COLLEGE, 2023))).code());
    }
    User inactive = user(55, Role.ADMIN, COLLEGE);
    inactive.setActive(false);
    when(users.findByIdForUpdate(55L)).thenReturn(Optional.of(inactive));
    assertThrows(ApiException.class, () -> service.create(actor(school),
        new UserService.CreateUser("student01", "学生", Role.STUDENT, null, "55", COLLEGE, 2023)));
  }

  @Test void collegeAdminCanLoadOwnActiveMajorsButSchoolAdminCannot() {
    var repository = mock(MajorCatalogRepository.class);
    var catalog = new MajorService(repository, users, new AdminScope(users));
    when(repository.findByCollegeCodeAndActiveTrueOrderByNameAsc(COLLEGE)).thenReturn(List.of());
    assertEquals(List.of(), catalog.teacherMajors(actor(college)));
    assertThrows(ApiException.class, () -> catalog.teacherMajors(actor(school)));
  }

  private static User user(long id, Role role, String college) {
    var user = new User("account" + id, "hash", role, "姓名", null, null, college, false);
    ReflectionTestUtils.setField(user, "id", id);
    return user;
  }
  private static CurrentUser actor(User user) {
    return new CurrentUser(user.getId(), user.getUsername(), user.getRole(), user.getName(), false);
  }
}
