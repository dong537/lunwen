package cn.luwneshacha.platform.account;

import cn.luwneshacha.platform.auth.PasswordHasher;
import cn.luwneshacha.platform.auth.CurrentUser;
import cn.luwneshacha.platform.common.ApiException;
import cn.luwneshacha.platform.common.PageParameters;
import cn.luwneshacha.platform.common.PageResponse;
import cn.luwneshacha.platform.submission.SubmissionRepository;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
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
  private final AdminScope scope;
  private final MajorService majors;
  private final SubmissionRepository submissions;
  public UserService(UserRepository users, PasswordHasher passwords, Validator validator, AdminScope scope,
      MajorService majors, SubmissionRepository submissions) {
    this.users = users; this.passwords = passwords; this.validator = validator;
    this.scope = scope; this.majors = majors; this.submissions = submissions;
  }
  @Transactional(readOnly = true)
  public UserView current(long id) { return view(required(id)); }

  @Transactional(readOnly = true)
  public PageResponse<UserView> list(CurrentUser current, Role role, String query, Boolean active, String collegeCode,
      String grade, int page, int size) {
    User actor = scope.actor(current);
    if (role == Role.ADMIN) scope.school(actor);
    GradeFilter gradeFilter = parseGradeFilter(role, grade);
    String college = scope.college(actor, collegeCode, false);
    var result = users.findAll(search(List.of(role), query, active, college, gradeFilter), PageParameters.of(page, size, Sort.by("username", "id")));
    return new PageResponse<>(views(result.getContent()), result.getTotalElements(), page, size);
  }

  @Transactional(readOnly = true)
  public PageResponse<UserView> teachers(CurrentUser current, String query, String collegeCode, int page, int size) {
    College.fromCode(collegeCode);
    String college = scope.college(scope.actor(current), collegeCode, true);
    var result = users.findAll(search(List.of(Role.TEACHER, Role.ADMIN), query, true, college, null),
        PageParameters.of(page, size, Sort.by("username", "id")));
    return new PageResponse<>(views(result.getContent()), result.getTotalElements(), page, size);
  }

  @Transactional
  public UserView create(CurrentUser current, @Valid CreateUser request) {
    User actor = scope.actorForWrite(current);
    if (request.role() == Role.ADMIN) scope.school(actor);
    String college = scope.college(actor, request.collegeCode(), true);
    return createAccount(request, college);
  }

  private UserView createAccount(CreateUser request, String college) {
    validate(request);
    String username = request.username();
    if (users.existsByUsername(username)) throw new ApiException(HttpStatus.CONFLICT, "USERNAME_EXISTS", "账号已存在");
    Long teacher = validateProfile(request.role(), request.major(), request.teacherId(), request.grade(), college, null, true);
    var user = new User(username, passwords.encode("123456"), request.role(), request.name().trim(),
        nullable(request.major()), teacher, college, request.role() != Role.STUDENT, request.grade());
    users.saveAndFlush(user);
    return view(user);
  }

  @Transactional
  public UserView createStudent(CurrentUser current, CreateStudent request) {
    User teacher = requireTeacher(current, true);
    validate(request);
    return createAccount(studentRequest(teacher, request), teacherCollege(teacher));
  }

  @Transactional(readOnly = true)
  public void validateStudent(CurrentUser current, CreateStudent request) {
    User teacher = requireTeacher(current, false);
    validate(request);
    validateNewUser(studentRequest(teacher, request), teacherCollege(teacher));
  }

  @Transactional(readOnly = true)
  public void validateTeacher(CurrentUser current) { requireTeacher(current, false); }

  @Transactional(readOnly = true)
  public String teacherCollegeCode(CurrentUser current) { return teacherCollege(requireTeacher(current, false)); }

  private User requireTeacher(CurrentUser current, boolean lock) {
    if (current == null) throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "请先登录");
    User teacher = (lock ? users.findByIdForUpdate(current.id()) : users.findById(current.id()))
        .orElseThrow(UserService::teacherForbidden);
    if (!SupervisionScope.isSupervisor(teacher) || !teacher.isActive() || teacher.isMustChangePassword()) throw teacherForbidden();
    return teacher;
  }
  private static ApiException teacherForbidden() {
    return new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "无权管理该学生");
  }
  private static CreateUser studentRequest(User teacher, CreateStudent request) {
    return new CreateUser(request.username(), request.name(), Role.STUDENT, request.major(), teacher.getId().toString(),
        teacherCollege(teacher), request.grade());
  }
  private static String teacherCollege(User teacher) {
    if (teacher.getCollegeCode() == null)
      throw new ApiException(HttpStatus.CONFLICT, "COLLEGE_UNASSIGNED", "教师账号尚未分配学院，请联系管理员");
    return teacher.getCollegeCode();
  }

  @Transactional
  public UserView update(CurrentUser current, long id, UpdateUser request) {
    validate(request);
    User actor = scope.actorForWrite(current);
    User user = managed(actor, id);
    String college = scope.college(actor, request.collegeCode(), true);
    if (SupervisionScope.isSupervisor(user) && user.getCollegeCode() != null
        && !college.equals(user.getCollegeCode()) && users.countByTeacherId(id) > 0)
      throw new ApiException(HttpStatus.CONFLICT, "TEACHER_HAS_STUDENTS", "请先重新分配该教师名下学生，再修改教师学院");
    Integer grade = user.getRole() == Role.STUDENT && request.grade() == null ? user.getGrade() : request.grade();
    Long teacher = validateProfile(user.getRole(), request.major(), request.teacherId(), grade, college, user, true);
    user.updateProfile(request.name().trim(), nullable(request.major()), teacher, college, grade);
    return view(users.saveAndFlush(user));
  }

  @Transactional
  public ActiveResult setActive(CurrentUser current, long id, boolean active) {
    User user = managed(scope.actorForWrite(current), id);
    user.setActive(active);
    users.saveAndFlush(user);
    long assigned = SupervisionScope.isSupervisor(user) ? users.countByTeacherId(id) : 0;
    String message = !active && assigned > 0 ? "该教师名下还有 " + assigned + " 名学生，请及时重新分配" : null;
    return new ActiveResult(view(user), assigned, message);
  }

  @Transactional
  public UserView resetPassword(CurrentUser current, long id) {
    User user = managed(scope.actorForWrite(current), id);
    user.changePassword(passwords.encode("123456"), user.getRole() != Role.STUDENT);
    return view(users.saveAndFlush(user));
  }

  @Transactional
  public DeleteResult delete(CurrentUser current, long id) {
    User user = managed(scope.actorForWrite(current), id);
    boolean referenced = SupervisionScope.isSupervisor(user) ? users.countByTeacherId(id) > 0
        : user.getRole() == Role.STUDENT && submissions.existsByStudentId(id);
    if (referenced) {
      user.setActive(false);
      UserView view = view(users.saveAndFlush(user));
      return new DeleteResult("DEACTIVATED", view, "账号存在关联的学生或论文，已停用并保留历史记录");
    }
    users.delete(user);
    users.flush();
    return new DeleteResult("DELETED", null, "账号已永久删除");
  }

  @Transactional(readOnly = true)
  public void validateNewUser(CurrentUser current, CreateUser request) {
    User actor = scope.actor(current);
    if (request.role() == Role.ADMIN) scope.school(actor);
    validateNewUser(request, scope.college(actor, request.collegeCode(), true));
  }
  private void validateNewUser(CreateUser request, String college) {
    validate(request);
    validateProfile(request.role(), request.major(), request.teacherId(), request.grade(), college, null, false);
  }

  private Long validateProfile(Role role, String major, String teacherId, Integer grade, String college, User existing,
      boolean lockTeacher) {
    if ((role == Role.ADMIN && nullable(major) != null) || (role != Role.STUDENT && (nullable(teacherId) != null || grade != null)))
      throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PROFILE", "管理员不能填写专业，只有学生可以填写年级和指导教师");
    if (role == Role.STUDENT && existing == null && grade == null)
      throw new ApiException(HttpStatus.BAD_REQUEST, "GRADE_REQUIRED", "请填写学生入学年级");
    if ((role == Role.STUDENT || role == Role.TEACHER) && (existing == null
        || !java.util.Objects.equals(nullable(major), nullable(existing.getMajor()))
        || (existing.getCollegeCode() != null && !college.equals(existing.getCollegeCode()))))
      majors.validateActive(college, major);
    if (nullable(teacherId) == null) return null;
    long id;
    try { id = Long.parseLong(teacherId); }
    catch (NumberFormatException e) { throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TEACHER", "指导教师编号不正确"); }
    User teacher = (lockTeacher ? users.findByIdForUpdate(id) : users.findById(id))
        .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TEACHER", "指导教师不存在"));
    if (!SupervisionScope.isSupervisor(teacher) || !teacher.isActive() || !college.equals(teacher.getCollegeCode()))
      throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TEACHER", "请选择本学院已启用的指导教师");
    return teacher.getId();
  }

  private void validate(Object request) {
    var errors = validator.validate(request);
    if (!errors.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", errors.iterator().next().getMessage());
  }
  private User managed(User actor, long id) {
    User user = users.findByIdForUpdate(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "账号不存在"));
    scope.target(actor, user);
    return user;
  }
  private User required(long id) {
    return users.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "账号不存在"));
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
  private static GradeFilter parseGradeFilter(Role role, String grade) {
    if (grade == null || grade.isBlank()) return null;
    if (role != Role.STUDENT)
      throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_GRADE", "年级筛选只用于学生账号");
    if ("UNSET".equals(grade)) return new GradeFilter(null, true);
    if (!grade.matches("[0-9]{4}"))
      throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_GRADE", "年级须为2000至2100年的入学年份");
    int year = Integer.parseInt(grade);
    if (year < 2000 || year > 2100)
      throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_GRADE", "年级须为2000至2100年的入学年份");
    return new GradeFilter(year, false);
  }

  private static Specification<User> search(List<Role> roles, String query, Boolean active, String college, GradeFilter grade) {
    if (query != null && query.length() > 100)
      throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_QUERY", "搜索内容不能超过100个字符");
    return (root, criteria, cb) -> {
      var predicates = new java.util.ArrayList<jakarta.persistence.criteria.Predicate>();
      predicates.add(root.get("role").in(roles));
      if (college != null) predicates.add(cb.equal(root.get("collegeCode"), college));
      if (grade != null) predicates.add(grade.unset()
          ? cb.isNull(root.get("grade")) : cb.equal(root.get("grade"), grade.year()));
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
      @Pattern(regexp = "[1-9][0-9]*", message = "指导教师编号不正确") String teacherId,
      String collegeCode,
      @Min(value = 2000, message = "年级须为2000至2100年的入学年份")
      @Max(value = 2100, message = "年级须为2000至2100年的入学年份") Integer grade) {}
  public record UpdateUser(
      @NotBlank(message = "请填写姓名") @Size(max = 64, message = "姓名不能超过64个字符") String name,
      @Size(max = 64, message = "专业不能超过64个字符") String major,
      @Pattern(regexp = "[1-9][0-9]*", message = "指导教师编号不正确") String teacherId,
      String collegeCode,
      @Min(value = 2000, message = "年级须为2000至2100年的入学年份")
      @Max(value = 2100, message = "年级须为2000至2100年的入学年份") Integer grade) {}
  public record ActiveResult(UserView user, long assignedStudents, String message) {}
  public record DeleteResult(String outcome, UserView user, String message) {}
  public record CreateStudent(
      @NotNull(message = "请填写学号") @Pattern(regexp = "[A-Za-z0-9_]{3,32}", message = "学号须为3至32位字母、数字或下划线") String username,
      @NotBlank(message = "请填写姓名") @Size(max = 64, message = "姓名不能超过64个字符") String name,
      @Size(max = 64, message = "专业不能超过64个字符") String major,
      @NotNull(message = "请填写学生入学年级")
      @Min(value = 2000, message = "年级须为2000至2100年的入学年份")
      @Max(value = 2100, message = "年级须为2000至2100年的入学年份") Integer grade) {}
  private record GradeFilter(Integer year, boolean unset) {}
}
