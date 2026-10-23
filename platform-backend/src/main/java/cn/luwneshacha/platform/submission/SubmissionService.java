package cn.luwneshacha.platform.submission;

import cn.luwneshacha.platform.account.*;
import cn.luwneshacha.platform.auth.CurrentUser;
import cn.luwneshacha.platform.auth.PasswordHasher;
import cn.luwneshacha.platform.common.*;
import cn.luwneshacha.platform.storage.ObjectStorage;
import java.io.*;
import java.time.*;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Service
public class SubmissionService {
  public static final long MAX_BYTES = 50L * 1024 * 1024;
  public static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
  public static final Duration UPLOAD_RESERVATION = Duration.ofMinutes(10);
  private static final Logger log = LoggerFactory.getLogger(SubmissionService.class);
  private final SubmissionRepository submissions;
  private final UserRepository users;
  private final ObjectStorage storage;
  private final TransactionTemplate transaction;
  public SubmissionService(SubmissionRepository submissions, UserRepository users, ObjectStorage storage, PlatformTransactionManager manager) {
    this.submissions=submissions; this.users=users; this.storage=storage; transaction=new TransactionTemplate(manager);
  }
  public record StudentSummary(String id, String username, String name, String major,
      long submissionCount, Instant lastSubmittedAt, Integer latestProblemCount) {}
  public record Dashboard(long activeStudents, long activeTeachers, long totalSubmissions) {}
  public static final class DownloadPlan {
    private final String filename;
    private final CurrentUser current;
    private final long id;
    private final String kind;
    private final String passwordFingerprint;
    private DownloadPlan(String filename,CurrentUser current,long id,String kind,String passwordFingerprint) {
      this.filename=filename;this.current=current;this.id=id;this.kind=kind;this.passwordFingerprint=passwordFingerprint;
    }
    public String filename() {return filename;}
  }

