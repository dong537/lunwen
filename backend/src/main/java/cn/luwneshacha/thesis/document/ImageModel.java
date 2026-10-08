package cn.luwneshacha.thesis.document;

public record ImageModel(
    SourceAddress source,
    SourceAddress captionSource,
    String captionText,
    long widthEmu,
    long heightEmu,
    boolean inline,
    boolean placementNeedsReview) {}
