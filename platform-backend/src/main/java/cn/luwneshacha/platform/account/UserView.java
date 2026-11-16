package cn.luwneshacha.platform.account;

public record UserView(String id, String username, String name, Role role, String major, Integer grade, String teacherId,
    String teacherName, boolean active, boolean mustChangePassword, String collegeCode, String collegeName) {
  public static UserView from(User user, String teacherName) {
    return new UserView(user.getId().toString(), user.getUsername(), user.getName(), user.getRole(), user.getMajor(), user.getGrade(),
        user.getTeacherId() == null ? null : user.getTeacherId().toString(), teacherName, user.isActive(), user.isMustChangePassword(),
        user.getCollegeCode(), user.getCollegeCode() == null ? null : College.valueOf(user.getCollegeCode()).displayName());
  }
}
