package cn.luwneshacha.platform.account;

/** Guidance is an assigned relationship, not the administrator's college-wide management scope. */
public final class SupervisionScope {
  private SupervisionScope() {}

  public static boolean isSupervisor(User user) {
    return user.getRole() == Role.TEACHER
        || user.getRole() == Role.ADMIN && user.getCollegeCode() != null;
  }

  public static boolean ownsStudent(User supervisor, User student) {
    return isSupervisor(supervisor) && student.getRole() == Role.STUDENT
        && supervisor.getId().equals(student.getTeacherId())
        && (supervisor.getRole() != Role.ADMIN
            || supervisor.getCollegeCode().equals(student.getCollegeCode()));
  }
}
