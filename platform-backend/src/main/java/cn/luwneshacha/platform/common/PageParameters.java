package cn.luwneshacha.platform.common;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;

public final class PageParameters {
  private PageParameters() {}
  public static PageRequest of(int page, int size, Sort sort) {
    if (page < 1 || size < 1 || size > 100)
      throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGE", "页码须大于等于1，每页数量须在1至100之间");
    return PageRequest.of(page - 1, size, sort);
  }
}
