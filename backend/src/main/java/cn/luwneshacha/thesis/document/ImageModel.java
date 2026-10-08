package cn.luwneshacha.thesis.document;

public record ImageModel(
    SourceAddress source,
    SourceAddress captionSource,
    String captionText,
    long widthEmu,
    long heightEmu,
    boolean inline,
    Boolean imageCentered,
    Boolean captionCentered,
    long transformWidthEmu,
    long transformHeightEmu) {

  public ImageModel(SourceAddress source, SourceAddress captionSource, String captionText,
      long widthEmu, long heightEmu, boolean inline, boolean placementNeedsReview) {
    this(source, captionSource, captionText, widthEmu, heightEmu, inline,
        placementNeedsReview ? null : true, placementNeedsReview ? null : true, 0, 0);
  }

  public ImageModel(SourceAddress source, SourceAddress captionSource, String captionText,
      long widthEmu, long heightEmu, boolean inline, Boolean imageCentered, Boolean captionCentered) {
    this(source, captionSource, captionText, widthEmu, heightEmu, inline, imageCentered, captionCentered, 0, 0);
  }

  public boolean placementNeedsReview() {
    return !inline || imageCentered == null || captionCentered == null;
  }

  public boolean hasTransformSize() { return transformWidthEmu > 0 && transformHeightEmu > 0; }
}
