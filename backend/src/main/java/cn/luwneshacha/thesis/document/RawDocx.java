package cn.luwneshacha.thesis.document;

import java.util.Map;

public record RawDocx(String sourceSha256, byte[] sourceBytes, Map<String, RawPart> parts, int entryCount) {
  public RawDocx {
    if (sourceSha256 == null || sourceBytes == null || parts == null) throw new IllegalArgumentException("raw docx fields are required");
    sourceBytes = sourceBytes.clone();
    parts = Map.copyOf(parts);
  }

  @Override public byte[] sourceBytes() { return sourceBytes.clone(); }

  public RawPart part(String name) {
    RawPart result = parts.get(name);
    if (result == null) throw new IllegalArgumentException("part not found: " + name);
    return result;
  }
}
