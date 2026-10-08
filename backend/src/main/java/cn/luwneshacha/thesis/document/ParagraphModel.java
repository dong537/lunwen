package cn.luwneshacha.thesis.document;

import java.util.List;

public record ParagraphModel(
    List<RunModel> runs,
    Integer firstLineIndentTwips,
    Integer leftIndentTwips,
    Integer rightIndentTwips,
    Integer lineTwips,
    String lineRule,
    SourceAddress source,
    Integer firstLineChars,
    Integer outlineLevel,
    String alignment) {
  public ParagraphModel(List<RunModel> runs, Integer firstLineIndentTwips, Integer leftIndentTwips,
      Integer rightIndentTwips, Integer lineTwips, String lineRule, SourceAddress source,
      Integer firstLineChars, Integer outlineLevel) {
    this(runs, firstLineIndentTwips, leftIndentTwips, rightIndentTwips, lineTwips, lineRule,
        source, firstLineChars, outlineLevel, null);
  }

  public ParagraphModel(List<RunModel> runs, Integer firstLineIndentTwips, Integer leftIndentTwips,
      Integer rightIndentTwips, Integer lineTwips, String lineRule, SourceAddress source,
      Integer firstLineChars) {
    this(runs, firstLineIndentTwips, leftIndentTwips, rightIndentTwips, lineTwips, lineRule,
        source, firstLineChars, null, null);
  }

  public ParagraphModel {
    runs = List.copyOf(runs);
  }
}
