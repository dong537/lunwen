package cn.luwneshacha.thesis.document;

public record SectionModel(
    Integer topMarginTwips,
    Integer rightMarginTwips,
    Integer bottomMarginTwips,
    Integer leftMarginTwips,
    SourceAddress source) {}
