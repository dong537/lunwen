package cn.luwneshacha.platform.submission;

import cn.luwneshacha.platform.account.*;
import cn.luwneshacha.platform.auth.CurrentUser;
import cn.luwneshacha.platform.auth.PasswordHasher;
import cn.luwneshacha.platform.common.*;
import cn.luwneshacha.platform.storage.ObjectPresigner;
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
  public static final Duration UPLOAD_RESERVATION = Duration.ofMinutes(15);
  private static final Logger log = LoggerFactory.getLogger(SubmissionService.class);
  private final SubmissionRepository submissions;
  private final UserRepository users;
  private final ObjectStorage storage;
  private final ObjectPresigner presigner;
  private final TransferProperties transfer;
  private final TransactionTemplate transaction;
  public SubmissionService(SubmissionRepository submissions, UserRepository users, ObjectStorage storage,
      PlatformTransactionManager manager, ObjectPresigner presigner, TransferProperties transfer) {
    this.submissions=submissions; this.users=users; this.storage=storage; this.presigner=presigner;
    this.transfer=transfer; transaction=new TransactionTemplate(manager);
  }
  public record StudentSummary(String id, String username, String name, String major, Integer grade,
      long submissionCount, Instant lastSubmittedAt, Integer latestProblemCount) {}
  public record DailyCount(String date, long count) {}
  public record StatusCount(String status, long count) {}
  public record CollegeCount(String collegeCode, String collegeName, long count) {}
  public record Dashboard(long activeStudents, long activeTeachers, long totalSubmissions,
      List<DailyCount> dailySubmissions, List<StatusCount> statusDistribution,
      List<CollegeCount> studentsByCollege) {}
  public record UploadTicket(String submissionId, String url, String method,
      Map<String,String> headers, Instant expiresAt) {
    @Override public String toString() {
      return "UploadTicket[submissionId="+submissionId+", url=REDACTED, method="+method+", expiresAt="+expiresAt+"]";
    }
  }
  public record DownloadTicket(String url, String filename, Instant expiresAt) {
    @Override public String toString() {
      return "DownloadTicket[url=REDACTED, filename="+filename+", expiresAt="+expiresAt+"]";
    }
  }
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

  public String transferMode(CurrentUser current) {
    User actor=actor(current);
    if (actor.getRole()!=Role.STUDENT) requireSupervisor(actor);
    return transfer.mode().name().toLowerCase(Locale.ROOT);
  }

  public UploadTicket createUpload(CurrentUser current, String filename, long size) {
    requireDirect();
    User actor = actor(current); requireRole(actor, Role.STUDENT);
    if (size <= 0) throw bad("EMPTY_FILE", "请选择非空的 DOCX 文件");
    if (size > MAX_BYTES) throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "论文文件不能超过50MiB");
    String name = filename(filename);
    if (!name.toLowerCase(Locale.ROOT).endsWith(".docx")) throw bad("INVALID_FILE_TYPE", "仅支持 DOCX 文件");
    String directory = "submissions/" + UUID.randomUUID();
    Instant now = Instant.now();
    Instant reservedUntil = now.plus(UPLOAD_RESERVATION);
    Submission item = transaction.execute(status -> {
      User student = actor(current, true); requireRole(student, Role.STUDENT);
      var pending = new Submission(student.getId(), name, size, directory, now);
      pending.reserveUploadUntil(reservedUntil);
      return submissions.saveAndFlush(pending);
    });
    Instant expiresAt = uploadExpiry(now, reservedUntil);
    try {
      return ticket(item, presigner.uploadUrl(directory + "/original.docx", size, DOCX, expiresAt), expiresAt);
    } catch (RuntimeException error) {
      log.warn("submission_upload_presign_failed submission={} student={} type={}", item.getId(), actor.getId(), error.getClass().getSimpleName());
      boolean failed=Boolean.TRUE.equals(transaction.execute(status -> submissions.failUpload(item.getId(),Instant.now())==1));
      if(failed) cleanupUpload(directory);
      throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PRESIGN_UNAVAILABLE", "文件服务暂时不可用，请稍后重试");
    }
  }

  public SubmissionView completeUpload(CurrentUser current, long id) {
    requireDirect();
    User actor=actor(current); requireRole(actor, Role.STUDENT);
    Submission item=owned(actor,id);
    if (item.getStatus()==SubmissionStatus.SUCCESS || item.getStatus()==SubmissionStatus.PROCESSING)
      return view(item);
    if (item.getStatus()==SubmissionStatus.FAILED)
      throw new ApiException(HttpStatus.CONFLICT,"FILE_NOT_AVAILABLE","当前审核状态不允许该操作");
    if (item.getAttempts()>0) return view(item);
    Optional<ObjectStorage.StoredMetadata> found;
    try { found=storage.head(downloadKey(item,"original")); }
    catch (IOException error) {
      log.warn("submission_upload_head_failed submission={}",item.getId(),error);
      throw storageUnavailable();
    }
    if (found.isEmpty()) {
      if (item.isUploadReserved(Instant.now()))
        throw new ApiException(HttpStatus.CONFLICT,"UPLOAD_NOT_FOUND","未检测到已上传的文件，请重试");
      throw new ApiException(HttpStatus.CONFLICT,"UPLOAD_EXPIRED","上传已超时，请重新提交");
    }
    var metadata=found.get();
    if (metadata.size()!=item.getFileSize())
      failUploadAndCleanup(item,"UPLOAD_SIZE_MISMATCH","上传文件与提交信息不一致，请重新选择文件");
    if (!isDocx(metadata.contentType()))
      failUploadAndCleanup(item,"UPLOAD_TYPE_MISMATCH","上传文件类型不正确，请重新选择 DOCX");
    Instant now=Instant.now();
    if (item.isUploadReserved(now)) {
      boolean released=Boolean.TRUE.equals(transaction.execute(status -> submissions.releaseUpload(item.getId(),now)==1));
      if (!released) {
        Submission latest=submissions.findById(item.getId()).orElseThrow(SubmissionService::notFound);
        if (latest.getStatus()==SubmissionStatus.FAILED)
          throw new ApiException(HttpStatus.CONFLICT,"FILE_NOT_AVAILABLE","当前审核状态不允许该操作");
        return view(latest);
      }
    }
    return view(submissions.findById(item.getId()).orElseThrow(SubmissionService::notFound));
  }

  public UploadTicket refreshUpload(CurrentUser current, long id) {
    requireDirect();
    User actor=actor(current); requireRole(actor, Role.STUDENT);
    Submission item=owned(actor,id);
    Instant now=Instant.now();
    if (item.getStatus()!=SubmissionStatus.PENDING || item.getAttempts()!=0 || !item.isUploadReserved(now))
      throw new ApiException(HttpStatus.CONFLICT,"UPLOAD_EXPIRED","上传已超时，请重新提交");
    Instant expiresAt=uploadExpiry(now,item.getUpdatedAt());
    try {
      return ticket(item,presigner.uploadUrl(downloadKey(item,"original"),item.getFileSize(),DOCX,expiresAt),expiresAt);
    } catch (RuntimeException error) {
      log.warn("submission_upload_presign_failed submission={} student={} type={}",item.getId(),actor.getId(),error.getClass().getSimpleName());
      throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"PRESIGN_UNAVAILABLE","文件服务暂时不可用，请稍后重试");
    }
  }

  public DownloadTicket prepareDownloadUrl(CurrentUser current, long id, String kind) {
    requireDirect();
    User actor=actor(current);
    Submission item=authorized(actor,id);
    requireDownloadable(item,kind);
    String filename=downloadFilename(item,kind);
    Instant expiresAt=Instant.now().plusSeconds(transfer.presignExpireSeconds());
    try {
      return new DownloadTicket(presigner.downloadUrl(downloadKey(item,kind),filename,expiresAt).url().toString(),
          filename,expiresAt);
    } catch (RuntimeException error) {
      log.warn("submission_download_presign_failed submission={} kind={} type={}",id,kind,error.getClass().getSimpleName());
      throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"PRESIGN_UNAVAILABLE","文件服务暂时不可用，请稍后重试");
    }
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
      User student = actor(current, true); requireRole(student, Role.STUDENT);
      var pending = new Submission(student.getId(), name, file.getSize(), directory, now);
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
    return new DownloadPlan(downloadFilename(item,kind),current,id,kind,PasswordHasher.fingerprint(actor.getPasswordHash()));
  }
  public ObjectStorage.StoredObject openDownload(DownloadPlan plan) {
    User actor=actor(plan.current);
    if(!PasswordHasher.fingerprint(actor.getPasswordHash()).equals(plan.passwordFingerprint))
      throw new ApiException(HttpStatus.UNAUTHORIZED,"SESSION_EXPIRED","登录已失效，请重新登录");
    Submission item=authorized(actor,plan.id);
    requireDownloadable(item,plan.kind);
    try { return storage.get(downloadKey(item,plan.kind)); }
    catch (IOException error) {
      log.warn("submission_download_failed submission={}",plan.id,error);
      throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE", "文件暂时无法下载，请稍后重试");
    }
  }
  private static void requireDownloadable(Submission item,String kind) {
    if (!kind.equals("original") && !kind.equals("marked") && !kind.equals("report"))
      throw bad("INVALID_FILE_KIND", "不支持的文件类型");
    if (item.getStatus() == SubmissionStatus.FAILED || item.isUploadReserved(Instant.now())
        || !kind.equals("original") && item.getStatus()!=SubmissionStatus.SUCCESS
        || kind.equals("report") && !item.isReportAvailable())
      throw new ApiException(HttpStatus.CONFLICT, "FILE_NOT_AVAILABLE", "当前审核状态不允许下载该文件");
  }
  public PageResponse<StudentSummary> students(CurrentUser current, String query, int page, int size) {
    User actor = actor(current); requireSupervisor(actor);
    Pageable pageable = pageable(page,size,Sort.by("username"));
    String search = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
    if (search.length()>100) throw bad("INVALID_SEARCH", "搜索内容不能超过100字");
    String escaped = search.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    Specification<User> spec = (root, cq, cb) -> cb.and(cb.equal(root.get("role"),Role.STUDENT),
        cb.equal(root.get("teacherId"),actor.getId()), cb.or(cb.like(cb.lower(root.get("username")),"%"+escaped+"%",'\\'),cb.like(cb.lower(root.get("name")),"%"+escaped+"%",'\\')));
    if (actor.getRole()==Role.ADMIN)
      spec=spec.and((root,cq,cb)->cb.equal(root.get("collegeCode"),actor.getCollegeCode()));
    Page<User> result=users.findAll(spec,pageable);
    List<Long> ids=result.getContent().stream().map(User::getId).toList();
    Map<Long,Long> counts=new HashMap<>(); Map<Long,Submission> latest=new HashMap<>();
    if (!ids.isEmpty()) {
      submissions.successCounts(ids).forEach(row -> counts.put((Long)row[0],(Long)row[1]));
      submissions.latestSuccesses(ids).forEach(item -> latest.put(item.getStudentId(),item));
    }
    return new PageResponse<>(result.getContent().stream().map(user -> {
      Submission last=latest.get(user.getId());
      return new StudentSummary(user.getId().toString(),user.getUsername(),user.getName(),user.getMajor(),user.getGrade(),
          counts.getOrDefault(user.getId(),0L),last==null?null:last.getCreatedAt(),last==null?null:last.getProblemCount());
    }).toList(), result.getTotalElements(),page,size);
  }
  public UserView student(CurrentUser current,long id) {
    User actor=actor(current); requireSupervisor(actor); User student=assigned(actor,id);
    return UserView.from(student,actor.getName());
  }
  public PageResponse<SubmissionView> studentSubmissions(CurrentUser current,long id,int page,int size) {
    User actor=actor(current); requireSupervisor(actor); assigned(actor,id);
    return page(id,page,size);
  }
  public Dashboard dashboard(CurrentUser current) {
    User actor=actor(current); requireRole(actor,Role.ADMIN);
    String college=actor.getCollegeCode();
    ZoneId zone=ZoneId.of("Asia/Shanghai");
    LocalDate firstDay=LocalDate.now(zone).minusDays(29);
    Instant since=firstDay.atStartOfDay(zone).toInstant();
    List<Object[]> days=college==null?submissions.dailySubmissions(since):submissions.dailySubmissionsByCollege(since,college);
    Map<String,Long> daily=new HashMap<>();
    days.forEach(row->daily.put((String)row[0],((Number)row[1]).longValue()));
    List<DailyCount> series=new ArrayList<>(30);
    for(int i=0;i<30;i++) {
      String day=firstDay.plusDays(i).toString();
      series.add(new DailyCount(day,daily.getOrDefault(day,0L)));
    }
    List<Object[]> grouped=college==null?submissions.recentStatusCounts(since):submissions.recentStatusCountsByCollege(since,college);
    Map<SubmissionStatus,Long> statusCounts=new EnumMap<>(SubmissionStatus.class);
    grouped.forEach(row->statusCounts.put((SubmissionStatus)row[0],((Number)row[1]).longValue()));
    List<StatusCount> statuses=Arrays.stream(SubmissionStatus.values())
        .map(state->new StatusCount(state.name(),statusCounts.getOrDefault(state,0L))).toList();
    List<CollegeCount> colleges=new ArrayList<>();
    for(College option:College.values()) if(college==null||college.equals(option.name()))
      colleges.add(new CollegeCount(option.name(),option.displayName(),
          users.countByRoleAndActiveAndCollegeCode(Role.STUDENT,true,option.name())));
    long activeStudents=college==null?users.countByRoleAndActive(Role.STUDENT,true)
        :users.countByRoleAndActiveAndCollegeCode(Role.STUDENT,true,college);
    if(college==null) {
      long assigned=colleges.stream().mapToLong(CollegeCount::count).sum();
      if(activeStudents>assigned) colleges.add(new CollegeCount(null,"待归属",activeStudents-assigned));
    }
    return new Dashboard(activeStudents,
        college==null?users.countByRoleAndActive(Role.TEACHER,true)
            :users.countByRoleAndActiveAndCollegeCode(Role.TEACHER,true,college),
        college==null?submissions.count():submissions.countByStudentCollege(college),
        series,statuses,List.copyOf(colleges));
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
    if (actor.getRole()!=Role.STUDENT && !SupervisionScope.isSupervisor(actor)) throw forbidden();
    Submission item=submissions.findById(id).orElseThrow(SubmissionService::notFound);
    if (actor.getRole()==Role.STUDENT && item.getStudentId().equals(actor.getId())) return item;
    if (SupervisionScope.isSupervisor(actor)) { assigned(actor,item.getStudentId()); return item; }
    throw forbidden();
  }
  private Submission owned(User actor,long id) {
    Submission item=submissions.findById(id).orElseThrow(SubmissionService::notFound);
    if (!item.getStudentId().equals(actor.getId())) throw forbidden();
    return item;
  }
  private User assigned(User teacher,long id) {
    User student=users.findById(id).orElseThrow(SubmissionService::forbidden);
    if (!SupervisionScope.ownsStudent(teacher,student)) throw forbidden();
    return student;
  }
  private User actor(CurrentUser current) {
    return actor(current, false);
  }
  private User actor(CurrentUser current, boolean lock) {
    if (current==null) throw new ApiException(HttpStatus.UNAUTHORIZED,"UNAUTHENTICATED","请先登录");
    User user=(lock ? users.findByIdForUpdate(current.id()) : users.findById(current.id()))
        .orElseThrow(SubmissionService::forbidden);
    if (!user.isActive() || user.isMustChangePassword()) throw forbidden();
    return user;
  }
  private static void requireRole(User user,Role role) { if(user.getRole()!=role) throw forbidden(); }
  private static void requireSupervisor(User user) { if(!SupervisionScope.isSupervisor(user)) throw forbidden(); }
  private static ApiException forbidden() { return new ApiException(HttpStatus.FORBIDDEN,"FORBIDDEN","无权访问该内容"); }
  private static ApiException bad(String code,String message) { return new ApiException(HttpStatus.BAD_REQUEST,code,message); }
  private static ApiException notFound() { return new ApiException(HttpStatus.NOT_FOUND,"SUBMISSION_NOT_FOUND","提交记录不存在"); }
  private static ApiException storageUnavailable() { return new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"STORAGE_UNAVAILABLE","文件服务暂时不可用，请稍后重试"); }
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
  private static String downloadFilename(Submission item,String kind) {
    String filename=item.getOriginalFilename();
    return switch(kind) {
      case "original" -> filename;
      case "marked" -> filename.substring(0,filename.length()-5)+".marked.docx";
      case "report" -> filename.substring(0,filename.length()-5)+".report.docx";
      default -> throw bad("INVALID_FILE_KIND", "不支持的文件类型");
    };
  }
  private static String downloadKey(Submission item,String kind) {
    return switch(kind) {
      case "original" -> item.getStorageDir()+"/original.docx";
      case "marked" -> item.getStorageDir()+"/attempt-"+item.getAttempts()+"/marked.docx";
      case "report" -> item.getStorageDir()+"/attempt-"+item.getAttempts()+"/report.docx";
      default -> throw bad("INVALID_FILE_KIND", "不支持的文件类型");
    };
  }
  private static boolean isDocx(String contentType) {
    if(contentType==null) return false;
    int separator=contentType.indexOf(';');
    return (separator<0?contentType:contentType.substring(0,separator)).trim().equalsIgnoreCase(DOCX);
  }
  private Instant uploadExpiry(Instant now,Instant reservedUntil) {
    Instant expiry=now.plusSeconds(transfer.presignExpireSeconds());
    Instant cap=reservedUntil.minusSeconds(60);
    if (expiry.isAfter(cap)) expiry=cap;
    if (!expiry.isAfter(now)) throw new ApiException(HttpStatus.CONFLICT,"UPLOAD_EXPIRED","上传已超时，请重新提交");
    return expiry;
  }
  private static UploadTicket ticket(Submission item,ObjectPresigner.UploadTicket ticket,Instant expiresAt) {
    return new UploadTicket(item.getId().toString(),ticket.url().toString(),"PUT",Map.of("Content-Type",DOCX),expiresAt);
  }
  private void requireDirect() {
    if (transfer.mode()!=TransferProperties.Mode.DIRECT)
      throw new ApiException(HttpStatus.CONFLICT,"DIRECT_TRANSFER_DISABLED","当前未启用浏览器直传");
  }
  private void failUploadAndCleanup(Submission item,String code,String message) {
    boolean failed=Boolean.TRUE.equals(transaction.execute(status -> submissions.failUpload(item.getId(),Instant.now())==1));
    if (failed) cleanupUpload(item.getStorageDir());
    throw new ApiException(HttpStatus.CONFLICT,code,message);
  }
  private void cleanupUpload(String directory) {
    try { storage.deletePrefix(directory+"/"); }
    catch(IOException|RuntimeException error) { log.error("submission_upload_cleanup_failed directory={}",directory,error); }
  }
}