  public SubmissionView upload(CurrentUser current, MultipartFile file) {
    User actor = actor(current); requireRole(actor, Role.STUDENT);
    if (file == null || file.isEmpty()) throw bad("EMPTY_FILE", "请选择非空的 DOCX 文件");
    if (file.getSize() > MAX_BYTES) throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "论文文件不能超过50MiB");
    String name = filename(file.getOriginalFilename());
    if (!name.toLowerCase(Locale.ROOT).endsWith(".docx")) throw bad("INVALID_FILE_TYPE", "仅支持 DOCX 文件");
    String directory = "submissions/" + UUID.randomUUID();
    Instant now = Instant.now();
    Submission item = transaction.execute(status -> {
      var pending = new Submission(actor.getId(), name, file.getSize(), directory, now);
      pending.reserveUploadUntil(now.plus(UPLOAD_RESERVATION));
      return submissions.saveAndFlush(pending);
    });
    try (InputStream input = file.getInputStream()) {
      storage.put(directory + "/original.docx", input, file.getSize(), DOCX);
    } catch (IOException | RuntimeException error) {
      log.warn("submission_upload_failed submission={} student={}", item.getId(), actor.getId(), error);
      try {
        boolean failed=Boolean.TRUE.equals(transaction.execute(status -> submissions.failUpload(item.getId(),Instant.now())==1));
        if(failed) cleanupUpload(directory);
      }
      catch (RuntimeException failure) { log.error("submission_upload_failure_pending submission={}",item.getId(),failure); }
      throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE", "论文保存失败，请稍后重试");
    }
    try {
      boolean released = Boolean.TRUE.equals(transaction.execute(status -> submissions.releaseUpload(item.getId(),Instant.now()) == 1));
      if (!released) throw new ApiException(HttpStatus.CONFLICT,"UPLOAD_EXPIRED","上传用时过长，请检查提交记录后重新提交");
    } catch (RuntimeException error) {
      // A committed reservation retains ownership even when release acknowledgement is lost.
      log.error("submission_upload_release_pending submission={}",item.getId(),error);
      throw error;
    }
    return view(submissions.findById(item.getId()).orElseThrow());
  }

  public PageResponse<SubmissionView> mine(CurrentUser current, int page, int size) {
    User actor = actor(current); requireRole(actor, Role.STUDENT);
    return page(actor.getId(), page, size);
  }
  public SubmissionView detail(CurrentUser current, long id) { return view(authorized(actor(current), id)); }
  public DownloadPlan prepareDownload(CurrentUser current,long id,String kind) {
    User actor=actor(current);
    Submission item = authorized(actor, id);
    requireDownloadable(item,kind);
    String filename = item.getOriginalFilename();
    if (!kind.equals("original")) filename = filename.substring(0, filename.length()-5)+"."+kind+".docx";
    return new DownloadPlan(filename,current,id,kind,PasswordHasher.fingerprint(actor.getPasswordHash()));
  }
  public ObjectStorage.StoredObject openDownload(DownloadPlan plan) {
    User actor=actor(plan.current);
    if(!PasswordHasher.fingerprint(actor.getPasswordHash()).equals(plan.passwordFingerprint))
      throw new ApiException(HttpStatus.UNAUTHORIZED,"SESSION_EXPIRED","登录已失效，请重新登录");
    Submission item=authorized(actor,plan.id);
    requireDownloadable(item,plan.kind);
    String key=plan.kind.equals("original")?item.getStorageDir()+"/original.docx"
        :item.getStorageDir()+"/attempt-"+item.getAttempts()+"/"+plan.kind+".docx";
    try { return storage.get(key); }
    catch (IOException error) {
      log.warn("submission_download_failed submission={}",plan.id,error);
      throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE", "文件暂时无法下载，请稍后重试");
    }
  }
  private static void requireDownloadable(Submission item,String kind) {
    if (!Set.of("original","marked","report").contains(kind)) throw bad("INVALID_FILE_KIND", "不支持的文件类型");
    if (item.getStatus() == SubmissionStatus.FAILED || item.isUploadReserved(Instant.now())
        || !kind.equals("original") && item.getStatus()!=SubmissionStatus.SUCCESS
        || kind.equals("report") && !item.isReportAvailable())
      throw new ApiException(HttpStatus.CONFLICT, "FILE_NOT_AVAILABLE", "当前审核状态不允许下载该文件");
  }
  public PageResponse<StudentSummary> students(CurrentUser current, String query, int page, int size) {
    User actor = actor(current); requireRole(actor, Role.TEACHER);
    Pageable pageable = pageable(page,size,Sort.by("username"));
    String search = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
    if (search.length()>100) throw bad("INVALID_SEARCH", "搜索内容不能超过100字");
    String escaped = search.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    Specification<User> spec = (root, cq, cb) -> cb.and(cb.equal(root.get("role"),Role.STUDENT),
        cb.equal(root.get("teacherId"),actor.getId()), cb.or(cb.like(cb.lower(root.get("username")),"%"+escaped+"%",'\\'),cb.like(cb.lower(root.get("name")),"%"+escaped+"%",'\\')));
    Page<User> result=users.findAll(spec,pageable);
    List<Long> ids=result.getContent().stream().map(User::getId).toList();
    Map<Long,Long> counts=new HashMap<>(); Map<Long,Submission> latest=new HashMap<>();
    if (!ids.isEmpty()) {
      submissions.successCounts(ids).forEach(row -> counts.put((Long)row[0],(Long)row[1]));
      submissions.latestSuccesses(ids).forEach(item -> latest.put(item.getStudentId(),item));
    }
    return new PageResponse<>(result.getContent().stream().map(user -> {
      Submission last=latest.get(user.getId());
      return new StudentSummary(user.getId().toString(),user.getUsername(),user.getName(),user.getMajor(),
          counts.getOrDefault(user.getId(),0L),last==null?null:last.getCreatedAt(),last==null?null:last.getProblemCount());
    }).toList(), result.getTotalElements(),page,size);
  }
  public UserView student(CurrentUser current,long id) {
    User actor=actor(current); requireRole(actor,Role.TEACHER); User student=assigned(actor,id);
    return UserView.from(student,actor.getName());
  }
  public PageResponse<SubmissionView> studentSubmissions(CurrentUser current,long id,int page,int size) {
    User actor=actor(current); requireRole(actor,Role.TEACHER); assigned(actor,id);
    return page(id,page,size);
  }
  public Dashboard dashboard(CurrentUser current) {
    User actor=actor(current); requireRole(actor,Role.ADMIN);
    return new Dashboard(users.countByRoleAndActive(Role.STUDENT,true),users.countByRoleAndActive(Role.TEACHER,true),submissions.count());
  }
  private PageResponse<SubmissionView> page(long studentId,int page,int size) {
    Page<Submission> result=submissions.findByStudentIdOrderByCreatedAtDescIdDesc(studentId,pageable(page,size,Sort.unsorted()));
    var ranks=numbers(result.getContent());
    return new PageResponse<>(result.getContent().stream().map(item->SubmissionView.from(item,ranks.get(item.getId()))).toList(),result.getTotalElements(),page,size);
  }
  private SubmissionView view(Submission item) { return SubmissionView.from(item,numbers(List.of(item)).get(item.getId())); }
  private Map<Long,Long> numbers(List<Submission> items) {
    Map<Long,Long> result=new HashMap<>();
    if (!items.isEmpty()) submissions.submissionNumbers(items.stream().map(Submission::getId).toList()).forEach(row->result.put((Long)row[0],(Long)row[1]));
    return result;
  }
  private Submission authorized(User actor,long id) {
    if (actor.getRole()==Role.ADMIN) throw forbidden();
    Submission item=submissions.findById(id).orElseThrow(()->new ApiException(HttpStatus.NOT_FOUND,"SUBMISSION_NOT_FOUND","提交记录不存在"));
    if (actor.getRole()==Role.STUDENT && item.getStudentId().equals(actor.getId())) return item;
    if (actor.getRole()==Role.TEACHER) { assigned(actor,item.getStudentId()); return item; }
    throw forbidden();
  }
  private User assigned(User teacher,long id) {
    User student=users.findById(id).orElseThrow(SubmissionService::forbidden);
    if (student.getRole()!=Role.STUDENT || !teacher.getId().equals(student.getTeacherId())) throw forbidden();
    return student;
  }
  private User actor(CurrentUser current) {
    if (current==null) throw new ApiException(HttpStatus.UNAUTHORIZED,"UNAUTHENTICATED","请先登录");
    User user=users.findById(current.id()).orElseThrow(SubmissionService::forbidden);
    if (!user.isActive() || user.isMustChangePassword()) throw forbidden();
    return user;
  }
  private static void requireRole(User user,Role role) { if(user.getRole()!=role) throw forbidden(); }
  private static ApiException forbidden() { return new ApiException(HttpStatus.FORBIDDEN,"FORBIDDEN","无权访问该内容"); }
  private static ApiException bad(String code,String message) { return new ApiException(HttpStatus.BAD_REQUEST,code,message); }
  private static Pageable pageable(int page,int size,Sort sort) {
    if(page<1||size<1||size>100) throw bad("INVALID_PAGE","页码须大于0，每页数量为1至100");
    return PageRequest.of(page-1,size,sort);
  }
  private static String filename(String supplied) {
    if(supplied==null) throw bad("INVALID_FILENAME","文件名不能为空");
    String name=supplied.replace('\\','/'); name=name.substring(name.lastIndexOf('/')+1).replaceAll("[\\p{Cntrl}]","").strip();
    if(name.isEmpty()||name.length()>255) throw bad("INVALID_FILENAME","文件名长度须为1至255字符");
    return name;
  }
  private void cleanupUpload(String directory) {
    try { storage.deletePrefix(directory+"/"); }
    catch(IOException|RuntimeException error) { log.error("submission_upload_cleanup_failed directory={}",directory,error); }
  }
}
