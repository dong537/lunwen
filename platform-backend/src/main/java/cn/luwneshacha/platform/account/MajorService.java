package cn.luwneshacha.platform.account;

import cn.luwneshacha.platform.auth.CurrentUser;
import cn.luwneshacha.platform.common.ApiException;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MajorService {
  private final MajorCatalogRepository majors;
  private final UserRepository users;
  private final AdminScope scope;
  public MajorService(MajorCatalogRepository majors, UserRepository users, AdminScope scope) {
    this.majors = majors; this.users = users; this.scope = scope;
  }
  @Transactional(readOnly = true)
  public Catalog catalog(CurrentUser current) {
    User actor = scope.actor(current);
    var colleges = actor.getCollegeCode() == null ? College.options() : List.of(
        new College.CollegeOption(actor.getCollegeCode(), College.valueOf(actor.getCollegeCode()).displayName()));
    return new Catalog(College.SCHOOL_NAME, colleges);
  }
  @Transactional(readOnly = true)
  public List<MajorView> list(CurrentUser current, String collegeCode) {
    String college = scope.college(scope.actor(current), collegeCode, true);
    return majors.findByCollegeCodeOrderByNameAsc(college).stream().map(MajorView::from).toList();
  }
  @Transactional(readOnly = true)
  public List<MajorView> teacherMajors(CurrentUser current) {
    if (current == null) throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "请先登录");
    User teacher = users.findById(current.id()).orElseThrow(AdminScope::forbidden);
    if (teacher.getRole() != Role.TEACHER || !teacher.isActive() || teacher.isMustChangePassword()) throw AdminScope.forbidden();
    if (teacher.getCollegeCode() == null) throw new ApiException(HttpStatus.CONFLICT, "COLLEGE_UNASSIGNED", "教师账号尚未分配学院，请联系管理员");
    return majors.findByCollegeCodeAndActiveTrueOrderByNameAsc(teacher.getCollegeCode()).stream().map(MajorView::from).toList();
  }
  @Transactional
  public MajorView create(CurrentUser current, CreateMajor request) {
    String college = scope.college(scope.actorForWrite(current), request.collegeCode(), true);
    String name = request.name().trim();
    if (majors.findByCollegeCodeAndName(college, name).isPresent()) throw duplicate();
    try { return MajorView.from(majors.saveAndFlush(new MajorCatalog(college, name))); }
    catch (DataIntegrityViolationException e) { throw duplicate(); }
  }
  @Transactional
  public MajorView setActive(CurrentUser current, long id, boolean active) {
    User actor = scope.actorForWrite(current);
    MajorCatalog major = majors.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "MAJOR_NOT_FOUND", "专业不存在"));
    scope.college(actor, major.getCollegeCode(), true);
    major.setActive(active);
    return MajorView.from(majors.saveAndFlush(major));
  }
  @Transactional(readOnly = true)
  public void validateActive(String collegeCode, String major) {
    if (major != null && !major.isBlank() && !majors.existsByCollegeCodeAndNameAndActiveTrue(collegeCode, major.trim()))
      throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_MAJOR", "请选择该学院已启用的专业");
  }
  private static ApiException duplicate() { return new ApiException(HttpStatus.CONFLICT, "MAJOR_EXISTS", "该学院已有同名专业"); }
  public record Catalog(String schoolName, List<College.CollegeOption> colleges) {}
  public record MajorView(String id, String collegeCode, String name, boolean active) {
    static MajorView from(MajorCatalog major) { return new MajorView(major.getId().toString(), major.getCollegeCode(), major.getName(), major.isActive()); }
  }
  public record CreateMajor(@NotBlank(message = "请填写学院") String collegeCode,
      @NotBlank(message = "请填写专业") @Size(max = 64, message = "专业不能超过64个字符") String name) {}
  public record ActiveRequest(@NotNull(message = "请选择专业状态") Boolean active) {}
}
