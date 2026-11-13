package cn.luwneshacha.platform.teacher;

import cn.luwneshacha.platform.auth.CurrentUser;
import cn.luwneshacha.platform.common.ApiResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/teacher")
public class TeacherStatisticsController {
  private final TeacherStatisticsService service;
  public TeacherStatisticsController(TeacherStatisticsService service) { this.service = service; }

  @GetMapping("/statistics")
  public ApiResponse<TeacherStatisticsService.StatisticsSummary> statistics(@AuthenticationPrincipal CurrentUser user) {
    return ApiResponse.ok(service.statistics(user));
  }
}
