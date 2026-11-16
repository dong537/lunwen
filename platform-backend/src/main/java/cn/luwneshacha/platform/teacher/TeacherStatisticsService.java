package cn.luwneshacha.platform.teacher;

import cn.luwneshacha.platform.account.Role;
import cn.luwneshacha.platform.account.UserRepository;
import cn.luwneshacha.platform.auth.CurrentUser;
import cn.luwneshacha.platform.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TeacherStatisticsService {
  private final UserRepository users;
  private final TeacherStatisticsRepository statistics;
  public TeacherStatisticsService(UserRepository users, TeacherStatisticsRepository statistics) {
    this.users = users; this.statistics = statistics;
  }

  /** Uses one database snapshot for the current roster and all submission totals. */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public StatisticsSummary statistics(CurrentUser current) {
    return statistics(current, null);
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public StatisticsSummary statistics(CurrentUser current, String grade) {
    var filter = GradeFilter.parse(grade);
    var teacher = requireTeacher(current);
    var students = statistics.studentTotals(teacher.getId(), filter.year(), filter.unset());
    var submissions = statistics.submissionTotals(teacher.getId(), filter.year(), filter.unset());
    var latest = statistics.latestSuccessfulTotals(teacher.getId(), filter.year(), filter.unset());
    return new StatisticsSummary(students.getTotalStudents(), students.getActiveStudents(),
        submissions.getSubmittedStudents(), students.getTotalStudents() - submissions.getSubmittedStudents(),
        latest.getStudentsWithSuccessfulReview(), submissions.getSuccessfulSubmissions(),
        submissions.getPendingSubmissions(), submissions.getProcessingSubmissions(), submissions.getFailedSubmissions(),
        latest.getLatestSuccessfulProblemCount());
  }

  cn.luwneshacha.platform.account.User requireTeacher(CurrentUser current) {
    if (current == null) throw unauthenticated();
    var teacher = users.findById(current.id()).orElseThrow(TeacherStatisticsService::unauthenticated);
    if (!teacher.isActive()) throw unauthenticated();
    if (teacher.getRole() != Role.TEACHER)
      throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "只有教师可以查看名下学生统计");
    if (teacher.isMustChangePassword())
      throw new ApiException(HttpStatus.FORBIDDEN, "PASSWORD_CHANGE_REQUIRED", "请先修改初始密码");
    return teacher;
  }

  private static ApiException unauthenticated() {
    return new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "请先登录");
  }

  public record StatisticsSummary(long totalStudents, long activeStudents, long submittedStudents,
      long notSubmittedStudents, long studentsWithSuccessfulReview, long successfulSubmissions,
      long pendingSubmissions, long processingSubmissions, long failedSubmissions,
      Long latestSuccessfulProblemCount) {}
}
