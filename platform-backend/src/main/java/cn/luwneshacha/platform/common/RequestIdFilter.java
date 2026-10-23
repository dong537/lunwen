package cn.luwneshacha.platform.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component @Order(Ordered.HIGHEST_PRECEDENCE)
public final class RequestIdFilter extends OncePerRequestFilter {
  private static final String ATTRIBUTE = RequestIdFilter.class.getName() + ".requestId";
  @Override protected boolean shouldNotFilterAsyncDispatch() { return false; }
  @Override protected boolean shouldNotFilterErrorDispatch() { return false; }
  @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain chain) throws ServletException, IOException {
    String requestId = (String) request.getAttribute(ATTRIBUTE);
    if (requestId == null) {
      requestId = UUID.randomUUID().toString();
      request.setAttribute(ATTRIBUTE, requestId);
    }
    String previous = MDC.get("requestId");
    MDC.put("requestId", requestId);
    if (!response.isCommitted()) response.setHeader("X-Request-Id", requestId);
    try { chain.doFilter(request, response); }
    finally { if (previous == null) MDC.remove("requestId"); else MDC.put("requestId", previous); }
  }
}
