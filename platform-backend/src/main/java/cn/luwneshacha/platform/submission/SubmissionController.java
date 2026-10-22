package cn.luwneshacha.platform.submission;

import cn.luwneshacha.platform.account.UserView;
import cn.luwneshacha.platform.auth.CurrentUser;
import cn.luwneshacha.platform.common.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController @RequestMapping("/api")
public class SubmissionController {
  private final SubmissionService service;
  public SubmissionController(SubmissionService service) {this.service=service;}
  @PostMapping(value="/submissions",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) @ResponseStatus(HttpStatus.ACCEPTED)
  public ApiResponse<SubmissionView> upload(@AuthenticationPrincipal CurrentUser user,@RequestParam MultipartFile file) {return ApiResponse.ok(service.upload(user,file));}
  @PostMapping("/submissions/uploads")
  public ResponseEntity<ApiResponse<SubmissionService.UploadTicket>> createUpload(
      @AuthenticationPrincipal CurrentUser user,@Valid @RequestBody CreateUpload input) {
    return ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.CACHE_CONTROL,"no-store")
        .body(ApiResponse.ok(service.createUpload(user,input.filename(),input.size())));
  }
  @PostMapping("/submissions/{id}/upload-complete") @ResponseStatus(HttpStatus.ACCEPTED)
  public ApiResponse<SubmissionView> completeUpload(@AuthenticationPrincipal CurrentUser user,@PathVariable long id) {
    return ApiResponse.ok(service.completeUpload(user,id));
  }
  @PostMapping("/submissions/{id}/upload-url")
  public ResponseEntity<ApiResponse<SubmissionService.UploadTicket>> refreshUpload(
      @AuthenticationPrincipal CurrentUser user,@PathVariable long id) {
    return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"no-store")
        .body(ApiResponse.ok(service.refreshUpload(user,id)));
  }
  @GetMapping("/submissions/transfer-mode")
  public ResponseEntity<ApiResponse<Map<String,String>>> transferMode() {
    return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"no-store")
        .body(ApiResponse.ok(Map.of("mode",service.transferMode())));
  }
  @GetMapping("/submissions")
  public ApiResponse<PageResponse<SubmissionView>> mine(@AuthenticationPrincipal CurrentUser user,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) {return ApiResponse.ok(service.mine(user,page,size));}
  @GetMapping("/submissions/{id}")
  public ApiResponse<SubmissionView> detail(@AuthenticationPrincipal CurrentUser user,@PathVariable long id) {return ApiResponse.ok(service.detail(user,id));}
  @GetMapping("/submissions/{id}/files/{kind}/url")
  public ResponseEntity<ApiResponse<SubmissionService.DownloadTicket>> downloadUrl(
      @AuthenticationPrincipal CurrentUser user,@PathVariable long id,@PathVariable String kind) {
    return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"no-store")
        .body(ApiResponse.ok(service.prepareDownloadUrl(user,id,kind)));
  }
  @GetMapping("/submissions/{id}/files/{kind}")
  public ResponseEntity<StreamingResponseBody> download(@AuthenticationPrincipal CurrentUser user,@PathVariable long id,@PathVariable String kind) {
    var download=service.prepareDownload(user,id,kind);
    StreamingResponseBody body=output->{try(var object=service.openDownload(download)){object.stream().transferTo(output);}};
    return ResponseEntity.ok().contentType(MediaType.parseMediaType(SubmissionService.DOCX))
        .header(HttpHeaders.CACHE_CONTROL,"private, no-store")
        .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(download.filename(),StandardCharsets.UTF_8).build().toString()).body(body);
  }
  @GetMapping("/teacher/students")
  public ApiResponse<PageResponse<SubmissionService.StudentSummary>> students(@AuthenticationPrincipal CurrentUser user,@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) {return ApiResponse.ok(service.students(user,q,page,size));}
  @GetMapping("/teacher/students/{id}")
  public ApiResponse<UserView> student(@AuthenticationPrincipal CurrentUser user,@PathVariable long id) {return ApiResponse.ok(service.student(user,id));}
  @GetMapping("/teacher/students/{id}/submissions")
  public ApiResponse<PageResponse<SubmissionView>> history(@AuthenticationPrincipal CurrentUser user,@PathVariable long id,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) {return ApiResponse.ok(service.studentSubmissions(user,id,page,size));}
  @GetMapping("/admin/dashboard")
  public ApiResponse<SubmissionService.Dashboard> dashboard(@AuthenticationPrincipal CurrentUser user) {return ApiResponse.ok(service.dashboard(user));}
  public record CreateUpload(@NotBlank(message="请选择文件") @Size(max=255,message="文件名不能超过255字符") String filename,
      @Positive(message="文件大小不正确") long size) {
    @Override public String toString() { return "CreateUpload[filename="+filename+", size="+size+"]"; }
  }
}
