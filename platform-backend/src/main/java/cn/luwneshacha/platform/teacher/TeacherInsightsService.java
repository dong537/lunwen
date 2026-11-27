package cn.luwneshacha.platform.teacher;

import cn.luwneshacha.platform.account.Role;
import cn.luwneshacha.platform.account.User;
import cn.luwneshacha.platform.account.UserRepository;
import cn.luwneshacha.platform.auth.CurrentUser;
import cn.luwneshacha.platform.common.ApiException;
import cn.luwneshacha.platform.common.PageResponse;
import cn.luwneshacha.platform.submission.Submission;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TeacherInsightsService {
  private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
  private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
  private static final int EXPORT_LIMIT = 10_000;

  private final TeacherStatisticsService access;
  private final TeacherInsightsRepository insights;
  private final UserRepository users;

  public TeacherInsightsService(TeacherStatisticsService access, TeacherInsightsRepository insights,
      UserRepository users) {
    this.access = access; this.insights = insights; this.users = users;
  }

  public record Coverage(long eligibleReviews, long indexedReviews) {}
  public record RuleLabel(String ruleId, String description) {}
  public record RuleCount(String ruleId, String description, long count, long affectedStudents) {}
  public record ProblemBucket(String label, long students) {}
  public record DailyCount(String date, long count) {}
  public record HeatmapStudent(String studentId, String name, Map<String,Integer> counts) {}
  public record Heatmap(List<RuleLabel> rules, List<HeatmapStudent> students) {}
  public record StudentInsight(String id, String username, String name, Integer grade, String major,
      Instant lastSubmittedAt, Integer latestProblemCount, String latestReviewStatus) {}
  public record Insights(Coverage coverage, List<RuleCount> topRules,
      List<ProblemBucket> problemDistribution, List<DailyCount> dailySubmissions,
      Heatmap heatmap, PageResponse<StudentInsight> students) {}

  private enum ReviewRange {
    LAST_30("30", 30), LAST_90("90", 90), LAST_365("365", 365), ALL("ALL", 0);
    final String value;
    final int days;
    ReviewRange(String value, int days) { this.value=value; this.days=days; }
    static ReviewRange parse(String supplied) {
      String value=supplied==null||supplied.isBlank()?"30":supplied;
      for(var range:values()) if(range.value.equals(value)) return range;
      throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_RANGE","审核范围须选择近30天、近90天、近365天或全部");
    }
    LocalDate firstDay(LocalDate today) { return days==0 ? null : today.minusDays(days-1); }
    Instant since(LocalDate today) {
      return days==0 ? Instant.EPOCH : firstDay(today).atStartOfDay(ZONE).toInstant();
    }
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Insights insights(CurrentUser current, String grade, String rangeValue, int page, int size) {
    if(page<1||size<1||size>100)
      throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_PAGE","页码须大于0，每页数量为1至100");
    var teacher=access.requireTeacher(current);
    var filter=GradeFilter.parse(grade);
    var range=ReviewRange.parse(rangeValue);
    LocalDate today=LocalDate.now(ZONE);
    Instant since=range.since(today);
    var reviewSummary=insights.latestReviewSummary(teacher.getId(),filter.year(),filter.unset(),since).get(0);
    var ranked=rankRules(insights.topRuleRows(teacher.getId(),filter.year(),filter.unset(),since,
        PageRequest.of(0,10)));
    Page<User> students=users.findAll(studentSpec(teacher.getId(),filter),
        PageRequest.of(page-1,size,Sort.by("username","id")));
    return new Insights(new Coverage(number(reviewSummary[0]),number(reviewSummary[1])),ranked,
        distribution(reviewSummary),daily(teacher.getId(),filter,range,today,since),
        heatmap(ranked,teacher.getId(),filter,since),new PageResponse<>(studentRows(students.getContent(),since),
            students.getTotalElements(),page,size));
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public byte[] export(CurrentUser current, String grade, String rangeValue) {
    var teacher=access.requireTeacher(current);
    var filter=GradeFilter.parse(grade);
    var range=ReviewRange.parse(rangeValue);
    var spec=studentSpec(teacher.getId(),filter);
    long total=users.count(spec);
    if(total>EXPORT_LIMIT)
      throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE,"EXPORT_TOO_LARGE","学生超过10000人，请缩小年级范围后导出");
    Instant since=range.since(LocalDate.now(ZONE));
    var workbook=new SXSSFWorkbook(100);
    workbook.setCompressTempFiles(true);
    try (workbook; var output=new ByteArrayOutputStream()) {
      var sheet=workbook.createSheet("论文指导统计");
      var header=sheet.createRow(0);
      String[] names={"学号","姓名","年级","专业","最近提交时间","最近成功审核问题数","最近提交状态"};
      Font bold=workbook.createFont(); bold.setBold(true);
      CellStyle style=workbook.createCellStyle(); style.setFont(bold);
      for(int c=0;c<names.length;c++) {var cell=header.createCell(c);cell.setCellValue(names[c]);cell.setCellStyle(style);}
      sheet.createFreezePane(0,1);
      int offset=0,rowIndex=1;
      while(offset<total) {
        var page=users.findAll(spec,PageRequest.of(offset/500,500,Sort.by("username","id")));
        for(StudentInsight item:studentRows(page.getContent(),since)) {
          var row=sheet.createRow(rowIndex++);
          row.createCell(0).setCellValue(item.username());
          row.createCell(1).setCellValue(item.name());
          row.createCell(2).setCellValue(item.grade()==null?"未填写":item.grade()+"年");
          row.createCell(3).setCellValue(item.major()==null?"":item.major());
          row.createCell(4).setCellValue(item.lastSubmittedAt()==null?"":
              DATE_TIME.format(item.lastSubmittedAt().atZone(ZONE)));
          if(item.latestProblemCount()!=null) row.createCell(5).setCellValue(item.latestProblemCount());
          row.createCell(6).setCellValue(item.latestReviewStatus()==null?"":item.latestReviewStatus());
        }
        offset+=page.getNumberOfElements();
        if(page.isEmpty()) break;
      }
      int[] widths={18,16,12,22,23,24,18};
      for(int c=0;c<widths.length;c++) sheet.setColumnWidth(c,widths[c]*256);
      workbook.write(output);
      return output.toByteArray();
    } catch(IOException error) {
      throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"EXPORT_FAILED","统计文件生成失败，请稍后重试");
    } finally {
      workbook.dispose();
    }
  }

  private static Specification<User> studentSpec(long teacherId,GradeFilter filter) {
    return (root,query,cb)->{
      var base=cb.and(cb.equal(root.get("teacherId"),teacherId),cb.equal(root.get("role"),Role.STUDENT));
      if(filter.unset()) return cb.and(base,cb.isNull(root.get("grade")));
      return filter.year()==null?base:cb.and(base,cb.equal(root.get("grade"),filter.year()));
    };
  }

  private List<StudentInsight> studentRows(List<User> selected,Instant since) {
    if(selected.isEmpty()) return List.of();
    List<Long> ids=selected.stream().map(User::getId).toList();
    Map<Long,Submission> recent=insights.latestSubmissionsForStudents(ids,since).stream()
        .collect(Collectors.toMap(Submission::getStudentId,row->row));
    Map<Long,Submission> successful=insights.latestSuccessfulForStudents(ids,since).stream()
        .collect(Collectors.toMap(Submission::getStudentId,row->row));
    return selected.stream().map(student->{
      Submission latest=recent.get(student.getId()),reviewed=successful.get(student.getId());
      return new StudentInsight(student.getId().toString(),student.getUsername(),student.getName(),
          student.getGrade(),student.getMajor(),latest==null?null:latest.getCreatedAt(),
          reviewed==null?null:reviewed.getProblemCount(),latest==null?null:latest.getStatus().name());
    }).toList();
  }

  private static long number(Object value) {
    return ((Number)value).longValue();
  }

  private static List<RuleCount> rankRules(List<Object[]> rows) {
    return rows.stream().map(row->new RuleCount((String)row[0],(String)row[1],number(row[2]),number(row[3]))).toList();
  }

  private static List<ProblemBucket> distribution(Object[] summary) {
    return List.of(new ProblemBucket("0",number(summary[2])),
        new ProblemBucket("1-5",number(summary[3])),
        new ProblemBucket("6-10",number(summary[4])),
        new ProblemBucket(">10",number(summary[5])));
  }

  private List<DailyCount> daily(long teacherId,GradeFilter filter,ReviewRange range,LocalDate today,Instant since) {
    Map<String,Long> counts=new LinkedHashMap<>();
    for(Object[] row:insights.dailySubmissions(teacherId,filter.year(),filter.unset(),since))
      counts.put((String)row[0],((Number)row[1]).longValue());
    LocalDate first=range.firstDay(today);
    if(first==null) first=counts.isEmpty()?today.minusDays(29):LocalDate.parse(counts.keySet().iterator().next());
    List<DailyCount> result=new ArrayList<>();
    for(LocalDate day=first;!day.isAfter(today);day=day.plusDays(1))
      result.add(new DailyCount(day.toString(),counts.getOrDefault(day.toString(),0L)));
    return result;
  }

  private Heatmap heatmap(List<RuleCount> ranked,long teacherId,GradeFilter filter,Instant since) {
    List<RuleLabel> rules=ranked.stream().limit(8)
        .map(rule->new RuleLabel(rule.ruleId(),rule.description())).toList();
    if(rules.isEmpty()) return new Heatmap(List.of(),List.of());
    List<Long> rankedStudents=insights.topStudentRows(teacherId,filter.year(),filter.unset(),since,
        PageRequest.of(0,10)).stream().map(row->number(row[0])).toList();
    if(rankedStudents.isEmpty()) return new Heatmap(rules,List.of());
    Map<Long,Map<String,Integer>> byStudent=new HashMap<>();
    for(var row:insights.heatmapCounts(teacherId,filter.year(),filter.unset(),since,
        rankedStudents,rules.stream().map(RuleLabel::ruleId).toList()))
      byStudent.computeIfAbsent(number(row[0]),id->new HashMap<>())
          .put((String)row[1],Math.toIntExact(number(row[2])));
    Map<Long,User> identities=users.findAllById(rankedStudents).stream()
        .collect(Collectors.toMap(User::getId,user->user));
    List<HeatmapStudent> students=rankedStudents.stream().filter(identities::containsKey)
        .map(id->new HeatmapStudent(id.toString(),identities.get(id).getName(),
            rules.stream().collect(Collectors.toMap(RuleLabel::ruleId,
                rule->byStudent.getOrDefault(id,Map.of()).getOrDefault(rule.ruleId(),0),
                (first,second)->first,LinkedHashMap::new)))).toList();
    return new Heatmap(rules,students);
  }
}
