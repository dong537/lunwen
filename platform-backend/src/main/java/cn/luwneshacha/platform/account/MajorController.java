package cn.luwneshacha.platform.account;

import cn.luwneshacha.platform.auth.CurrentUser;
import cn.luwneshacha.platform.common.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class MajorController {
  private final MajorService majors;
  public MajorController(MajorService majors) { this.majors = majors; }
  @GetMapping("/api/admin/catalog")
  public ApiResponse<MajorService.Catalog> catalog(@AuthenticationPrincipal CurrentUser current) { return ApiResponse.ok(majors.catalog(current)); }
  @GetMapping("/api/admin/majors")
  public ApiResponse<List<MajorService.MajorView>> list(@AuthenticationPrincipal CurrentUser current, @RequestParam String collegeCode) {
    return ApiResponse.ok(majors.list(current, collegeCode));
  }
  @PostMapping("/api/admin/majors") @ResponseStatus(HttpStatus.CREATED)
  public ApiResponse<MajorService.MajorView> create(@AuthenticationPrincipal CurrentUser current,
      @Valid @RequestBody MajorService.CreateMajor request) { return ApiResponse.ok(majors.create(current, request)); }
  @PatchMapping("/api/admin/majors/{id}/active")
  public ApiResponse<MajorService.MajorView> active(@AuthenticationPrincipal CurrentUser current, @PathVariable long id,
      @Valid @RequestBody MajorService.ActiveRequest request) { return ApiResponse.ok(majors.setActive(current, id, request.active())); }
  @GetMapping("/api/teacher/majors")
  public ApiResponse<List<MajorService.MajorView>> teacherMajors(@AuthenticationPrincipal CurrentUser current) {
    return ApiResponse.ok(majors.teacherMajors(current));
  }
}
