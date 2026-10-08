package cn.luwneshacha.thesis.document;

import java.util.Set;

public record EquationModel(
    SourceAddress source,
    String type,
    String summary,
    String number,
    boolean standalone,
    boolean needsReview,
    Set<String> nodeTypes,
    Set<String> fonts,
    Boolean italicVariables) {
  public EquationModel(SourceAddress source, String type, String summary, String number,
      boolean standalone, boolean needsReview) {
    this(source, type, summary, number, standalone, needsReview, Set.of(), Set.of(), null);
  }

  public EquationModel {
    nodeTypes = Set.copyOf(nodeTypes == null ? Set.of() : nodeTypes);
    fonts = Set.copyOf(fonts == null ? Set.of() : fonts);
  }
}
