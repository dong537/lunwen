package cn.luwneshacha.platform.account;

import cn.luwneshacha.platform.auth.PasswordHasher;
import cn.luwneshacha.platform.common.ApiException;
import cn.luwneshacha.platform.common.PageParameters;
import cn.luwneshacha.platform.common.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {
  private final UserRepository users;
  private final PasswordHasher passwords;
  private final Validator validator;
  public UserService(UserRepository users, PasswordHasher passwords, Validator validator) {
    this.users = users; this.passwords = passwords; this.validator = validator;
  }
  @Transactional(readOnly = true)
  public UserView current(long id) { return view(required(id)); }

  @Transactional(readOnly = true)
  public PageResponse<UserView> list(Role role, String query, Boolean active, int page, int size) {
    managedRole(role);
    var result = users.findAll(search(role, query, active), PageParameters.of(page, size, Sort.by("username", "id")));
    return new PageResponse<>(views(result.getContent()), result.getTotalElements(), page, size);
  }

  @Transactional(readOnly = true)
  public List<UserView> teachers(String query) {
    return views(users.findAll(search(Role.TEACHER, query, true), PageRequest.of(0, 100, Sort.by("username", "id"))).getContent());
  }

  @Transactional
  public UserView create(@Valid CreateUser request) {
    validate(request);
    managedRole(request.role());
    String username = request.username();
    if (users.existsByUsername(username)) throw new ApiException(HttpStatus.CONFLICT, "USERNAME_EXISTS", "账号已存在");
    Long teacher = validateProfile(request.role(), request.major(), request.teacherId());
    var user = users.saveAndFlush(new User(username, passwords.encode("123456"), request.role(), request.name().trim(),
        nullable(request.major()), teacher, true));
    return view(user);
  }

  @Transactional
  public UserView update(long id, UpdateUser request) {
    validate(request);
    User user = managed(id);
    Long teacher = validateProfile(user.getRole(), request.major(), request.teacherId());
    user.updateProfile(request.name().trim(), nullable(request.major()), teacher);
    return view(users.saveAndFlush(user));
  }

  @Transactional
  public ActiveResult setActive(long id, boolean active) {
    User user = managed(id);
    user.setActive(active);
    users.saveAndFlush(user);
    long assigned = user.getRole() == Role.TEACHER ? users.countByTeacherId(id) : 0;
    String message = !active && assigned > 0 ? "该教师名下还有 " + assigned + " 名学生，请及时重新分配" : null;
    return new ActiveResult(view(user), assigned, message);
  }

  @Transactional
  public UserView resetPassword(long id) {
    User user = managed(id);
    user.changePassword(passwords.encode("123456"), true);
    return view(users.saveAndFlush(user));
  }

  @Transactional(readOnly = true)
  public void validateNewUser(CreateUser request) {
    validate(request); managedRole(request.role());
    validateProfile(request.role(), request.major(), request.teacherId());
  }

  private Long validateProfile(Role role, String major, String teacherId) {
    if (role != Role.STUDENT && (nullable(major) != null || nullable(teacherId) != null))
      throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PROFILE", "只有学生可以填写专业和指导教师");
    if (nullable(teacherId) == null) return null;
    long id;
    try { id = Long.parseLong(teacherId); }
    catch (NumberFormatException e) { throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TEACHER", "指导教师编号不正确"); }
    User teacher = users.findById(id).orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TEACHER", "指导教师不存在"));
    if (teacher.getRole() != Role.TEACHER || !teacher.isActive())
      throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TEACHER", "请选择已启用的教师账号");
    return teacher.getId();
  }

  private void validate(Object request) {
    var errors = validator.validate(request);
    if (!errors.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", errors.iterator().next().getMessage());
  }
  private User managed(long id) {
    User user = users.findByIdForUpdate(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "账号不存在"));
    managedRole(user.getRole()); return user;
  }
  private User required(long id) {
    return users.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "账号不存在"));
  }
  private static void managedRole(Role role) {
    if (role != Role.STUDENT && role != Role.TEACHER)
      throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ROLE", "只能管理学生和教师账号");
  }
  private static String nullable(String value) { return value == null || value.isBlank() ? null : value.trim(); }
  private UserView view(User user) {
    String teacherName = user.getTeacherId() == null ? null : users.findById(user.getTeacherId()).map(User::getName).orElse(null);
    return UserView.from(user, teacherName);
  }
  private List<UserView> views(List<User> values) {
    var teacherIds = values.stream().map(User::getTeacherId).filter(java.util.Objects::nonNull).distinct().toList();
    Map<Long, User> teachers = users.findAllById(teacherIds).stream().collect(Collectors.toMap(User::getId, Function.identity()));
    return values.stream().map(user -> UserView.from(user,
        teachers.containsKey(user.getTeacherId()) ? teachers.get(user.getTeacherId()).getName() : null)).toList();
  }
  private static Specification<User> search(Role role, String query, Boolean active) {
    if (query != null && query.length() > 100)
      throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_QUERY", "搜索内容不能超过100个字符");
    return (root, criteria, cb) -> {
      var predicates = new java.util.ArrayList<jakarta.persistence.criteria.Predicate>();
      predicates.add(cb.equal(root.get("role"), role));
      if (active != null) predicates.add(cb.equal(root.get("active"), active));
      if (query != null && !query.isBlank()) {
        String pattern = "%" + query.trim().toLowerCase(java.util.Locale.ROOT).replace("\\", "\\\\")
            .replace("%", "\\%").replace("_", "\\_") + "%";
        predicates.add(cb.or(cb.like(cb.lower(root.get("username")), pattern, '\\'),
            cb.like(cb.lower(root.get("name")), pattern, '\\')));
      }
      return cb.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
    };
  }

  public record CreateUser(
      @NotNull(message = "请填写账号") @Pattern(regexp = "[A-Za-z0-9_]{3,32}", message = "账号须为3至32位字母、数字或下划线") String username,
      @NotBlank(message = "请填写姓名") @Size(max = 64, message = "姓名不能超过64个字符") String name,
      @NotNull(message = "请选择角色") Role role,
      @Size(max = 64, message = "专业不能超过64个字符") String major,
      @Pattern(regexp = "[1-9][0-9]*", message = "指导教师编号不正确") String teacherId) {}
  public record UpdateUser(
      @NotBlank(message = "请填写姓名") @Size(max = 64, message = "姓名不能超过64个字符") String name,
      @Size(max = 64, message = "专业不能超过64个字符") String major,
      @Pattern(regexp = "[1-9][0-9]*", message = "指导教师编号不正确") String teacherId) {}
  public record ActiveResult(UserView user, long assignedStudents, String message) {}
}
