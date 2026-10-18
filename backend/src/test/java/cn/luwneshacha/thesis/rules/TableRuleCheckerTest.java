package cn.luwneshacha.thesis.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.luwneshacha.thesis.document.ParsedDocument;
import cn.luwneshacha.thesis.document.ParagraphModel;
import cn.luwneshacha.thesis.document.RunModel;
import cn.luwneshacha.thesis.document.SourceAddress;
import cn.luwneshacha.thesis.document.TableModel;
import java.util.List;
import org.junit.jupiter.api.Test;

class TableRuleCheckerTest {
  @Test
  void readsUnitsFromIndividualDataCellsWithoutRequiringUnifiedHeaderUnits() {
    for (var rows : List.of(
        List.of(List.of("传感器类型", "误差/精度"), List.of("水温", "≤±0.4℃"), List.of("浊度", "—")),
        List.of(List.of("温度", "时间"), List.of("25℃", "2s"), List.of("26℃", "3s")),
        List.of(List.of("测试项", "成功率"), List.of("显示", "100%"), List.of("按键", "99%")))) {
      assertTrue(check(parsedTable(rows)).stream().noneMatch(r -> r.ruleId().equals("TAB-04")), rows.toString());
    }
  }

  @Test
  void usesMeasurementValuesAfterMergedDescriptionRowForUnitValidation() {
    String xml = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:tbl>"
        + "<w:tr><w:tc><w:tcPr><w:gridSpan w:val=\"2\"/></w:tcPr><w:p><w:r><w:t>\u6D4B\u8BD5\u7528\u4F8B\u7F16\u53F7\uFF1ATC-01\uFF1B\u6A21\u5757\u540D\u79F0\uFF1A\u957F\u5EA6\u6D4B\u8BD5</w:t></w:r></w:p></w:tc></w:tr>"
        + "<w:tr><w:tc><w:p><w:r><w:t>\u957F\u5EA6</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>\u65F6\u95F4</w:t></w:r></w:p></w:tc></w:tr>"
        + "<w:tr><w:tc><w:p><w:r><w:t>25mm</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>2s</w:t></w:r></w:p></w:tc></w:tr>"
        + "</w:tbl></w:body></w:document>";
    TableModel table = new cn.luwneshacha.thesis.document.TableParser().parse(
        xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)).get(0);

