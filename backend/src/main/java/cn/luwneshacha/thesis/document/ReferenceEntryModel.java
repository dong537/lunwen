package cn.luwneshacha.thesis.document;

public record ReferenceEntryModel(
    SourceAddress source,
    String number,
    String text,
    String category,
    boolean needsReview) {}
