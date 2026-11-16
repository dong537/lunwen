package cn.luwneshacha.platform.teacher;

import cn.luwneshacha.platform.auth.CurrentUser;
import cn.luwneshacha.platform.common.ApiResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import java.nio.charset.StandardCharsets;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/teacher")
public class TeacherStatisticsController {
  private final TeacherStatisticsService service;
  private final TeacherInsightsService insights;
  public TeacherStatisticsController(TeacherStatisticsService service, TeacherInsightsService insights) {
    this.service = service; this.insights = insights;
  }

  @GetMapping("/statistics")
  public ApiResponse<TeacherStatisticsService.StatisticsSummary> statistics(@AuthenticationPrincipal CurrentUser user,
      @RequestParam(required = false) String grade) {
    return ApiResponse.ok(service.statistics(user, grade));
  }

  @GetMapping("/statistics/insights")
  public ApiResponse<TeacherInsightsService.Insights> insights(@AuthenticationPrincipal CurrentUser user,
      @RequestParam(required = false) String grade, @RequestParam(defaultValue = "30") String range,
      @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
    return ApiResponse.ok(insights.insights(user, grade, range, page, size));
  }

  @GetMapping("/statistics/export")
  public ResponseEntity<byte[]> export(@AuthenticationPrincipal CurrentUser user,
      @RequestParam(required = false) String grade, @RequestParam(defaultValue = "30") String range) {
    byte[] workbook = insights.export(user, grade, range);
    return ResponseEntity.ok().contentType(MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
            .filename("论文指导统计.xlsx", StandardCharsets.UTF_8).build().toString())
        .body(workbook);
  }
}
