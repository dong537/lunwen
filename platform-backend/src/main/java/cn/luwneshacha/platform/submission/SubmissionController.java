package cn.luwneshacha.platform.submission;

import cn.luwneshacha.platform.account.UserView;
import cn.luwneshacha.platform.auth.CurrentUser;
import cn.luwneshacha.platform.common.*;
import java.nio.charset.StandardCharsets;
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
  @GetMapping("/submissions")
  public ApiResponse<PageResponse<SubmissionView>> mine(@AuthenticationPrincipal CurrentUser user,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) {return ApiResponse.ok(service.mine(user,page,size));}
  @GetMapping("/submissions/{id}")
  public ApiResponse<SubmissionView> detail(@AuthenticationPrincipal CurrentUser user,@PathVariable long id) {return ApiResponse.ok(service.detail(user,id));}
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
}
