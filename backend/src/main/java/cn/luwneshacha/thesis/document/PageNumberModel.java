package cn.luwneshacha.thesis.document;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;

public record PageNumberModel(
    SourceAddress source,
    boolean pageField,
    Boolean centered,
    String asciiFont,
    Integer sizeHalfPoints,
    @JsonIgnore List<Integer> applicableSectionIndexes,
    @JsonIgnore List<RunFormatting> runFormatting,
    @JsonIgnore FooterVariant footerVariant) {
  public PageNumberModel(SourceAddress source, boolean pageField, Boolean centered,
      String asciiFont, Integer sizeHalfPoints) {
    this(source, pageField, centered, asciiFont, sizeHalfPoints, List.of(),
        List.of(new RunFormatting(asciiFont, sizeHalfPoints)), FooterVariant.DEFAULT);
  }

  public PageNumberModel(SourceAddress source, boolean pageField, Boolean centered,
      String asciiFont, Integer sizeHalfPoints, List<Integer> applicableSectionIndexes) {
    this(source, pageField, centered, asciiFont, sizeHalfPoints, applicableSectionIndexes,
        List.of(new RunFormatting(asciiFont, sizeHalfPoints)), FooterVariant.DEFAULT);
  }

  public PageNumberModel(SourceAddress source, boolean pageField, Boolean centered,
      String asciiFont, Integer sizeHalfPoints, List<Integer> applicableSectionIndexes,
      List<RunFormatting> runFormatting) {
    this(source, pageField, centered, asciiFont, sizeHalfPoints, applicableSectionIndexes,
        runFormatting, FooterVariant.DEFAULT);
  }

  public PageNumberModel {
    applicableSectionIndexes = List.copyOf(applicableSectionIndexes);
    runFormatting = List.copyOf(runFormatting);
    if (footerVariant == null) throw new IllegalArgumentException("footer variant is required");
  }

  public enum FooterVariant { DEFAULT, FIRST, EVEN }

  public record RunFormatting(String asciiFont, Integer sizeHalfPoints) { }
}
