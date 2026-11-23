package cn.luwneshacha.thesis.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.luwneshacha.thesis.document.EquationLayoutEvidence;
import cn.luwneshacha.thesis.document.EquationModel;
import cn.luwneshacha.thesis.document.EquationNumberEvidence;
import cn.luwneshacha.thesis.document.EquationObjectEvidence;
import cn.luwneshacha.thesis.document.EquationParser;
import cn.luwneshacha.thesis.document.Docx4jDocumentParser;
import cn.luwneshacha.thesis.document.DocxReader;
import cn.luwneshacha.thesis.document.ImageModel;
import cn.luwneshacha.thesis.document.ParagraphModel;
import cn.luwneshacha.thesis.document.ParsedDocument;
import cn.luwneshacha.thesis.document.RunModel;
import cn.luwneshacha.thesis.document.SourceAddress;
import cn.luwneshacha.thesis.document.TableModel;
import java.util.List;
import java.util.Set;
import java.nio.file.Path;
import org.docx4j.XmlUtils;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.wml.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EquationRuleModuleExecutionTest {
  private static final String MODULE = "EquationRuleModule";

  @Test
  void recordsAllActualFormulaChecksWhenOmmlAndLayoutEvidenceIsComplete() {
    var source = address(1, 0);
    var numberSource = address(1);
    var equation = new EquationModel(source, "OMML", "x", "1-1", true, false,
        Set.of("oMath"), Set.of(), null, numberSource, null, Set.of(), true,
        new EquationNumberEvidence("(1-1)", true, "Times New Roman", 21, numberSource),
        true, new EquationLayoutEvidence("OMML_NUMBER_ARRAY", true, true, true, numberSource),
        EquationObjectEvidence.omml(), List.of());
    var document = new ParsedDocument("source", List.of(
        paragraph("第一章 绪论", 0), paragraph("(1-1)", 1), paragraph("由式(1-1)可知。", 2)),
        List.of(), List.of(), List.of(), List.of(equation), List.of());

    var module = new EquationRuleModule();
    var evaluation = module.evaluate(document, RuleSet.schoolV1());

    assertEquals(module.check(document, RuleSet.schoolV1()), evaluation.results());
    for (String id : List.of("EQ-01", "EQ-02", "EQ-03", "EQ-04", "EQ-05", "EQ-06", "EQ-07")) {
      assertEquals(new RuleRun(id, MODULE, 1, 1, 0, null), run(evaluation, id), id);
    }
  }

  @Test
  void keepsUnreadableExternalFormulaAndUnlocatedCitationUnknown() {
    var equation = new EquationModel(address(1, 0), "OLE", "embedded equation", "1-1",
        true, true, Set.of(), Set.of(), null, null, null, Set.of(), false,
        null, null, null, EquationObjectEvidence.external("OLE", "Equation.3"), List.of());
    var document = new ParsedDocument("source", List.of(paragraph("(1-1)", 1)),
        List.of(), List.of(), List.of(), List.of(equation), List.of());

    var evaluation = new EquationRuleModule().evaluate(document, RuleSet.schoolV1());

    for (String id : List.of("EQ-01", "EQ-03", "EQ-04", "EQ-05", "EQ-06", "EQ-07")) {
      assertEquals(new RuleRun(id, MODULE, 1, 0, 1, null), run(evaluation, id), id);
    }
    // The separate visible number run can still prove its own font and size.
    assertEquals(new RuleRun("EQ-02", MODULE, 1, 1, 0, null), run(evaluation, "EQ-02"));
    assertTrue(evaluation.results().stream().anyMatch(result -> "EQ-06".equals(result.ruleId())));
  }

  @Test
  void externalFormulaCanStillProveItsVisibleNumberButNotItsInternalStructure() {
    var source = address(1, 0);
    var numberSource = address(1);
    var equation = new EquationModel(source, "OLE", "embedded equation", "1-1", true, true,
        Set.of(), Set.of(), null, numberSource, null, Set.of(), false,
        new EquationNumberEvidence("(1-1)", true, "Times New Roman", 21, numberSource),
        true, new EquationLayoutEvidence("BORDERLESS_TABLE", true, true, true, numberSource),
        EquationObjectEvidence.external("OLE", "Equation.3"), List.of());
    var document = new ParsedDocument("source", List.of(
        paragraph("第一章 绪论", 0), paragraph("(1-1)", 1), paragraph("由式(1-1)可知。", 2)),
        List.of(), List.of(), List.of(), List.of(equation), List.of());

    var evaluation = new EquationRuleModule().evaluate(document, RuleSet.schoolV1());

    for (String id : List.of("EQ-01", "EQ-02", "EQ-03", "EQ-04", "EQ-05"))
      assertEquals(new RuleRun(id, MODULE, 1, 1, 0, null), run(evaluation, id), id);
    for (String id : List.of("EQ-06", "EQ-07"))
      assertEquals(new RuleRun(id, MODULE, 1, 0, 1, null), run(evaluation, id), id);
  }

  @Test
  void inlineFormulaDoesNotInventStandaloneNumberAndCitationCandidates() {
    var equation = new EquationModel(address(1, 0), "OMML", "x", null, false, false);
    var document = new ParsedDocument("source", List.of(paragraph("正文中的公式", 1)),
        List.of(), List.of(), List.of(), List.of(equation), List.of());

    var evaluation = new EquationRuleModule().evaluate(document, RuleSet.schoolV1());

    assertEquals(new RuleRun("EQ-06", MODULE, 1, 1, 0, null), run(evaluation, "EQ-06"));
    assertFalse(evaluation.ruleRuns().stream().anyMatch(run -> !"EQ-06".equals(run.ruleId())));
  }

  @Test
  void plainTextEquationInBodyRemainsUnknownRatherThanAbsent() {
    var document = new ParsedDocument("source", List.of(
        paragraph("第一章 绪论", 0), paragraph("实际温度 = t1/10", 1)),
        List.of(), List.of());

    var evaluation = new EquationRuleModule().evaluate(document, RuleSet.schoolV1());

    for (String id : List.of("EQ-01", "EQ-02", "EQ-03", "EQ-04", "EQ-05", "EQ-06", "EQ-07"))
      assertEquals(new RuleRun(id, MODULE, 1, 0, 1, null), run(evaluation, id), id);
  }

  @Test
  void inlineCalculationsInOrdinaryTableExplanationsAreNotStandaloneFormulaCandidates() {
    var explanationTable = new TableModel(address(1), address(2), "表1-1 属性说明",
        3, 2, true, true, true, false, true, false)
        .withCellEvidence(List.of(
            new TableModel.CellEvidence(address(1, 1, 1), 1, "说明", false, 1),
            new TableModel.CellEvidence(address(1, 2, 1), 2, "说明", false, 1)));
    var document = new ParsedDocument("source", List.of(
        paragraph("第一章 绪论", 0),
        paragraphAt("属性名称", 1, 0, 0, 0), paragraphAt("说明", 1, 0, 1, 0),
        paragraphAt("温度", 1, 1, 0, 0), paragraphAt("实际温度 = t1/10", 1, 1, 1, 0),
        paragraphAt("主事件流", 1, 2, 0, 0), paragraphAt("下发属性，op=1或op=0", 1, 2, 1, 0)),
        List.of(), List.of(), List.of(explanationTable), List.of(), List.of());

    var evaluation = new EquationRuleModule().evaluate(document, RuleSet.schoolV1());

    assertFalse(evaluation.ruleRuns().stream().anyMatch(run -> run.ruleId().startsWith("EQ-")));
  }

  @Test
  void isolatedTextEquationInOrdinaryDataTableIsNotSilentlyDiscarded() {
    var document = withTables(List.of(paragraph("第一章 绪论", 0),
        paragraphAt("x=a+b", 1, 1, 1, 0)), table(1));

    assertSevenUnknown(new EquationRuleModule().evaluate(document, RuleSet.schoolV1()), 1);
  }

  @Test
  void separateDisplayedParagraphInsideDescriptionCellRemainsUnknown() {
    var table = new TableModel(address(1), null, "表1-1 参数说明",
        2, 2, true, true, true, false, true, false)
        .withCellEvidence(List.of(new TableModel.CellEvidence(
            address(1, 1, 1), 1, "说明", false, 2)));
    var document = withTables(List.of(paragraph("第一章 绪论", 0),
        paragraphAt("计算关系如下", 1, 1, 1, 0),
        paragraphAt("x=a+b", 1, 1, 1, 1)), table);

    assertSevenUnknown(new EquationRuleModule().evaluate(document, RuleSet.schoolV1()), 1);
  }

  @Test
  void proseSentenceWithInlineEqualityIsNotAnIndependentFormula() {
    var document = new ParsedDocument("source", List.of(
        paragraph("第一章 绪论", 0), paragraph("由计算式可得：x = y + 2。", 1)),
        List.of(), List.of());

    var evaluation = new EquationRuleModule().evaluate(document, RuleSet.schoolV1());

    assertFalse(evaluation.ruleRuns().stream().anyMatch(run -> run.ruleId().startsWith("EQ-")));
  }

  @Test
  void manualBreaksKeepSeparateDisplayedTextEquationsAsSeparateCandidates() {
    var document = new ParsedDocument("source", List.of(
        paragraph("第一章 绪论", 0),
        paragraph("计算结果如下：\nx = y + 2\na = b + 3", 1)),
        List.of(), List.of());

    assertSevenUnknown(new EquationRuleModule().evaluate(document, RuleSet.schoolV1()), 2);
  }

  @Test
  void plainTextEquationInsideBorderlessFormulaTableRemainsUnknownRatherThanAbsent() {
    var document = withTables(List.of(paragraph("第一章 绪论", 0),
        paragraphAt("x=1", 1, 0, 0, 0)), formulaLayoutTable(1));

    assertSevenUnknown(new EquationRuleModule().evaluate(document, RuleSet.schoolV1()), 1);
  }

  @Test
  void nestedFormulaLayoutInsideOrdinaryTableRemainsAStandaloneCandidate() {
    var outer = new TableModel(address(1), null, null, 3, 3,
        true, true, true, false, true, false);
    var inner = new TableModel(address(1, 0, 0, 0), null, null, 1, 2,
        false, false, false, false, false, false);
    var document = new ParsedDocument("source", List.of(
        paragraph("第一章 绪论", 0), paragraphAt("x=1", 1, 0, 0, 0, 0, 0, 0)),
        List.of(), List.of(), List.of(outer, inner), List.of(), List.of());

    assertSevenUnknown(new EquationRuleModule().evaluate(document, RuleSet.schoolV1()), 1);
  }

  @Test
  void ordinaryNestedDataTableDoesNotInheritBorderlessOuterFormulaLayout() {
    var outer = new TableModel(address(1), null, null, 1, 2,
        false, false, false, false, false, false);
    var inner = new TableModel(address(1, 0, 0, 0), null, null, 3, 3,
        true, true, true, false, true, false).withCellEvidence(List.of(
            new TableModel.CellEvidence(address(1, 0, 0, 0, 0, 0), 1, "说明", false, 1)));
    var document = new ParsedDocument("source", List.of(
        paragraph("第一章 绪论", 0), paragraphAt("实际温度 = t1/10", 1, 0, 0, 0, 0, 0, 0)),
        List.of(), List.of(), List.of(outer, inner), List.of(), List.of());

    assertFalse(new EquationRuleModule().evaluate(document, RuleSet.schoolV1()).ruleRuns().stream()
        .anyMatch(run -> run.ruleId().startsWith("EQ-")));
  }

  @Test
  void plainTextEquationInsideAppendixFormulaTableRemainsUnknownRatherThanAbsent() {
    var document = withTables(List.of(paragraph("附录A 术语", 0),
        paragraphAt("y=2", 1, 0, 0, 0)), formulaLayoutTable(1));

    assertSevenUnknown(new EquationRuleModule().evaluate(document, RuleSet.schoolV1()), 1);
  }

  @Test
  void tableStatusExplanationsRemainExcluded() {
    var document = withTables(List.of(paragraph("第一章 绪论", 0),
        paragraphAt("状态：1=绿色开启，0=红色关闭。", 1, 0, 0, 0)), table(1));

    assertFalse(new EquationRuleModule().evaluate(document, RuleSet.schoolV1())
        .ruleRuns().stream().anyMatch(run -> run.ruleId().startsWith("EQ-")));
  }

  @Test
  void coverAndContentsTablesDoNotBecomeBodyFormulaCandidates() {
    var document = new ParsedDocument("source", List.of(
        paragraph("本科生毕业设计（论文）", 0),
        paragraphAt("x=1", 1, 0, 0, 0),
        paragraph("目 录", 2),
        paragraphAt("y=2", 3, 0, 0, 0),
        paragraph("第一章 绪论", 4)),
        List.of(), List.of(), List.of(table(1), table(3)), List.of(), List.of());

    assertFalse(new EquationRuleModule().evaluate(document, RuleSet.schoolV1())
        .ruleRuns().stream().anyMatch(run -> run.ruleId().startsWith("EQ-")));
  }

  @Test
  void unpaginatedManualContentsChapterDoesNotActivateTableFormulaScan() {
    var document = new ParsedDocument("source", List.of(
        paragraph("目 录", 0),
        manualContentsEntry("第一章 绪论", 1),
        paragraphAt("x=1", 2, 0, 0, 0),
        paragraph("第一章 绪论", 3),
        paragraphAt("y=2", 4, 0, 0, 0)),
        List.of(), List.of(), List.of(formulaLayoutTable(2), formulaLayoutTable(4)), List.of(), List.of());

    assertSevenUnknown(new EquationRuleModule().evaluate(document, RuleSet.schoolV1()), 1);
  }

  @Test
  void unpaginatedManualContentsAppendixDoesNotActivateTableFormulaScan() {
    var document = new ParsedDocument("source", List.of(
        paragraph("目 录", 0),
        manualContentsEntry("附录A 术语", 1),
        paragraphAt("x=1", 2, 0, 0, 0),
        paragraph("附录A 术语", 3),
        paragraphAt("y=2", 4, 0, 0, 0)),
        List.of(), List.of(), List.of(formulaLayoutTable(2), formulaLayoutTable(4)), List.of(), List.of());

    assertSevenUnknown(new EquationRuleModule().evaluate(document, RuleSet.schoolV1()), 1);
  }

  @Test
  void appendixSourceCodeStatementsAreNotFormulaCandidates() {
    var document = tableRows("附录A 源程序", List.of(
        "    if (workMode == AUTO_MODE) {",
        "    for (int i = 0; i < 10; i++) {",
        "temperature = ds18b20.getTempCByIndex(0);",
        "    int sum = 0;",
        "        sum += analogRead(pin);",
        "waterLevelPercent = getAverageValue(WATER_LEVEL_PIN);",
        "        gelTriggered = true;",
        "            workMode = (workMode + 1) % 3;   // 0自动 1手动 2定时",
        "  const params = {};",
        "  if (this.tempLowerLimit !== '') params.t2 = this.tempLowerLimit;"));

    assertFalse(new EquationRuleModule().evaluate(document, RuleSet.schoolV1())
        .ruleRuns().stream().anyMatch(run -> run.ruleId().startsWith("EQ-")));
  }

  @Test
  void individualAppendixSourceStatementsDoNotBecomeFormulaCandidates() {
    var lines = List.of(
        "    if (workMode == AUTO_MODE) {",
        "    } else if (workMode == TIMER_MODE) {",
        "while (WiFi.status() != WL_CONNECTED) {",
        "temperature = ds18b20.getTempCByIndex(0);",
        "    int sum = 0;",
        "    for (int i = 0; i < 10; i++) {",
        "        sum += analogRead(pin);",
        "waterLevelPercent = getAverageValue(WATER_LEVEL_PIN);",
        "turbidityPercent = getAverageValue(TURBIDITY_PIN);",
        "oxygenPercent = getAverageValue(OXYGEN_PIN);",
        "    if (workMode == AUTO_MODE && oxygenPercent < oxygenThreshold) {",
        "    } else if (workMode == AUTO_MODE) {",
        "        gelTriggered = true;",
        "    } else if (turbidityPercent <= turbidityThreshold) {",
        "        gelTriggered = false;",
        "    if (digitalRead(BUTTON_MODE) == LOW) {",
        "        if (digitalRead(BUTTON_MODE) == LOW) {",
        "            workMode = (workMode + 1) % 3;   // 0自动 1手动 2定时",
        "            while(digitalRead(BUTTON_MODE) == LOW);",
        "    if (workMode == 1) {  // 手动模式",
        "        if (digitalRead(BUTTON_MAN_ON) == LOW)  setOxygenPump(true);",
        "        if (digitalRead(BUTTON_MAN_OFF) == LOW) setOxygenPump(false);",
        "    } else if (workMode == 2) { // 定时模式",
        "        if (digitalRead(BUTTON_INC) == LOW)  oxygenInterval += 10000;",
        "        if (digitalRead(BUTTON_DEC) == LOW)  oxygenInterval -= 10000;",
        "  const params = {};",
        "  if (this.tempLowerLimit !== '') params.t2 = this.tempLowerLimit;",
        "  if (this.turbidityUpperLimit !== '') params.z2 = this.turbidityUpperLimit;");
    var misclassified = lines.stream().filter(line -> new EquationRuleModule()
        .evaluate(tableRows("附录A 源程序", List.of(line)), RuleSet.schoolV1())
        .ruleRuns().stream().anyMatch(run -> run.ruleId().startsWith("EQ-"))).toList();

    assertEquals(List.of(), misclassified);
  }

  @Test
  void boundedCategoryValueListsInTablesAreNotFormulaCandidates() {
    var document = tableRows("第一章 绪论", List.of(
        "0=关闭，1=开启",
        "0=自动，1=手动，2=定时",
        "设备状态（0=离线，1=在线）",
        "加热状态（0=关，1=开）"));

    assertFalse(new EquationRuleModule().evaluate(document, RuleSet.schoolV1())
        .ruleRuns().stream().anyMatch(run -> run.ruleId().startsWith("EQ-")));
  }

  @Test
  void ordinaryTableExplanationsDoNotBecomeIndependentFormulaCandidates() {
    var document = descriptionTableRows("第一章 绪论", List.of(
        "temperature = ds18b20.getTempCByIndex(0);",
        "0=关闭，1=开启",
        "x=1",
        "水温原始值，实际温度 = t1/10",
        "实际温度 = t1/10;",
        "a=b+c;"));

    assertFalse(new EquationRuleModule().evaluate(document, RuleSet.schoolV1())
        .ruleRuns().stream().anyMatch(run -> run.ruleId().startsWith("EQ-")));
  }

  @Test
  void displayedEquationAfterManualBreakInDescriptionCellRemainsUnknown() {
    var document = descriptionTableRows("第一章 绪论",
        List.of("计算说明如下：\nx = y + 1"));

    assertSevenUnknown(new EquationRuleModule().evaluate(document, RuleSet.schoolV1()), 1);
  }

  @Test
  void actualWordLineBreakInDescriptionCellKeepsDisplayedFormulaUnknown(@TempDir Path temp)
      throws Exception {
    var pkg = WordprocessingMLPackage.createPackage();
    String xml = "<w:document xmlns:w='http://schemas.openxmlformats.org/wordprocessingml/2006/main'>"
        + "<w:body><w:p><w:r><w:t>第一章 绪论</w:t></w:r></w:p>"
        + "<w:tbl><w:tr><w:tc><w:p><w:r><w:t>属性</w:t></w:r></w:p></w:tc>"
        + "<w:tc><w:p><w:r><w:t>说明</w:t></w:r></w:p></w:tc></w:tr>"
        + "<w:tr><w:tc><w:p><w:r><w:t>温度</w:t></w:r></w:p></w:tc>"
        + "<w:tc><w:p><w:r><w:t>计算说明如下：</w:t><w:br/>"
        + "<w:t>x = y + 1</w:t></w:r></w:p></w:tc></w:tr></w:tbl>"
        + "<w:sectPr/></w:body></w:document>";
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(xml));
    Path file = temp.resolve("manual-break-table.docx");
    pkg.save(file.toFile());

    var document = new Docx4jDocumentParser().parse(new DocxReader().read(file));
    assertTrue(document.paragraphs().stream().anyMatch(paragraph ->
        paragraph.runs().stream().map(RunModel::text).reduce("", String::concat)
            .equals("计算说明如下：\nx = y + 1")));
    assertSevenUnknown(new EquationRuleModule().evaluate(document, RuleSet.schoolV1()), 1);
  }

  @Test
  void tableHeadingsDoNotChangeTheBodyScanningRegion() {
    var beforeBody = withTables(List.of(paragraphAt("第一章 绪论", 0, 0, 0, 0),
        paragraph("x=1", 1), paragraph("第一章 正文", 2)), formulaLayoutTable(0));
    assertFalse(new EquationRuleModule().evaluate(beforeBody, RuleSet.schoolV1())
        .ruleRuns().stream().anyMatch(run -> run.ruleId().startsWith("EQ-")));

    var inBody = withTables(List.of(paragraph("第一章 正文", 0),
        paragraphAt("参考文献", 1, 0, 0, 0), paragraph("x=1", 2)), formulaLayoutTable(1));
    assertSevenUnknown(new EquationRuleModule().evaluate(inBody, RuleSet.schoolV1()), 1);
  }

  @Test
  void statusValueExplanationsAreNotFormulaCandidates() {
    var document = new ParsedDocument("source", List.of(
        paragraph("第一章 绪论", 0),
        paragraph("设备状态监控：水泵wp、加热器ht、增氧泵op状态显示准确：1=绿色开启，0=红色关闭；"
            + "模式m1=0/1/2对应自动/手动/定时，状态同步率100%。", 1)),
        List.of(), List.of());

    var evaluation = new EquationRuleModule().evaluate(document, RuleSet.schoolV1());

    assertFalse(evaluation.ruleRuns().stream().anyMatch(run -> run.ruleId().startsWith("EQ-")));
  }

  @Test
  void statusAndCalculationInSameProseParagraphAreInline() {
    var document = new ParsedDocument("source", List.of(
        paragraph("第一章 绪论", 0),
        paragraph("状态：1=绿色开启，0=红色关闭；实际温度 = t1/10", 1)),
        List.of(), List.of());

    var evaluation = new EquationRuleModule().evaluate(document, RuleSet.schoolV1());

    assertFalse(evaluation.ruleRuns().stream().anyMatch(run -> run.ruleId().startsWith("EQ-")));
  }

  @Test
  void numericEqualityInsideProseSentenceIsNotIndependent() {
    var document = new ParsedDocument("source", List.of(
        paragraph("第一章 绪论", 0), paragraph("由计算式可得：1=常数。", 1)),
        List.of(), List.of());

    var evaluation = new EquationRuleModule().evaluate(document, RuleSet.schoolV1());

    assertFalse(evaluation.ruleRuns().stream().anyMatch(run -> run.ruleId().startsWith("EQ-")));
  }

  @Test
  void bareValueListWithoutModeLabelRemainsUnknown() {
    var document = new ParsedDocument("source", List.of(
        paragraph("第一章 绪论", 0), paragraph("x=1/2/3对应甲/乙/丙。", 1)),
        List.of(), List.of());

    var evaluation = new EquationRuleModule().evaluate(document, RuleSet.schoolV1());

    for (String id : List.of("EQ-01", "EQ-02", "EQ-03", "EQ-04", "EQ-05", "EQ-06", "EQ-07"))
      assertEquals(new RuleRun(id, MODULE, 1, 0, 1, null), run(evaluation, id), id);
  }

  @Test
  void stateMappingAfterAnotherEqualsDoesNotHideTheEquation() {
    var document = new ParsedDocument("source", List.of(
        paragraph("第一章 绪论", 0), paragraph("状态 x=1=绿色开启。", 1)),
        List.of(), List.of());

    var evaluation = new EquationRuleModule().evaluate(document, RuleSet.schoolV1());

    for (String id : List.of("EQ-01", "EQ-02", "EQ-03", "EQ-04", "EQ-05", "EQ-06", "EQ-07"))
      assertEquals(new RuleRun(id, MODULE, 1, 0, 1, null), run(evaluation, id), id);
  }

  @Test
  void namedOptionMappingAfterAnotherEqualsDoesNotHideTheEquation() {
    var document = new ParsedDocument("source", List.of(
        paragraph("第一章 绪论", 0),
        paragraph("状态 x=模式m1=0/1/2对应自动/手动/定时。", 1)),
        List.of(), List.of());

    var evaluation = new EquationRuleModule().evaluate(document, RuleSet.schoolV1());

    for (String id : List.of("EQ-01", "EQ-02", "EQ-03", "EQ-04", "EQ-05", "EQ-06", "EQ-07"))
      assertEquals(new RuleRun(id, MODULE, 1, 0, 1, null), run(evaluation, id), id);
  }

  @Test
  void uncaptionedBodyImageRemainsPotentialEquationUnknown() {
    var image = new ImageModel(address(1), null, null, 100, 100, true, null, null);
    var document = new ParsedDocument("source", List.of(
        paragraph("第一章 绪论", 0), paragraph(" ", 1)),
        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(image));

    var evaluation = new EquationRuleModule().evaluate(document, RuleSet.schoolV1());

    for (String id : List.of("EQ-01", "EQ-02", "EQ-03", "EQ-04", "EQ-05", "EQ-06", "EQ-07"))
      assertEquals(new RuleRun(id, MODULE, 1, 0, 1, null), run(evaluation, id), id);
  }

  @Test
  void genericOleInBodyRemainsUnknownEvenBesideACaptionedFigure() {
    var source = address(1);
    var image = new ImageModel(source, address(2), "图1-1 示意图", 100, 100, true, true, true);
    var document = new ParsedDocument("source", List.of(
        paragraph("第一章 绪论", 0), paragraph("图片", 1), paragraph("图1-1 示意图", 2)),
        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(image),
        List.of(), List.of(), List.of(), List.of(), true,
        List.of(new EquationParser.PossibleObject(source, "DRAWING"),
            new EquationParser.PossibleObject(source, "OLE")), true);

    var evaluation = new EquationRuleModule().evaluate(document, RuleSet.schoolV1());

    for (String id : List.of("EQ-01", "EQ-02", "EQ-03", "EQ-04", "EQ-05", "EQ-06", "EQ-07"))
      assertEquals(new RuleRun(id, MODULE, 1, 0, 1, null), run(evaluation, id), id);
  }

  private static RuleRun run(RuleModule.Evaluation evaluation, String id) {
    return evaluation.ruleRuns().stream().filter(value -> id.equals(value.ruleId()))
        .findFirst().orElse(null);
  }

  private static void assertSevenUnknown(RuleModule.Evaluation evaluation, int candidates) {
    for (String id : List.of("EQ-01", "EQ-02", "EQ-03", "EQ-04", "EQ-05", "EQ-06", "EQ-07"))
      assertEquals(new RuleRun(id, MODULE, candidates, 0, candidates, null), run(evaluation, id), id);
  }

  private static ParsedDocument withTables(List<ParagraphModel> paragraphs, TableModel table) {
    return new ParsedDocument("source", paragraphs, List.of(), List.of(), List.of(table), List.of(), List.of());
  }

  private static ParsedDocument tableRows(String heading, List<String> texts) {
    var paragraphs = new java.util.ArrayList<ParagraphModel>();
    paragraphs.add(paragraph(heading, 0));
    for (int index = 0; index < texts.size(); index++)
      paragraphs.add(paragraphAt(texts.get(index), 1, 0, index, 0));
    return withTables(paragraphs, table(1));
  }

  private static ParsedDocument descriptionTableRows(String heading, List<String> texts) {
    var paragraphs = new java.util.ArrayList<ParagraphModel>();
    var cells = new java.util.ArrayList<TableModel.CellEvidence>();
    paragraphs.add(paragraph(heading, 0));
    for (int index = 0; index < texts.size(); index++) {
      int row = index + 1;
      paragraphs.add(paragraphAt(texts.get(index), 1, row, 1, 0));
      cells.add(new TableModel.CellEvidence(address(1, row, 1), row, "说明", false, 1));
    }
    return withTables(paragraphs, new TableModel(address(1), null, null,
        texts.size() + 1, 2, true, true, true, false, false, false)
        .withCellEvidence(cells));
  }

  private static TableModel table(int block) {
    return new TableModel(address(block), null, null, 1, 1, true, true, true, false, false, false);
  }

  private static TableModel formulaLayoutTable(int block) {
    return new TableModel(address(block), null, null, 1, 2, false, false, false, false, false, false);
  }

  private static ParagraphModel manualContentsEntry(String text, int block) {
    var source = address(block);
    return new ParagraphModel(List.of(new RunModel(text, "Times New Roman", "宋体", null,
        24, false, source)), 0, null, null, 360, "auto", source, null, null, "left");
  }

  private static ParagraphModel paragraph(String text, int block) {
    return paragraphAt(text, block);
  }

  private static ParagraphModel paragraphAt(String text, int... path) {
    var source = address(path);
    return new ParagraphModel(List.of(new RunModel(text, "Times New Roman", "宋体", null,
        21, false, source)), 0, null, null, 360, "auto", source, 0, null, "center");
  }

  private static SourceAddress address(int... path) {
    return new SourceAddress("word/document.xml", java.util.Arrays.stream(path).boxed()
        .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new)), 0, 5);
  }
}