    assertEquals(List.of("\u957F\u5EA6", "\u65F6\u95F4"), table.headerTexts());
    assertEquals(List.of(List.of("25mm", "2s")), table.dataRowTexts());
    assertTrue(check(table).stream().noneMatch(item -> item.ruleId().equals("TAB-04")));
  }

  @Test
  void cellUnitsDoNotHideMissingUnitsOrWrongDimensionsInOtherCells() {
    for (var rows : List.of(
        List.of(List.of("温度", "时间"), List.of("25℃", "2")),
        List.of(List.of("温度"), List.of("25℃"), List.of("26")),
        List.of(List.of("温度"), List.of("25mm")),
        List.of(List.of("误差"), List.of("—"), List.of("10")))) {
      assertTrue(check(parsedTable(rows)).stream().anyMatch(r -> r.ruleId().equals("TAB-04")), rows.toString());
    }
  }

  private static TableModel parsedTable(List<List<String>> rows) {
    StringBuilder xml = new StringBuilder("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:tbl>");
    for (var row : rows) {
      xml.append("<w:tr>");
      for (String cell : row) xml.append("<w:tc><w:p><w:r><w:t>").append(cell)
          .append("</w:t></w:r></w:p></w:tc>");
      xml.append("</w:tr>");
    }
    xml.append("</w:tbl></w:body></w:document>");
    return new cn.luwneshacha.thesis.document.TableParser().parse(
        xml.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)).get(0);
  }

  @Test
  void doesNotTreatOneLabeledQuantityColumnAsProofThatAllColumnsHaveUnits() {
    TableModel table = table(List.of("温度（℃）", "时间"));

    CheckResult result = check(table).stream()
        .filter(item -> item.ruleId().equals("TAB-04")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("TABLE_UNITS_MISSING", result.reasonCode());
    assertTrue(result.actual().contains("时间"));
  }

  @Test
  void acceptsDifferentUnitsWhenEachQuantityColumnNamesItsOwnUnit() {
    TableModel table = table(List.of("温度（℃）", "时间（s）"));

    assertTrue(check(table).stream().noneMatch(item -> item.ruleId().equals("TAB-04")));
  }

  @Test
  void acceptsACompleteSlashDelimitedUnitToken() {
    TableModel table = table(List.of("长度/mm"));

    assertTrue(check(table).stream().noneMatch(item -> item.ruleId().equals("TAB-04")));
  }

  @Test
  void rejectsUnitSymbolThatIsOnlyAPrefixOfAnInvalidSlashSuffix() {
    TableModel table = table(List.of("长度/mmeter"));

    List<CheckResult> findings = check(table).stream()
        .filter(item -> item.ruleId().equals("TAB-04")).toList();

    assertEquals(1, findings.size());
    CheckResult result = findings.get(0);
    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("TABLE_UNITS_MISSING", result.reasonCode());
  }

  @Test
  void rejectsSlashUnitFollowedByIdentifierPunctuation() {
    TableModel table = table(List.of("长度/mm_extra"));

    CheckResult result = check(table).stream()
        .filter(item -> item.ruleId().equals("TAB-04")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("TABLE_UNITS_MISSING", result.reasonCode());
  }

  @Test
  void acceptsAnExplicitSharedUnitForAllQuantityColumns() {
    TableModel table = table(List.of("长度", "宽度", "单位：mm"));

    assertTrue(check(table).stream().noneMatch(item -> item.ruleId().equals("TAB-04")));
  }

  @Test
  void rejectsAnExplicitUnitWithTheWrongQuantityDimension() {
    TableModel table = table(List.of("温度（mm）"));

    CheckResult result = check(table).stream()
        .filter(item -> item.ruleId().equals("TAB-04")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("TABLE_UNITS_MISSING", result.reasonCode());
    assertTrue(result.actual().contains("温度（mm）"));
  }

  @Test
  void rejectsLengthUnitForVolumeInsteadOfAcceptingAnyRecognizedUnit() {
    TableModel table = table(List.of("体积（mm）"));

    CheckResult result = check(table).stream()
        .filter(item -> item.ruleId().equals("TAB-04")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("TABLE_UNITS_MISSING", result.reasonCode());
  }

  @Test
  void acceptsCubicLengthUnitForVolumeAndRejectsItForArea() {
    TableModel volume = table(List.of("体积（m³）"));
    TableModel area = table(List.of("面积（m³）"));

    assertTrue(check(volume).stream().noneMatch(item -> item.ruleId().equals("TAB-04")));
    assertTrue(check(area).stream().anyMatch(item -> item.ruleId().equals("TAB-04")
        && item.judgement() == Judgement.FAIL));
  }

  @Test
  void acceptsLiterUnitsForVolume() {
    TableModel table = table(List.of("体积（mL）"));

    assertTrue(check(table).stream().noneMatch(item -> item.ruleId().equals("TAB-04")));
  }

  @Test
  void classifiesMassAndVolumeFractionsAsRatiosBeforeTheirBaseQuantities() {
    TableModel massFraction = table(List.of("质量分数（%）"));
    TableModel volumeFraction = table(List.of("体积分数（%）"));

    assertTrue(check(massFraction).stream().noneMatch(item -> item.ruleId().equals("TAB-04")));
    assertTrue(check(volumeFraction).stream().noneMatch(item -> item.ruleId().equals("TAB-04")));
  }

  @Test
  void rejectsLengthUnitForSpeedAndAcceptsCompleteSpeedUnits() {
    TableModel invalid = table(List.of("速度（mm）"));
    TableModel metersPerSecond = table(List.of("速度（m/s）"));
    TableModel kilometersPerHour = table(List.of("速度（km/h）"));
    TableModel invalidSuffix = table(List.of("速度（m/speed）"));

    assertTrue(check(invalid).stream().anyMatch(item -> item.ruleId().equals("TAB-04")
        && item.judgement() == Judgement.FAIL));
    assertTrue(check(metersPerSecond).stream().noneMatch(item -> item.ruleId().equals("TAB-04")));
    assertTrue(check(kilometersPerHour).stream().noneMatch(item -> item.ruleId().equals("TAB-04")));
    assertTrue(check(invalidSuffix).stream().anyMatch(item -> item.ruleId().equals("TAB-04")
        && item.judgement() == Judgement.FAIL));
  }

  @Test
  void rejectsAnExponentUnitWhenItsFullDimensionDoesNotMatchTheQuantity() {
    TableModel table = table(List.of("长度/mm²"));

    CheckResult result = check(table).stream()
        .filter(item -> item.ruleId().equals("TAB-04")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("TABLE_UNITS_MISSING", result.reasonCode());
  }

  @Test
  void acceptsAnAreaUnitWhenItsExponentMatchesTheQuantity() {
    TableModel table = table(List.of("面积/mm²"));

    assertTrue(check(table).stream().noneMatch(item -> item.ruleId().equals("TAB-04")));
  }

  @Test
  void doesNotApplySharedUnitToAQuantityWithAnIncompatibleDimension() {
    TableModel table = table(List.of("长度", "温度", "单位：mm"));

    CheckResult result = check(table).stream()
        .filter(item -> item.ruleId().equals("TAB-04")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("TABLE_UNITS_MISSING", result.reasonCode());
    assertTrue(result.actual().contains("温度"));
  }

  @Test
  void doesNotInferAnUnknownQuantityDimensionFromSharedUnit() {
    TableModel table = table(List.of("长度", "数值", "单位：mm"));

    CheckResult result = check(table).stream()
        .filter(item -> item.ruleId().equals("TAB-04")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertTrue(result.actual().contains("数值"));
  }

  @Test
  void doesNotTreatAnUnknownQuantityWithARecognizedUnitAsVerified() {
    TableModel table = table(List.of("数值（mm）"));

    CheckResult result = check(table).stream()
        .filter(item -> item.ruleId().equals("TAB-04")).findFirst().orElse(null);

    assertTrue(result != null);
    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("TABLE_UNIT_DIMENSION_UNKNOWN", result.reasonCode());
  }

  @Test
  void doesNotChooseBetweenConflictingSharedUnitLabels() {
    TableModel table = table(List.of("长度", "单位：mm", "单位：cm"));

    CheckResult result = check(table).stream()
        .filter(item -> item.ruleId().equals("TAB-04")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertTrue(result.actual().contains("长度"));
  }

  @Test
  void acceptsSharedUnitOnlyWhenXmlPlacesItOnTheRightOfTheTableTopLine() {
    TableModel table = table(List.of("长度", "宽度"), "单位：mm", true);

    assertTrue(check(table).stream().noneMatch(item -> item.ruleId().equals("TAB-04")));
  }

  @Test
  void acceptsBareUnitAtTheRightOfTheTableTopLine() {
    TableModel table = table(List.of("长度", "宽度"), "mm", true);

    assertTrue(check(table).stream().noneMatch(item -> item.ruleId().equals("TAB-04")));
  }

  @Test
  void acceptsParenthesizedUnitAtTheRightOfTheTableTopLine() {
    TableModel table = table(List.of("长度", "宽度"), "（mm）", true);

    assertTrue(check(table).stream().noneMatch(item -> item.ruleId().equals("TAB-04")));
  }

  @Test
  void recognizesAdditionalSiBaseUnitsInTableHeaders() {
    for (String header : List.of("温度（K）", "物质的量（mol）", "发光强度（cd）"))
      assertTrue(check(table(List.of(header))).stream().noneMatch(item -> item.ruleId().equals("TAB-04")), header);
  }

  @Test
  void rejectsSharedUnitTextWhenXmlDoesNotPlaceItOnTheRight() {
    TableModel table = table(List.of("长度", "宽度"), "单位：mm", false);

    CheckResult result = check(table).stream()
        .filter(item -> item.ruleId().equals("TAB-04")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("TABLE_UNITS_MISSING", result.reasonCode());
  }

  @Test
  void failsTableNoteWhenAlignmentCannotBeReadFromItsParagraph() {
    var noteSource = new SourceAddress("word/document.xml", List.of(0, 2), 0, 6);
    var note = new ParagraphModel(List.of(new RunModel("注：说明", null, null, null, 21,
        false, noteSource)), null, null, null, null, null, noteSource, null);
    var table = new TableModel(new SourceAddress("word/document.xml", List.of(0, 1), 0, 0), null,
        "表1-1 数据", 2, 2, true, true, true, false, true, false, false, List.of(), List.of(),
        List.of(), "注：说明", noteSource, false, false);
    var document = new ParsedDocument("source", List.of(note), List.of(), List.of(),
        List.of(table), List.of(), List.of(), List.of());

    var result = new TableRuleChecker().check(document, table, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("TAB-07")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("TABLE_NOTE_ALIGNMENT_UNKNOWN", result.reasonCode());
  }

  @Test
  void checksUnitsForSingleRowTablesWithQuantitativeHeaders() {
    var source = new SourceAddress("word/document.xml", List.of(0, 1), 0, 0);
    var table = new TableModel(source, null, "表1-1 参数", 1, 1, true, true, true,
        false, true, false, false, List.of(), List.of(), List.of("温度"), null, null, false);

    var result = check(table).stream()
        .filter(item -> item.ruleId().equals("TAB-04")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("TABLE_UNITS_MISSING", result.reasonCode());
    assertTrue(result.actual().contains("温度"));
  }

  @Test
  void rejectsContinuationTableWithoutRepeatedHeader() {
    TableModel original = table(new SourceAddress("word/document.xml", List.of(0, 1), 0, 0),
        "表1-1 参数", List.of("名称", "数值"));
    TableModel continuation = table(new SourceAddress("word/document.xml", List.of(0, 3), 0, 0),
        "续表1-1", List.of());
    var document = new ParsedDocument("source", List.of(), List.of(), List.of(),
        List.of(original, continuation), List.of(), List.of(), List.of());

    CheckResult result = new TableRuleChecker().check(document, continuation, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("TAB-08")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("TABLE_CONTINUATION_HEADER_MISMATCH", result.reasonCode());
  }

  @Test
  void rejectsMismatchedHeaderInOneRowContinuationTable() {
    TableModel original = table(new SourceAddress("word/document.xml", List.of(0, 1), 0, 0),
        "表1-1 参数", List.of("名称", "数值"));
    TableModel twoRowContinuation = table(new SourceAddress("word/document.xml", List.of(0, 3), 0, 0),
        "续表1-1", List.of("名称", "数值"));
    TableModel continuation = new TableModel(twoRowContinuation.source(), twoRowContinuation.captionSource(),
        twoRowContinuation.captionText(), 1, twoRowContinuation.columns(), twoRowContinuation.topBorder(),
        twoRowContinuation.middleBorder(), twoRowContinuation.bottomBorder(), twoRowContinuation.verticalBorders(),
        twoRowContinuation.captionBefore(), twoRowContinuation.layoutNeedsReview(), twoRowContinuation.mergedCells(),
        twoRowContinuation.columnWidthsTwips(), twoRowContinuation.rowHeightsTwips(), List.of("名称", "不同"),
        twoRowContinuation.noteText(), twoRowContinuation.noteSource(), twoRowContinuation.repeatedPlaceholderText(),
        twoRowContinuation.hasTableStyle(), twoRowContinuation.sharedUnitText(),
        twoRowContinuation.sharedUnitRightAligned(), twoRowContinuation.tableStyleBorderEvidenceResolved());
    var document = new ParsedDocument("source", List.of(), List.of(), List.of(),
        List.of(original, continuation), List.of(), List.of(), List.of());

    CheckResult result = new TableRuleChecker().check(document, continuation, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("TAB-08")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("TABLE_CONTINUATION_HEADER_MISMATCH", result.reasonCode());
  }

  @Test
  void acceptsContinuationWhenWordStoresRepeatedHeaderAsSeparateOneRowTable() {
    SourceAddress originalSource = new SourceAddress("word/document.xml", List.of(0, 1), 0, 0);
    SourceAddress repeatedHeaderSource = new SourceAddress("word/document.xml", List.of(0, 3), 0, 0);
    SourceAddress continuationSource = new SourceAddress("word/document.xml", List.of(0, 5), 0, 0);
    TableModel original = tableWithRows(originalSource, "表3-14 数据", 6, List.of("项目", "内容"));
    TableModel repeatedHeader = tableWithRows(repeatedHeaderSource, "表3-14 数据", 1,
        List.of("项目", "内容"));
    TableModel continuation = tableWithRows(continuationSource, "续表3-14", 7,
        List.of("后置条件", "数据可被查询与展示"));
    var document = new ParsedDocument("source", List.of(), List.of(), List.of(),
        List.of(original, repeatedHeader, continuation), List.of(), List.of(), List.of());

    assertTrue(new TableRuleChecker().check(document, continuation, RuleSet.schoolV1()).stream()
        .noneMatch(item -> item.ruleId().equals("TAB-08")));
  }

  @Test
  void rejectsContinuationWhenItsOnlyMatchingOriginalTableAppearsLater() {
    SourceAddress continuationSource = new SourceAddress("word/document.xml", List.of(0, 3), 0, 0);
    SourceAddress originalSource = new SourceAddress("word/document.xml", List.of(0, 5), 0, 0);
    TableModel continuation = tableWithRows(continuationSource, "续表3-6", 2,
        List.of("字段", "内容"));
    TableModel original = tableWithRows(originalSource, "表3-6 数据", 6,
        List.of("字段", "内容"));
    var document = new ParsedDocument("source", List.of(), List.of(), List.of(),
        List.of(continuation, original), List.of(), List.of(), List.of());

    CheckResult result = new TableRuleChecker().check(document, continuation, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("TAB-08")).findFirst().orElseThrow();

    assertEquals("TABLE_CONTINUATION_ORIGINAL_MISSING", result.reasonCode());
  }

  @Test
  void doesNotBorrowMatchingHeaderFromAnUnrelatedSingleRowTable() {
    TableModel original = tableWithRows(new SourceAddress("word/document.xml", List.of(0, 1), 0, 0),
        "表3-6 数据", 6, List.of("字段", "内容"));
    TableModel unrelated = tableWithRows(new SourceAddress("word/document.xml", List.of(0, 3), 0, 0),
        "表4-1 其他信息", 1, List.of("字段", "内容"));
    TableModel continuation = tableWithRows(new SourceAddress("word/document.xml", List.of(0, 5), 0, 0),
        "续表3-6", 2, List.of("后续数据", "内容"));
    var document = new ParsedDocument("source", List.of(), List.of(), List.of(),
        List.of(original, unrelated, continuation), List.of(), List.of(), List.of());

    CheckResult result = new TableRuleChecker().check(document, continuation, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("TAB-08")).findFirst().orElseThrow();

    assertEquals("TABLE_CONTINUATION_ORIGINAL_MISSING", result.reasonCode());
  }

  @Test
  void rejectsContinuationCaptionWithoutAnEarlierOriginalTable() {
    TableModel continuation = table(new SourceAddress("word/document.xml", List.of(0, 1), 0, 0),
        "续表1-1", List.of("名称", "数值"));
    var document = new ParsedDocument("source", List.of(), List.of(), List.of(),
        List.of(continuation), List.of(), List.of(), List.of());

    CheckResult result = new TableRuleChecker().check(document, continuation, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("TAB-08")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("TABLE_CONTINUATION_ORIGINAL_MISSING", result.reasonCode());
  }

  @Test
  void doesNotAssociateContinuationWithOriginalCaptionWhoseNumberHasALatinSuffix() {
    TableModel malformedOriginal = table(new SourceAddress("word/document.xml", List.of(0, 1), 0, 0),
        "表1-1a 参数", List.of("名称", "数值"));
    TableModel continuation = table(new SourceAddress("word/document.xml", List.of(0, 3), 0, 0),
        "续表1-1", List.of("名称", "数值"));
    var document = new ParsedDocument("source", List.of(), List.of(), List.of(),
        List.of(malformedOriginal, continuation), List.of(), List.of(), List.of());

    CheckResult result = new TableRuleChecker().check(document, continuation, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("TAB-08")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("TABLE_CONTINUATION_ORIGINAL_MISSING", result.reasonCode());
  }

  @Test
  void doesNotAssociateMalformedContinuationNumberWithItsNumericPrefix() {
    TableModel original = table(new SourceAddress("word/document.xml", List.of(0, 1), 0, 0),
        "表1-1 参数", List.of("名称", "数值"));
    TableModel continuation = table(new SourceAddress("word/document.xml", List.of(0, 3), 0, 0),
        "续表1-1-0", List.of("不同", "表头"));
    var document = new ParsedDocument("source", List.of(), List.of(), List.of(),
        List.of(original, continuation), List.of(), List.of(), List.of());

    List<CheckResult> results = new TableRuleChecker().check(document, continuation, RuleSet.schoolV1());

    assertTrue(results.stream().noneMatch(item -> item.ruleId().equals("TAB-08")));
  }

  @Test
  void recognizesSchemaTableHeadersSeparatedByIdeographicSpace() {
    TableModel table = table(List.of("字段　名称", "类型", "长度"));

    assertTrue(check(table).stream().noneMatch(item -> item.ruleId().equals("TAB-04")));
  }

  @Test
  void recognizesDatabaseSchemaTableWithFieldTypeHeader() {
    TableModel table = table(List.of("字段名称", "字段类型", "字段长度", "字段注释"));

    assertTrue(check(table).stream().noneMatch(item -> item.ruleId().equals("TAB-04")));
  }

  @Test
  void doesNotLetSchemaLikeHeadersSuppressUnitsForAdditionalQuantityColumns() {
    TableModel table = table(List.of("字段名称", "类型", "长度", "温度"));

    CheckResult result = check(table).stream()
        .filter(item -> item.ruleId().equals("TAB-04")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("TABLE_UNITS_MISSING", result.reasonCode());
    assertTrue(result.actual().contains("长度"));
    assertTrue(result.actual().contains("温度"));
  }

  private static List<CheckResult> check(TableModel table) {
    return new TableRuleChecker().check(new ParsedDocument("source", List.of(), List.of(), List.of(),
        List.of(table), List.of(), List.of(), List.of()), table, RuleSet.schoolV1());
  }

  private static TableModel table(List<String> headers) {
    return table(headers, null, false);
  }

  private static TableModel table(List<String> headers, String sharedUnit, boolean rightAligned) {
    var source = new SourceAddress("word/document.xml", List.of(0, 1), 0, 0);
    return table(source, "表1-1 参数", headers, sharedUnit, rightAligned);
  }

  private static TableModel table(SourceAddress source, String caption, List<String> headers) {
    return table(source, caption, headers, null, false);
  }

  private static TableModel table(SourceAddress source, String caption, List<String> headers,
      String sharedUnit, boolean rightAligned) {
    return tableWithRows(source, caption, 2, headers, sharedUnit, rightAligned);
  }

  private static TableModel tableWithRows(SourceAddress source, String caption, int rows,
      List<String> headers) {
    return tableWithRows(source, caption, rows, headers, null, false);
  }

  private static TableModel tableWithRows(SourceAddress source, String caption, int rows,
      List<String> headers, String sharedUnit, boolean rightAligned) {
    return new TableModel(source, null, caption, rows, headers.size(), true, true, true,
        false, true, false, false, List.of(), List.of(), headers, null, null, false, false,
        sharedUnit, rightAligned);
  }
}
