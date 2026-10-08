package cn.luwneshacha.thesis.document;

public record EquationModel(
    SourceAddress source,
    String type,
    String summary,
    String number,
    boolean standalone,
    boolean needsReview) {}
