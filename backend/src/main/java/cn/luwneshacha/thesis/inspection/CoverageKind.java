package cn.luwneshacha.thesis.inspection;

/** Distinguishes why a document region is absent from the normal report. */
public enum CoverageKind {
  /** The parser did not expose this region to a format rule. */
  NOT_INSPECTED("未检查"),
  /** Only part of the region is represented by current rules. */
  PARTIALLY_INSPECTED("部分检查"),
  /** A finding may exist, but the Word writer cannot place a range there. */
  NOT_ANNOTATABLE("无法写入批注"),
  /** The document uses a construct that the current parser cannot interpret. */
  UNSUPPORTED_FORMAT("格式暂不支持"),
  /** The XML part exists but could not be parsed. */
  PARSE_ERROR("XML 解析失败");

  private final String label;

  CoverageKind(String label) { this.label = label; }

  public String label() { return label; }
}
