package cn.luwneshacha.platform.auth;

import cn.luwneshacha.platform.common.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;

@Component
public final class SecurityResponses {
  private final ObjectMapper json;
  public SecurityResponses(ObjectMapper json) { this.json = json; }
  public void write(HttpServletResponse response, int status, String code, String message) throws IOException {
    response.setStatus(status);
    response.setContentType("application/json");
    response.setCharacterEncoding("UTF-8");
    json.writeValue(response.getOutputStream(), ApiResponse.fail(code, message));
  }
}
