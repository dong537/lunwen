package cn.luwneshacha.thesis.document;

import java.util.List;
import java.util.Map;

/** XML evidence for one effective Word header part. */
public record HeaderModel(
    SourceAddress source,
    String text,
    Boolean centered,
    List<RunFormatting> runFormatting,
    Map<Type, List<Integer>> applicableSectionIndexesByType) {
  public HeaderModel {
    if (source == null) throw new IllegalArgumentException("header source is required");
    if (text == null) throw new IllegalArgumentException("header text is required");
    runFormatting = List.copyOf(runFormatting);
    var copiedSectionsByType = new java.util.EnumMap<Type, List<Integer>>(Type.class);
    applicableSectionIndexesByType.forEach((type, indexes) -> copiedSectionsByType.put(type, List.copyOf(indexes)));
    applicableSectionIndexesByType = Map.copyOf(copiedSectionsByType);
  }

  public HeaderModel(SourceAddress source, String text, Boolean centered, List<RunFormatting> runFormatting,
      List<Integer> applicableSectionIndexes) {
    this(source, text, centered, runFormatting,
        Map.of(Type.DEFAULT, List.copyOf(applicableSectionIndexes)));
  }

  public List<Integer> applicableSectionIndexes() {
    return applicableSectionIndexesByType.values().stream().flatMap(List::stream).distinct().sorted().toList();
  }

  public enum Type {
    DEFAULT("default"),
    FIRST("first"),
    EVEN("even");

    private final String xmlValue;

    Type(String xmlValue) {
      this.xmlValue = xmlValue;
    }

    public String xmlValue() {
      return xmlValue;
    }

    public static Type fromXml(String value) {
      for (Type type : values()) {
        if (type.xmlValue.equals(value)) return type;
      }
      throw new IllegalArgumentException("unsupported header type: " + value);
    }
  }

  public record RunFormatting(
      String asciiFont,
      String eastAsiaFont,
      Integer sizeHalfPoints,
      SourceAddress source) {
    public RunFormatting {
      if (source == null) throw new IllegalArgumentException("header run source is required");
    }
  }
}
