package cn.luwneshacha.thesis.document;

public record PageNumberModel(
    SourceAddress source,
    boolean pageField,
    boolean centered,
    String asciiFont,
    Integer sizeHalfPoints) {}
