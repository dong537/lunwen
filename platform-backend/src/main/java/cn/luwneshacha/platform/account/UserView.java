package cn.luwneshacha.platform.account;

public record UserView(String id, String username, String name, Role role, String major, String teacherId,
    String teacherName, boolean active, boolean mustChangePassword) {
  public static UserView from(User user, String teacherName) {
    return new UserView(user.getId().toString(), user.getUsername(), user.getName(), user.getRole(), user.getMajor(),
        user.getTeacherId() == null ? null : user.getTeacherId().toString(), teacherName, user.isActive(), user.isMustChangePassword());
  }
}
