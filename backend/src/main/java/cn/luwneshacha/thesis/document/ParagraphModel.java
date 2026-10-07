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
    Integer firstLineChars) {
  public ParagraphModel {
    runs = List.copyOf(runs);
  }
}
