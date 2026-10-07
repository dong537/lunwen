package cn.luwneshacha.thesis.document;

public record RunModel(
    String text,
    String asciiFont,
    String eastAsiaFont,
    String complexFont,
    Integer fontSizeHalfPoints,
    Boolean bold,
    SourceAddress source) {}
