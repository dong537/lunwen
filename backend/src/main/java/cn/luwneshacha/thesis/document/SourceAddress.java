package cn.luwneshacha.thesis.document;

import java.util.List;

/**
 * A location in the inspected, unmodified XML part. Each path index selects among all
 * element children, including comment markers; text and whitespace nodes are not counted.
 * Resolve paths before removing or inserting annotations, independently of visible-text filtering.
 */
public record SourceAddress(String partName, List<Integer> elementPath, int start, int end) {
  public SourceAddress {
    if (partName == null || partName.isBlank()) throw new IllegalArgumentException("partName is required");
    if (elementPath == null || elementPath.stream().anyMatch(i -> i == null || i < 0))
      throw new IllegalArgumentException("elementPath must contain non-negative indexes");
    if (start < 0 || end < start) throw new IllegalArgumentException("invalid character range");
    elementPath = List.copyOf(elementPath);
  }
}
