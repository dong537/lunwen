package cn.luwneshacha.thesis.document;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;

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
    long transformHeightEmu,
    boolean sizeEvidenceInvalid,
    boolean transformSizeEvidenceInvalid,
    @JsonIgnore boolean captionFragment,
    @JsonIgnore boolean captionNumberAmbiguous,
    @JsonIgnore List<String> ambiguousCaptionChapters) {

  public ImageModel {
    ambiguousCaptionChapters = List.copyOf(ambiguousCaptionChapters);
  }

  public ImageModel(SourceAddress source, SourceAddress captionSource, String captionText,
      long widthEmu, long heightEmu, boolean inline, Boolean imageCentered, Boolean captionCentered,
      long transformWidthEmu, long transformHeightEmu, boolean sizeEvidenceInvalid,
      boolean transformSizeEvidenceInvalid, boolean captionFragment, boolean captionNumberAmbiguous) {
    this(source, captionSource, captionText, widthEmu, heightEmu, inline, imageCentered,
        captionCentered, transformWidthEmu, transformHeightEmu, sizeEvidenceInvalid,
        transformSizeEvidenceInvalid, captionFragment, captionNumberAmbiguous, List.of());
  }

  public ImageModel(SourceAddress source, SourceAddress captionSource, String captionText,
      long widthEmu, long heightEmu, boolean inline, Boolean imageCentered, Boolean captionCentered,
      long transformWidthEmu, long transformHeightEmu, boolean sizeEvidenceInvalid,
      boolean transformSizeEvidenceInvalid, boolean captionFragment) {
    this(source, captionSource, captionText, widthEmu, heightEmu, inline, imageCentered,
        captionCentered, transformWidthEmu, transformHeightEmu, sizeEvidenceInvalid,
        transformSizeEvidenceInvalid, captionFragment, false, List.of());
  }

  public ImageModel(SourceAddress source, SourceAddress captionSource, String captionText,
      long widthEmu, long heightEmu, boolean inline, Boolean imageCentered, Boolean captionCentered,
      long transformWidthEmu, long transformHeightEmu, boolean sizeEvidenceInvalid,
      boolean transformSizeEvidenceInvalid) {
    this(source, captionSource, captionText, widthEmu, heightEmu, inline, imageCentered,
        captionCentered, transformWidthEmu, transformHeightEmu, sizeEvidenceInvalid,
        transformSizeEvidenceInvalid, false, false, List.of());
  }

  public ImageModel(SourceAddress source, SourceAddress captionSource, String captionText,
      long widthEmu, long heightEmu, boolean inline, boolean placementNeedsReview) {
    this(source, captionSource, captionText, widthEmu, heightEmu, inline,
        placementNeedsReview ? null : true, placementNeedsReview ? null : true, 0, 0, false, false);
  }

  public ImageModel(SourceAddress source, SourceAddress captionSource, String captionText,
      long widthEmu, long heightEmu, boolean inline, Boolean imageCentered, Boolean captionCentered) {
    this(source, captionSource, captionText, widthEmu, heightEmu, inline, imageCentered, captionCentered, 0, 0, false, false);
  }

  public ImageModel(SourceAddress source, SourceAddress captionSource, String captionText,
      long widthEmu, long heightEmu, boolean inline, Boolean imageCentered, Boolean captionCentered,
      long transformWidthEmu, long transformHeightEmu) {
    this(source, captionSource, captionText, widthEmu, heightEmu, inline, imageCentered, captionCentered,
        transformWidthEmu, transformHeightEmu, false, false);
  }

  public boolean placementNeedsReview() {
    return !inline || imageCentered == null || captionCentered == null;
  }

  public boolean hasTransformSize() { return transformWidthEmu > 0 && transformHeightEmu > 0; }
}
