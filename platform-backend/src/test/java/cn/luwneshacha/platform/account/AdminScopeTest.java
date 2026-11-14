package cn.luwneshacha.platform.account;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.luwneshacha.platform.auth.CurrentUser;
import cn.luwneshacha.platform.common.ApiException;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AdminScopeTest {
  private final AdminScope scope = new AdminScope(null);
  private final User schoolAdmin = new User("school_admin", "hash", Role.ADMIN, "校级", null, null, false);
  private final User collegeAdmin = new User("college_admin", "hash", Role.ADMIN, "院级", null, null,
      College.INFORMATION_ENGINEERING.name(), false);

  @Test void collegeAdminUsesOwnCollegeAndCannotRequestAnother() {
    assertEquals(College.INFORMATION_ENGINEERING.name(), scope.college(collegeAdmin, null, true));
    assertEquals(College.INFORMATION_ENGINEERING.name(), scope.college(collegeAdmin, College.INFORMATION_ENGINEERING.name(), true));
    ApiException error = assertThrows(ApiException.class, () -> scope.college(collegeAdmin, College.EDUCATION_ARTS.name(), true));
    assertEquals(403, error.status().value());
    assertEquals(College.EDUCATION_ARTS.name(), scope.college(schoolAdmin, College.EDUCATION_ARTS.name(), true));
  }

  @Test void collegeAdminCannotManageAnotherCollegeOrAdministrator() {
    var ownTeacher = new User("own_teacher", "hash", Role.TEACHER, "教师", null, null,
        College.INFORMATION_ENGINEERING.name(), false);
    var otherTeacher = new User("other_teacher", "hash", Role.TEACHER, "教师", null, null,
        College.EDUCATION_ARTS.name(), false);
    assertDoesNotThrow(() -> scope.target(collegeAdmin, ownTeacher));
    assertThrows(ApiException.class, () -> scope.target(collegeAdmin, otherTeacher));
    assertThrows(ApiException.class, () -> scope.target(collegeAdmin, schoolAdmin));
    assertThrows(ApiException.class, () -> scope.target(schoolAdmin, schoolAdmin));
  }

  @Test void writeScopeUsesLockedCurrentDatabaseAccountInsteadOfSessionSnapshot() {
    UserRepository users = mock(UserRepository.class);
    User databaseActor = new User("college_admin", "hash", Role.ADMIN, "院级", null, null,
        College.EDUCATION_ARTS.name(), false);
    when(users.findByIdForUpdate(7L)).thenReturn(Optional.of(databaseActor));
    CurrentUser staleSession = new CurrentUser(7L, "college_admin", Role.ADMIN, "院级", false);
    assertEquals(College.EDUCATION_ARTS.name(), new AdminScope(users).actorForWrite(staleSession).getCollegeCode());
    databaseActor.setActive(false);
    assertThrows(ApiException.class, () -> new AdminScope(users).actorForWrite(staleSession));
    verify(users, times(2)).findByIdForUpdate(7L);
  }
}
