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
    Boolean italicVariables,
    SourceAddress numberSource,
    String variableExplanation,
    Set<String> variables,
    boolean variableSymbolsResolved,
    EquationNumberEvidence numberEvidence,
    Boolean numberRightAligned,
    EquationLayoutEvidence layoutEvidence,
    EquationObjectEvidence objectEvidence,
    java.util.List<EquationVariable> structuredVariables) {
  public EquationModel(SourceAddress source, String type, String summary, String number,
      boolean standalone, boolean needsReview) {
    this(source, type, summary, number, standalone, needsReview, Set.of(), Set.of(), null, null, null,
        Set.of(), false, null, null, null, null, java.util.List.of());
  }

  public EquationModel(SourceAddress source, String type, String summary, String number,
      boolean standalone, boolean needsReview, Set<String> nodeTypes, Set<String> fonts,
      Boolean italicVariables) {
    this(source, type, summary, number, standalone, needsReview, nodeTypes, fonts, italicVariables, null, null,
        Set.of(), false, null, null, null, null, java.util.List.of());
  }

  public EquationModel(SourceAddress source, String type, String summary, String number,
      boolean standalone, boolean needsReview, Set<String> nodeTypes, Set<String> fonts,
      Boolean italicVariables, SourceAddress numberSource) {
    this(source, type, summary, number, standalone, needsReview, nodeTypes, fonts, italicVariables,
        numberSource, null, Set.of(), false, null, null, null, null, java.util.List.of());
  }

  public EquationModel(SourceAddress source, String type, String summary, String number,
      boolean standalone, boolean needsReview, Set<String> nodeTypes, Set<String> fonts,
      Boolean italicVariables, SourceAddress numberSource, String variableExplanation) {
    this(source, type, summary, number, standalone, needsReview, nodeTypes, fonts, italicVariables,
        numberSource, variableExplanation, Set.of(), false, null, null, null, null, java.util.List.of());
  }

  public EquationModel(SourceAddress source, String type, String summary, String number,
      boolean standalone, boolean needsReview, Set<String> nodeTypes, Set<String> fonts,
      Boolean italicVariables, SourceAddress numberSource, String variableExplanation,
      Set<String> variables, boolean variableSymbolsResolved) {
    this(source, type, summary, number, standalone, needsReview, nodeTypes, fonts, italicVariables,
        numberSource, variableExplanation, variables, variableSymbolsResolved, null, null, null, null, java.util.List.of());
  }

  public EquationModel(SourceAddress source, String type, String summary, String number,
      boolean standalone, boolean needsReview, Set<String> nodeTypes, Set<String> fonts,
      Boolean italicVariables, SourceAddress numberSource, String variableExplanation,
      Set<String> variables, boolean variableSymbolsResolved, EquationNumberEvidence numberEvidence) {
    this(source, type, summary, number, standalone, needsReview, nodeTypes, fonts, italicVariables,
        numberSource, variableExplanation, variables, variableSymbolsResolved, numberEvidence, null, null, null,
        java.util.List.of());
  }

  public EquationModel(SourceAddress source, String type, String summary, String number,
      boolean standalone, boolean needsReview, Set<String> nodeTypes, Set<String> fonts,
      Boolean italicVariables, SourceAddress numberSource, String variableExplanation,
      Set<String> variables, boolean variableSymbolsResolved, EquationNumberEvidence numberEvidence,
      Boolean numberRightAligned) {
    this(source, type, summary, number, standalone, needsReview, nodeTypes, fonts, italicVariables,
        numberSource, variableExplanation, variables, variableSymbolsResolved, numberEvidence,
        numberRightAligned, null, null, java.util.List.of());
  }

  public EquationModel {
    nodeTypes = Set.copyOf(nodeTypes == null ? Set.of() : nodeTypes);
    fonts = Set.copyOf(fonts == null ? Set.of() : fonts);
    variables = Set.copyOf(variables == null ? Set.of() : variables);
    structuredVariables = java.util.List.copyOf(structuredVariables == null ? java.util.List.of() : structuredVariables);
  }
}
