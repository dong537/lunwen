package cn.luwneshacha.thesis.rules;

import cn.luwneshacha.thesis.document.ParsedDocument;
import cn.luwneshacha.thesis.document.ParagraphModel;
import cn.luwneshacha.thesis.document.SourceAddress;
import cn.luwneshacha.thesis.document.TableModel;
import cn.luwneshacha.thesis.document.TableFragmentEvidence;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class TableRuleChecker {
  private static final String UNIT = "(?:km/h|m/s|cm/s|mm/s|km·h⁻¹|m·s⁻¹|mm|cm|km|μm|nm|mL|L|kg|mg|g|ms|min|h|s|Hz|kHz|mV|V|mA|A|kW|W|kPa|MPa|Pa|mol|cd|K|N|J|C|F|Ω|S|Wb|T|H|lm|lx|Bq|Gy|Sv|kat|m|℃|°C|%)";
  private static final Pattern UNIT_LABEL = Pattern.compile(
      "(?:[（(]\\s*(" + UNIT + ")([·.]?(?:cm|mm|m|s|2|3|²|³))?\\s*[）)]|[/／]\\s*(" + UNIT
          + ")([·.]?(?:cm|mm|m|s|2|3|²|³))?(?![\\p{L}\\p{M}\\p{N}\\p{Pc}]))");
  private static final Pattern CELL_VALUE_UNIT = Pattern.compile(
      "[≤≥<>≈±+−-]*\\s*\\d+(?:\\.\\d+)?(?:\\s*[~～至-]\\s*\\d+(?:\\.\\d+)?)?\\s*(?:[（(]\\s*("
          + UNIT + "|ADC)\\s*[）)]|(" + UNIT + "|ADC))");
  private static final Pattern SHARED_UNIT_LABEL = Pattern.compile(
      "^(?:单位\\s*[:：]?\\s*)?(?:[（(]\\s*)?(" + UNIT + ")(?:\\s*[）)])?$");
  private static final Pattern CONTINUATION_CAPTION = Pattern.compile(
      "^续表(\\d+-\\d+|[A-Z]\\d+)$");
  private static final Pattern TABLE_CAPTION = Pattern.compile(
      "^\\s*表\\s*(\\d+-\\d+|[A-Z]\\d+)(?=$|[^\\p{IsLatin}\\p{N}-])",
      Pattern.CASE_INSENSITIVE);

  List<CheckResult> check(ParsedDocument document, TableModel table, RuleSet rules) {
    var results = new ArrayList<CheckResult>();
    TableModel headerOwner = table.fragmentEvidence() == null ? table : document.tables().stream()
        .filter(candidate -> candidate.source().equals(table.fragmentEvidence().originalSource()))
        .findFirst().orElse(table);
    List<String> headers = headerOwner.headerTexts();
    List<List<String>> rows = table.fragmentEvidence() != null
        && table.fragmentEvidence().kind() == TableFragmentEvidence.Kind.DATA_ROW
        ? List.of(table.headerTexts()) : table.dataRowTexts();
    boolean repeatedHeader = table.fragmentEvidence() != null
        && table.fragmentEvidence().kind() == TableFragmentEvidence.Kind.REPEATED_HEADER;
    if (!repeatedHeader && table.columns() > 0 && isQuantitativeTable(headerOwner)) {
      List<String> quantitativeHeaders = headers.stream()
          .filter(TableRuleChecker::isQuantitativeHeader).toList();
      String sharedUnit = sharedUnit(headerOwner);
      List<String> unverifiableUnits = quantitativeHeaders.stream()
          .filter(header -> dimensionForHeader(header) == null
              && UNIT_LABEL.matcher(header).find()).toList();
      List<String> missingUnits = quantitativeHeaders.stream()
          .filter(header -> !unverifiableUnits.contains(header)
              && !hasUnitLabel(header) && !sharedUnitMatchesHeader(sharedUnit, header)
              && !hasUnitInCells(headers, rows, header)).toList();
      if (!missingUnits.isEmpty())
        results.add(fail("TAB-04", "每个参数表头标明量和单位，或明确给出统一单位",
            String.join("、", missingUnits), "TABLE_UNITS_MISSING", table.source()));
      if (!unverifiableUnits.isEmpty())
        results.add(fail("TAB-04", "可识别的量名称和对应单位",
            String.join("、", unverifiableUnits), "TABLE_UNIT_DIMENSION_UNKNOWN", table.source()));
    }
    if (table.repeatedPlaceholderText()) {
      results.add(fail("TAB-06", "重复写出完整内容", "同前/同左",
          "TABLE_REPEATED_PLACEHOLDER", table.source()));
    }
    if (table.noteText() != null) {
      SourceAddress noteSource = table.noteSource() == null ? table.source() : table.noteSource();
      ParagraphModel note = table.noteSource() == null ? null : document.paragraphs().stream()
          .filter(paragraph -> paragraph.source().partName().equals(table.noteSource().partName())
              && paragraph.source().elementPath().equals(table.noteSource().elementPath()))
          .findFirst().orElse(null);
      if (note == null || note.alignment() == null) {
        results.add(fail("TAB-07", "left", null, "TABLE_NOTE_ALIGNMENT_UNKNOWN", noteSource));
      } else if (!"left".equals(note.alignment())) {
        results.add(fail("TAB-07", "left", note.alignment(), "TABLE_NOTE_NOT_LEFT_ALIGNED",
            noteSource));
      }
    }
    checkContinuationHeader(results, document, table);
    return List.copyOf(results);
  }

  private void checkContinuationHeader(List<CheckResult> results, ParsedDocument document,
      TableModel continuation) {
    Matcher continuationNumber = CONTINUATION_CAPTION.matcher(continuation.captionText() == null
        ? "" : continuation.captionText());
    if (!continuationNumber.matches()) return;
    String number = continuationNumber.group(1);
    List<TableModel> tables = document.tables();
    int continuationIndex = indexOfSource(tables, continuation.source());
    if (continuationIndex <= 0) {
      results.add(fail("TAB-08", "前文存在编号相同的原表", continuation.captionText(),
          "TABLE_CONTINUATION_ORIGINAL_MISSING",
          continuation.captionSource() == null ? continuation.source() : continuation.captionSource()));
      return;
    }
    TableModel adjacent = tables.get(continuationIndex - 1);
    TableModel original = adjacent;
    TableModel repeatedHeader = null;
    if (adjacent.fragmentEvidence() != null
        && adjacent.fragmentEvidence().continuationSource().equals(continuation.source())) {
      original = tables.stream().filter(candidate ->
          candidate.source().equals(adjacent.fragmentEvidence().originalSource()))
          .findFirst().orElse(adjacent);
      if (adjacent.fragmentEvidence().kind() == TableFragmentEvidence.Kind.REPEATED_HEADER)
        repeatedHeader = adjacent;
    }
    Matcher originalNumber = TABLE_CAPTION.matcher(original.captionText() == null ? "" : original.captionText());
    if (original.captionText() == null || original.captionText().stripLeading().startsWith("續表")
        || !originalNumber.find() || !number.equals(originalNumber.group(1))) {
      results.add(fail("TAB-08", "紧邻前文存在编号相同的原表", continuation.captionText(),
          "TABLE_CONTINUATION_ORIGINAL_MISSING",
          continuation.captionSource() == null ? continuation.source() : continuation.captionSource()));
      return;
    }
    if (original.headerTexts().isEmpty()) {
      results.add(fail("TAB-08", "可读取的原表和续列表头", "表头结构为空",
          "TABLE_CONTINUATION_HEADER_UNREADABLE",
          continuation.captionSource() == null ? continuation.source() : continuation.captionSource()));
      return;
    }
    List<String> originalHeader = original.headerTexts();
    List<String> continuationHeader = repeatedHeader == null
        ? continuation.headerTexts() : repeatedHeader.headerTexts();
    if (!originalHeader.equals(continuationHeader)) {
      results.add(fail("TAB-08", String.join(" | ", originalHeader),
          String.join(" | ", continuationHeader), "TABLE_CONTINUATION_HEADER_MISMATCH",
          continuation.captionSource() == null ? continuation.source() : continuation.captionSource()));
    }
  }

  private static int indexOfSource(List<TableModel> tables, SourceAddress source) {
    for (int index = 0; index < tables.size(); index++)
      if (tables.get(index).source().equals(source)) return index;
    return -1;
  }

  /**
   * 规范只要求量和单位可被识别；单位相同时可以集中写，单位不同时可以
   * 分别写在对应列的表头。不能把“没有统一单位标签”当成错误。
   */
  private static String sharedUnit(TableModel table) {
    List<String> units = table.headerTexts().stream()
        .map(header -> SHARED_UNIT_LABEL.matcher(header.trim()))
        .filter(Matcher::matches)
        .map(matcher -> matcher.group(1))
        .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    if (table.sharedUnitRightAligned()) {
      Matcher topUnit = SHARED_UNIT_LABEL.matcher(table.sharedUnitText() == null
          ? "" : table.sharedUnitText().trim());
      if (topUnit.matches()) units.add(topUnit.group(1));
    }
    return units.stream().distinct().count() == 1 ? units.get(0) : null;
  }

  private static boolean sharedUnitMatchesHeader(String unit, String header) {
    if (unit == null) return false;
    String headerDimension = dimensionForHeader(header);
    return headerDimension != null && headerDimension.equals(dimensionForUnit(unit));
  }

  private static String dimensionForHeader(String header) {
    if (header.matches(".*(质量分数|质量百分比|体积分数|体积百分比|合格率|成功率|百分比).*")) return "ratio";
    if (header.matches(".*(长度|宽度|高度|距离).*")) return "length";
    if (header.matches(".*面积.*")) return "area";
    if (header.matches(".*(温度).*")) return "temperature";
    if (header.matches(".*(时间|时长|周期).*")) return "time";
    if (header.matches(".*体积.*")) return "volume";
    if (header.matches(".*(频率).*")) return "frequency";
    if (header.matches(".*(电压).*")) return "voltage";
    if (header.matches(".*(电流).*")) return "current";
    if (header.matches(".*(功率).*")) return "power";
    if (header.matches(".*(压力).*")) return "pressure";
    if (header.matches(".*(重量|质量).*")) return "mass";
    if (header.matches(".*速度.*")) return "speed";
    if (header.matches(".*(物质的量|物质数量|摩尔).*")) return "amount";
    if (header.matches(".*发光强度.*")) return "luminous-intensity";
    if (header.matches(".*(力|载荷).*")) return "force";
    if (header.matches(".*(能量|热量|功).*")) return "energy";
    if (header.matches(".*(电荷量|电量).*")) return "electric-charge";
    if (header.matches(".*电容.*")) return "capacitance";
    if (header.matches(".*电阻.*")) return "resistance";
    if (header.matches(".*电导.*")) return "conductance";
    if (header.matches(".*磁通量.*")) return "magnetic-flux";
    if (header.matches(".*(磁感应强度|磁通密度).*")) return "magnetic-flux-density";
    if (header.matches(".*电感.*")) return "inductance";
    if (header.matches(".*光通量.*")) return "luminous-flux";
    if (header.matches(".*照度.*")) return "illuminance";
    if (header.matches(".*放射性活度.*")) return "radioactivity";
    if (header.matches(".*吸收剂量.*")) return "absorbed-dose";
    if (header.matches(".*当量剂量.*")) return "equivalent-dose";
    if (header.matches(".*催化活性.*")) return "catalytic-activity";
    return null;
  }

  private static String dimensionForUnit(String unit) {
    return switch (unit) {
      case "mm", "cm", "km", "m", "μm", "nm" -> "length";
      case "kg", "mg", "g" -> "mass";
      case "mL", "L" -> "volume";
      case "m/s", "cm/s", "mm/s", "km/h", "m·s⁻¹", "km·h⁻¹" -> "speed";
      case "ms", "min", "h", "s" -> "time";
      case "Hz", "kHz" -> "frequency";
      case "mV", "V" -> "voltage";
      case "mA", "A" -> "current";
      case "kW", "W" -> "power";
      case "kPa", "MPa", "Pa" -> "pressure";
      case "℃", "°C", "K" -> "temperature";
      case "%" -> "ratio";
      case "mol" -> "amount";
      case "cd" -> "luminous-intensity";
      case "N" -> "force";
      case "J" -> "energy";
      case "C" -> "electric-charge";
      case "F" -> "capacitance";
      case "Ω" -> "resistance";
      case "S" -> "conductance";
      case "Wb" -> "magnetic-flux";
      case "T" -> "magnetic-flux-density";
      case "H" -> "inductance";
      case "lm" -> "luminous-flux";
      case "lx" -> "illuminance";
      case "Bq" -> "radioactivity";
      case "Gy" -> "absorbed-dose";
      case "Sv" -> "equivalent-dose";
      case "kat" -> "catalytic-activity";
      default -> null;
    };
  }

  private static boolean hasUnitLabel(String header) {
    Matcher matcher = UNIT_LABEL.matcher(header);
    if (!matcher.find()) return false;
    boolean parenthesized = matcher.group(1) != null;
    String unit = parenthesized ? matcher.group(1) : matcher.group(3);
    String quantityDimension = dimensionForHeader(header);
    String unitDimension = dimensionForUnit(unit, parenthesized ? matcher.group(2) : matcher.group(4));
    return quantityDimension != null && quantityDimension.equals(unitDimension);
  }

  private static String dimensionForUnit(String unit, String suffix) {
    String baseDimension = dimensionForUnit(unit);
    if (suffix == null) return baseDimension;
    if (suffix.matches("[.]?(?:2|²)")) {
      if ("length".equals(baseDimension)) return "area";
      if ("time".equals(baseDimension)) return "time-squared";
    }
    if (suffix.matches("[.]?(?:3|³)") && "length".equals(baseDimension)) return "volume";
    return null;
  }

  private static boolean isQuantitativeHeader(String header) {
    return header.matches(".*(数量|数值|测量|误差|温度|压力|速度|长度|宽度|高度|时间|频率|电压|电流|功率|合格率|成功率|百分比|重量|质量|面积|体积).*" );
  }

  private static boolean hasUnitInCells(List<String> headers, List<List<String>> rows, String header) {
    if (rows.isEmpty() || UNIT_LABEL.matcher(header).find()) return false;
    int column = headers.indexOf(header);
    if (column < 0 || headers.lastIndexOf(header) != column) return false;
    String dimension = dimensionForHeader(header);
    boolean foundMeasurement = false;
    for (List<String> row : rows) {
      if (column >= row.size()) return false;
      String value = row.get(column).strip();
      if (value.isEmpty() || value.matches("[—–-]+")) continue;
      Matcher measurement = CELL_VALUE_UNIT.matcher(value);
      if (!measurement.matches()) return false;
      String unit = measurement.group(1) == null ? measurement.group(2) : measurement.group(1);
      if (dimension != null && !dimension.equals(dimensionForUnit(unit))) return false;
      foundMeasurement = true;
    }
    return foundMeasurement;
  }

  private static boolean isQuantitativeTable(TableModel table) {
    if (table.headerTexts().isEmpty()) return false;
    if (isSchemaTable(table)) return false;
    return table.headerTexts().stream().anyMatch(TableRuleChecker::isQuantitativeHeader);
  }

  private static boolean isSchemaTable(TableModel table) {
    List<String> headers = table.headerTexts().stream()
        .map(TableRuleChecker::compactWhitespace).toList();
    // Database/entity dictionaries describe fields, types and storage lengths;
    // their "length" column is not a measured quantity and must not trigger
    // the parameter-table unit rule.
    boolean hasField = headers.stream().anyMatch(header ->
        header.contains("字段名称") || header.equals("字段名") || header.contains("字段"));
    boolean hasType = headers.stream().anyMatch(header ->
        header.equals("类型") || header.contains("字段类型"));
    boolean hasLength = headers.contains("长度") || headers.contains("字段长度");
    boolean hasOtherQuantity = headers.stream()
        .anyMatch(header -> isQuantitativeHeader(header)
            && !"长度".equals(header) && !"字段长度".equals(header));
    return hasField && hasType && hasLength && !hasOtherQuantity;
  }

  private static String compactWhitespace(String text) {
    return text.codePoints()
        .filter(codePoint -> !Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint))
        .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
        .toString();
  }

  private static CheckResult fail(String ruleId, String expected, String actual,
      String reason, SourceAddress source) {
    return new CheckResult(ruleId, Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, expected, actual, reason, source);
  }

}
