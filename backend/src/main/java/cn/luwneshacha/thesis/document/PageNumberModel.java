package cn.luwneshacha.thesis.document;

public record PageNumberModel(
    SourceAddress source,
    boolean pageField,
    Boolean centered,
    String asciiFont,
    Integer sizeHalfPoints) {}
