package cn.luwneshacha.thesis.document;

import java.util.Map;

public record RawDocx(String sourceSha256, Map<String, RawPart> parts, int entryCount) {
  public RawDocx {
    if (sourceSha256 == null || parts == null) throw new IllegalArgumentException("raw docx fields are required");
    parts = Map.copyOf(parts);
  }

  public RawPart part(String name) {
    RawPart result = parts.get(name);
    if (result == null) throw new IllegalArgumentException("part not found: " + name);
    return result;
  }
}