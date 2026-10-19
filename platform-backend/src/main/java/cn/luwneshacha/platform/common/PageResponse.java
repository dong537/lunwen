package cn.luwneshacha.platform.common;

import java.util.List;

public record PageResponse<T>(List<T> items, long total, int page, int size) {
  public PageResponse { items = List.copyOf(items); }
}
