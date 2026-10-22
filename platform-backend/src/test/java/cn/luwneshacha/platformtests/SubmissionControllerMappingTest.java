package cn.luwneshacha.platformtests;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import cn.luwneshacha.platform.submission.SubmissionController;
import cn.luwneshacha.platform.submission.SubmissionService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SubmissionControllerMappingTest {
  @Test void transferModeIsNotParsedAsSubmissionId() throws Exception {
    var service=mock(SubmissionService.class);
    when(service.transferMode()).thenReturn("direct");
    var mvc=MockMvcBuilders.standaloneSetup(new SubmissionController(service)).build();
    mvc.perform(get("/api/submissions/transfer-mode"))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control","no-store"))
        .andExpect(jsonPath("$.data.mode").value("direct"));
  }
}
