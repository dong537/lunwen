package cn.luwneshacha.thesis.document;

import java.util.List;

public record SourceAddress(String partName, List<Integer> elementPath, int start, int end) {
  public SourceAddress {
    if (partName == null || partName.isBlank()) throw new IllegalArgumentException("partName is required");
    if (elementPath == null || elementPath.stream().anyMatch(i -> i == null || i < 0))
      throw new IllegalArgumentException("elementPath must contain non-negative indexes");
    if (start < 0 || end < start) throw new IllegalArgumentException("invalid character range");
    elementPath = List.copyOf(elementPath);
  }
}
