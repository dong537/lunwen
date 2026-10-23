package cn.luwneshacha.thesis.document;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class EquationParserTest {
  @Test
  void usesXmlTextLengthRatherThanDisplaySummaryForFractionAnchor() {
    var equation = parse("<w:p><m:oMathPara><m:oMath><m:eqArr><m:e>"
        + "<m:r><m:t>BreakfastRate=</m:t></m:r><m:f>"
        + "<m:num><m:r><m:t>BreakfastTimes</m:t></m:r></m:num>"
        + "<m:den><m:r><m:t>MidBreakfastTimes</m:t></m:r></m:den></m:f>"
        + "<m:r><m:t>#</m:t></m:r><m:r><m:t>(3-1)</m:t></m:r>"
        + "</m:e></m:eqArr></m:oMath></m:oMathPara></w:p>").get(0);

    assertEquals(52, equation.summary().length());
    assertEquals(0, equation.source().start());
    assertEquals(51, equation.source().end());
    assertEquals(46, equation.numberSource().start());
    assertEquals(51, equation.numberSource().end());
  }

  @Test
  void anchorsInlineEquationUsingVisibleParagraphCoordinates() {
    var equation = parse("<w:p><w:r><w:t>See</w:t><w:tab/></w:r>"
        + "<w:del><w:r><w:t>deleted</w:t></w:r></w:del>"
        + "<w:moveFrom><w:r><w:t>moved</w:t></w:r></w:moveFrom>"
        + "<w:r><w:instrText>REF hidden</w:instrText><w:br w:type=\"page\"/></w:r>"
        + "<w:ins><w:r><w:t>now</w:t><w:br/></w:r></w:ins>"
        + "<m:oMath><m:r><m:t>x</m:t></m:r><m:r><m:t>y</m:t></m:r></m:oMath>"
        + "<w:r><w:t> after</w:t></w:r></w:p>").get(0);

    assertEquals("x y", equation.summary());
    assertEquals(8, equation.source().start());
    assertEquals(10, equation.source().end());
  }

  @Test
  void anchorsSplitMathNumberAtItsExactParagraphOffset() {
    var equation = parse("<w:p><w:r><w:tab/><w:br/></w:r>"
        + "<m:oMathPara><m:oMath><m:eqArr><m:e>"
        + "<m:r><m:t>x=1</m:t></m:r><m:r><m:t>#</m:t></m:r>"
        + "<m:r><m:t xml:space=\"preserve\"> (3-</m:t></m:r>"
        + "<m:r><m:t xml:space=\"preserve\">1) </m:t></m:r>"
        + "</m:e></m:eqArr></m:oMath></m:oMathPara></w:p>").get(0);

    assertEquals("(3-1)", equation.numberEvidence().text());
    assertEquals(2, equation.source().start());
    assertEquals(13, equation.source().end());
    assertEquals(7, equation.numberSource().start());
    assertEquals(12, equation.numberSource().end());
    assertEquals(equation.numberSource(), equation.numberEvidence().source());
    assertEquals(Boolean.TRUE, equation.numberRightAligned());
  }

  @Test
  void objectDescriptionDoesNotBecomeAVisibleTextRange() {
    var equation = parse("<w:p><w:r><w:tab/><w:object>"
        + "<o:OLEObject ProgID=\"Equation.3\"/></w:object>"
        + "<w:t>(3-2)</w:t></w:r></w:p>").get(0);

    assertEquals("3-2", equation.number());
    assertEquals(1, equation.source().start());
    assertEquals(1, equation.source().end());
  }

  @Test
  void preservesVariablesInsideCompositeScriptBase() {
    var equations = parse("<w:p><m:oMath><m:r><m:t>z=</m:t></m:r>"
        + "<m:sSup><m:e><m:r><m:t>x+y</m:t></m:r></m:e>"
        + "<m:sup><m:r><m:t>n</m:t></m:r></m:sup></m:sSup>"
        + "</m:oMath></w:p>");
    assertEquals(java.util.List.of("z", "x", "y", "n"), equations.get(0)
        .structuredVariables().stream().map(EquationVariable::identifier).toList());
  }

  @Test
  void keepsMultilineDerivationWithOneExternalNumberAsOneEquation() {
    var equations = parse("<w:p><m:oMathPara><m:oMath><m:eqArr>"
        + "<m:e><m:r><m:t>x=1+2</m:t></m:r></m:e>"
        + "<m:e><m:r><m:t>=3</m:t></m:r></m:e>"
        + "</m:eqArr></m:oMath></m:oMathPara><w:r><w:t>(1-1)</w:t></w:r></w:p>");
    assertEquals(1, equations.size());
    assertEquals("1-1", equations.get(0).number());
  }

  @Test
  void preservesFractionsInIndependentlyNumberedArrayRows() {
    var equations = parse("<w:p><m:oMathPara><m:oMath><m:eqArr>"
        + "<m:e><m:f><m:num><m:r><m:t>x</m:t></m:r></m:num>"
        + "<m:den><m:r><m:t>y</m:t></m:r></m:den></m:f>"
        + "<m:r><m:t>#</m:t></m:r><m:r><m:t>(1-1)</m:t></m:r></m:e>"
        + "<m:e><m:r><m:t>z</m:t></m:r><m:r><m:t>#</m:t></m:r>"
        + "<m:r><m:t>(1-2)</m:t></m:r></m:e>"
        + "</m:eqArr></m:oMath></m:oMathPara></w:p>");
    assertEquals(2, equations.size());
    assertTrue(equations.get(0).summary().contains("x"));
    assertTrue(equations.get(0).summary().contains("y"));
    assertFalse(equations.get(0).summary().contains("1-1"));
  }

  @Test
  void preservesExpressionVariablesAlongsideScriptedVariables() {
    var equations = parse("<w:p><m:oMath><m:r><m:t>y=x+sin(t)+</m:t></m:r>"
        + "<m:sSub><m:e><m:r><m:t>z</m:t></m:r></m:e>"
        + "<m:sub><m:r><m:t>0</m:t></m:r></m:sub></m:sSub>"
        + "</m:oMath></w:p>");

    assertEquals(java.util.List.of("y", "x", "t", "z_0"), equations.get(0)
        .structuredVariables().stream().map(EquationVariable::identifier).toList());
    assertEquals(2, equations.get(0).structuredVariables().get(1).source().start());
    assertEquals(3, equations.get(0).structuredVariables().get(1).source().end());
  }

  private java.util.List<EquationModel> parse(String body) {
    return new EquationParser().parse(("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" xmlns:m=\"http://schemas.openxmlformats.org/officeDocument/2006/math\" xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\" xmlns:o=\"urn:schemas-microsoft-com:office:office\"><w:body>" + body + "</w:body></w:document>").getBytes(StandardCharsets.UTF_8));
  }

  private java.util.List<EquationModel> parse(String body, String styles) {
    String xml = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" "
        + "xmlns:m=\"http://schemas.openxmlformats.org/officeDocument/2006/math\"><w:body>"
        + body + "</w:body></w:document>";
    return new EquationParser().parse(xml.getBytes(StandardCharsets.UTF_8),
        styles.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void ordinaryPicturesAndExcelObjectsAreNotEquations() {
    assertTrue(parse("<w:p><w:r><w:drawing><a:graphic/></w:drawing><w:object><o:OLEObject ProgID=\"Excel.Sheet.12\"/></w:object></w:r></w:p>").isEmpty());
  }

  @Test
  void inlineMathIsNotAStandaloneNumberedEquation() {
    var equations = parse("<w:p><w:r><w:t>For </w:t></w:r><m:oMath><m:r><m:t>x</m:t></m:r></m:oMath><w:r><w:t> see (3-1) for a value.</w:t></w:r></w:p>");
    assertFalse(equations.get(0).standalone());
    assertNull(equations.get(0).number());
    assertEquals(java.util.List.of(0, 0), equations.get(0).source().elementPath());
  }

  @Test
  void equationOleHasParagraphAnchorAndNumber() {
    var equations = parse("<w:p><w:r><w:object><o:OLEObject ProgID=\"Equation.3\"/></w:object><w:t>(3-2)</w:t></w:r></w:p>");
    assertEquals(1, equations.size());
    assertEquals("3-2", equations.get(0).number());
    assertEquals(java.util.List.of(0, 0), equations.get(0).source().elementPath());
  }

  @Test
  void recognizesEquationEmbeddedInDrawingMlObject() {
    var equations = parse("<w:p><w:r><w:drawing><a:graphic><a:graphicData "
        + "uri=\"http://schemas.microsoft.com/office/word/2010/wordprocessingShape\">"
        + "<w:object><o:OLEObject ProgID=\"MathType.MTEF.8\"/></w:object>"
        + "</a:graphicData></a:graphic></w:drawing><w:t>(3-1)</w:t></w:r></w:p>");

    assertEquals(1, equations.size());
    assertEquals("OLE", equations.get(0).type());
    assertEquals("MathType.MTEF.8", equations.get(0).objectEvidence().progId());
    assertFalse(equations.get(0).objectEvidence().internalStructureReadable());
  }

  @Test
  void doesNotTreatOrdinaryDrawingMlPictureAsEquationObject() {
    var equations = parse("<w:p><w:r><w:drawing><a:graphic><a:graphicData "
        + "uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><pic:pic/>"
        + "</a:graphicData></a:graphic></w:drawing><w:t>普通图片</w:t></w:r></w:p>");

    assertTrue(equations.isEmpty());
  }

  @Test
  void createsOneEquationModelPerOmmlArrayRow() {
    var equations = parse("<w:p><m:oMathPara><m:oMath><m:eqArr>"
        + "<m:e><m:r><m:t>x</m:t></m:r><m:r><m:t>#</m:t></m:r><m:r><m:t>(1-1)</m:t></m:r></m:e>"
        + "<m:e><m:r><m:t>y</m:t></m:r><m:r><m:t>#</m:t></m:r><m:r><m:t>(1-2)</m:t></m:r></m:e>"
        + "</m:eqArr></m:oMath></m:oMathPara></w:p>");

    assertEquals(2, equations.size());
    assertEquals("x", equations.get(0).summary());
    assertEquals("1-1", equations.get(0).number());
    assertEquals("y", equations.get(1).summary());
    assertEquals("1-2", equations.get(1).number());
    assertNotEquals(equations.get(0).source().elementPath(), equations.get(1).source().elementPath());
  }

  @Test
  void recognizesRightAlignedEquationNumberAtExactTextAreaBoundary() {
    var equations = parse("<w:p><w:pPr><w:jc w:val=\"center\"/><w:tabs>"
        + "<w:tab w:val=\"right\" w:pos=\"8958\"/></w:tabs></w:pPr>"
        + "<m:oMath><m:r><m:t>x=1</m:t></m:r></m:oMath>"
        + "<w:r><w:tab/></w:r><w:r><w:t>(3-1)</w:t></w:r></w:p>"
        + "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>"
        + "<w:pgMar w:left=\"1474\" w:right=\"1474\"/></w:sectPr>");

    assertEquals("3-1", equations.get(0).number());
    assertEquals(Boolean.TRUE, equations.get(0).numberRightAligned());
  }

  @Test
  void rejectsEquationNumberTabStopThatIsNotAtTextAreaBoundary() {
    var equations = parse("<w:p><w:pPr><w:jc w:val=\"center\"/><w:tabs>"
        + "<w:tab w:val=\"right\" w:pos=\"8957\"/></w:tabs></w:pPr>"
        + "<m:oMath><m:r><m:t>x=1</m:t></m:r></m:oMath>"
        + "<w:r><w:tab/></w:r><w:r><w:t>(3-1)</w:t></w:r></w:p>"
        + "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>"
        + "<w:pgMar w:left=\"1474\" w:right=\"1474\"/></w:sectPr>");

    assertEquals(Boolean.FALSE, equations.get(0).numberRightAligned());
  }

  @Test
  void doesNotAcceptTabAfterEquationNumberAsRightAlignmentEvidence() {
    var equations = parse("<w:p><w:pPr><w:jc w:val=\"center\"/><w:tabs>"
        + "<w:tab w:val=\"right\" w:pos=\"8958\"/></w:tabs></w:pPr>"
        + "<m:oMath><m:r><m:t>x=1</m:t></m:r></m:oMath>"
        + "<w:r><w:t>(3-1)</w:t><w:tab/></w:r></w:p>"
        + "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>"
        + "<w:pgMar w:left=\"1474\" w:right=\"1474\"/></w:sectPr>");

    assertEquals(Boolean.FALSE, equations.get(0).numberRightAligned());
  }

  @Test
  void parsesFullWidthParenthesizedEquationNumber() {
    var equations = parse("<w:p><m:oMathPara><m:oMath><m:r><m:t>x</m:t></m:r></m:oMath></m:oMathPara><w:r><w:t>（3-1）</w:t></w:r></w:p>");
    assertEquals("3-1", equations.get(0).number());
  }

  @Test
  void parsesEquationNumberStoredInOmmlAlignmentArrayWithItsOwnFormattingEvidence() {
    var equations = parse("<w:p><w:pPr><w:jc w:val=\"center\"/></w:pPr>"
        + "<m:oMathPara><m:oMath><m:eqArr><m:e>"
        + "<m:r><m:t>x=1</m:t></m:r><m:r><m:t>#</m:t></m:r>"
        + "<m:r><m:rPr><m:nor/><m:sty m:val=\"p\"/></m:rPr>"
        + "<w:rPr><w:rFonts w:ascii=\"Cambria Math\"/><w:sz w:val=\"21\"/></w:rPr>"
        + "<m:t>(3-1)</m:t></m:r></m:e></m:eqArr></m:oMath></m:oMathPara></w:p>");

    var equation = equations.get(0);
    assertEquals("3-1", equation.number());
    assertTrue(equation.numberEvidence().parenthesized());
    assertEquals("Cambria Math", equation.numberEvidence().fontName());
    assertEquals(21, equation.numberEvidence().fontSizeHalfPoints());
    assertNotNull(equation.numberEvidence().source());
    assertEquals(java.util.List.of(0, 0, 1, 0, 0, 0, 2),
        equation.numberEvidence().source().elementPath());
    assertEquals(equation.numberEvidence().source(), equation.numberSource());
    assertEquals("OMML_NUMBER_ARRAY", equation.layoutEvidence().kind());
    assertEquals(Boolean.TRUE, equation.layoutEvidence().formulaCentered());
    assertEquals(Boolean.TRUE, equation.layoutEvidence().numberRightAligned());
    assertEquals(equation.numberEvidence().source(), equation.layoutEvidence().numberSource());
  }

  @Test
  void resolvesEquationNumberFontAndSizeFromParagraphStyleWhenRunHasNoDirectValues() {
    var equations = parse("<w:p><w:pPr><w:pStyle w:val=\"Formula\"/><w:jc w:val=\"center\"/></w:pPr>"
        + "<m:oMathPara><m:oMath><m:eqArr><m:e><m:r><m:t>x=1</m:t></m:r>"
        + "<m:r><m:t>#</m:t></m:r><m:r><m:rPr><m:nor/><m:sty m:val=\"p\"/></m:rPr>"
        + "<m:t>(3-1)</m:t></m:r></m:e></m:eqArr></m:oMath></m:oMathPara></w:p>",
        "<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
            + "<w:style w:type=\"paragraph\" w:styleId=\"Formula\"><w:rPr>"
            + "<w:rFonts w:ascii=\"Times New Roman\" w:hAnsi=\"Times New Roman\"/>"
            + "<w:sz w:val=\"21\"/></w:rPr></w:style></w:styles>");

    assertEquals("Times New Roman", equations.get(0).numberEvidence().fontName());
    assertEquals(21, equations.get(0).numberEvidence().fontSizeHalfPoints());
  }

  @Test
  void directEquationNumberRunPropertiesOverrideParagraphStyle() {
    var equations = parse("<w:p><w:pPr><w:pStyle w:val=\"Formula\"/></w:pPr>"
        + "<m:oMathPara><m:oMath><m:eqArr><m:e><m:r><m:t>x=1</m:t></m:r>"
        + "<m:r><m:t>#</m:t></m:r><m:r><w:rPr><w:rFonts w:ascii=\"Cambria Math\"/>"
        + "<w:sz w:val=\"20\"/></w:rPr><m:t>(3-1)</m:t></m:r>"
        + "</m:e></m:eqArr></m:oMath></m:oMathPara></w:p>",
        "<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
            + "<w:style w:type=\"paragraph\" w:styleId=\"Formula\"><w:rPr>"
            + "<w:rFonts w:ascii=\"Times New Roman\"/><w:sz w:val=\"21\"/>"
            + "</w:rPr></w:style></w:styles>");

    assertEquals("Cambria Math", equations.get(0).numberEvidence().fontName());
    assertEquals(20, equations.get(0).numberEvidence().fontSizeHalfPoints());
  }

  @Test
  void resolvesEquationNumberPropertiesThroughCharacterStyleBasedOnChain() {
    var equations = parse("<w:p><m:oMathPara><m:oMath><m:eqArr><m:e>"
        + "<m:r><m:t>x=1</m:t></m:r><m:r><m:t>#</m:t></m:r>"
        + "<m:r><w:rPr><w:rStyle w:val=\"EquationNumber\"/></w:rPr><m:t>(3-1)</m:t></m:r>"
        + "</m:e></m:eqArr></m:oMath></m:oMathPara></w:p>",
        "<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
            + "<w:style w:type=\"character\" w:styleId=\"EquationNumber\"><w:basedOn w:val=\"BaseNumber\"/></w:style>"
            + "<w:style w:type=\"character\" w:styleId=\"BaseNumber\"><w:rPr>"
            + "<w:rFonts w:ascii=\"Times New Roman\"/><w:sz w:val=\"21\"/>"
            + "</w:rPr></w:style></w:styles>");

    assertEquals("Times New Roman", equations.get(0).numberEvidence().fontName());
    assertEquals(21, equations.get(0).numberEvidence().fontSizeHalfPoints());
  }

  @Test
  void resolvesEquationNumberPropertiesFromDocumentRunDefaults() {
    var equations = parse("<w:p><m:oMathPara><m:oMath><m:eqArr><m:e>"
        + "<m:r><m:t>x=1</m:t></m:r><m:r><m:t>#</m:t></m:r>"
        + "<m:r><m:t>(3-1)</m:t></m:r></m:e></m:eqArr></m:oMath></m:oMathPara></w:p>",
        "<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
            + "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Times New Roman\"/>"
            + "<w:sz w:val=\"21\"/></w:rPr></w:rPrDefault></w:docDefaults></w:styles>");

    assertEquals("Times New Roman", equations.get(0).numberEvidence().fontName());
    assertEquals(21, equations.get(0).numberEvidence().fontSizeHalfPoints());
  }

  @Test
  void resolvesThemeFontSlotUsedByInheritedEquationNumberStyle() {
    String body = "<w:p><w:pPr><w:pStyle w:val=\"Formula\"/></w:pPr>"
        + "<m:oMathPara><m:oMath><m:eqArr><m:e><m:r><m:t>x=1</m:t></m:r>"
        + "<m:r><m:t>#</m:t></m:r><m:r><m:t>(3-1)</m:t></m:r>"
        + "</m:e></m:eqArr></m:oMath></m:oMathPara></w:p>";
    String xml = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" "
        + "xmlns:m=\"http://schemas.openxmlformats.org/officeDocument/2006/math\"><w:body>"
        + body + "</w:body></w:document>";
    String styles = "<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
        + "<w:style w:type=\"paragraph\" w:styleId=\"Formula\"><w:rPr>"
        + "<w:rFonts w:asciiTheme=\"minorHAnsi\"/><w:sz w:val=\"21\"/>"
        + "</w:rPr></w:style></w:styles>";

    var equations = new EquationParser().parse(xml.getBytes(StandardCharsets.UTF_8),
        styles.getBytes(StandardCharsets.UTF_8), java.util.Map.of("minorHAnsi", "Times New Roman"));

    assertEquals("Times New Roman", equations.get(0).numberEvidence().fontName());
    assertEquals(21, equations.get(0).numberEvidence().fontSizeHalfPoints());
  }

  @Test
  void usesThemeFontWhenBothAsciiFontAndAsciiThemeArePresent() {
    String xml = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" "
        + "xmlns:m=\"http://schemas.openxmlformats.org/officeDocument/2006/math\"><w:body>"
        + "<w:p><m:oMathPara><m:oMath><m:eqArr><m:e><m:r><m:t>x</m:t></m:r>"
        + "<m:r><m:t>#</m:t></m:r><m:r><w:rPr><w:rFonts w:ascii=\"Arial\" "
        + "w:asciiTheme=\"minorHAnsi\"/><w:sz w:val=\"21\"/></w:rPr>"
        + "<m:t>(3-1)</m:t></m:r></m:e></m:eqArr></m:oMath></m:oMathPara></w:p>"
        + "</w:body></w:document>";

    var equations = new EquationParser().parse(xml.getBytes(StandardCharsets.UTF_8), null,
        java.util.Map.of("minorHAnsi", "Times New Roman"));

    assertEquals("Times New Roman", equations.get(0).numberEvidence().fontName());
  }

  @Test
  void inheritsAsciiFontWhenDirectRunOnlyOverridesHighAnsiFont() {
    var equations = parse("<w:p><w:pPr><w:pStyle w:val=\"Formula\"/></w:pPr>"
        + "<m:oMathPara><m:oMath><m:eqArr><m:e><m:r><m:t>x</m:t></m:r>"
        + "<m:r><m:t>#</m:t></m:r><m:r><w:rPr><w:rFonts w:hAnsi=\"Arial\"/>"
        + "</w:rPr><m:t>(3-1)</m:t></m:r></m:e></m:eqArr></m:oMath></m:oMathPara></w:p>",
        "<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
            + "<w:style w:type=\"paragraph\" w:styleId=\"Formula\"><w:rPr>"
            + "<w:rFonts w:ascii=\"Times New Roman\"/><w:sz w:val=\"21\"/>"
            + "</w:rPr></w:style></w:styles>");

    assertEquals("Times New Roman", equations.get(0).numberEvidence().fontName());
  }

  @Test
  void doesNotMistakeHashAndParenthesizedFractionContentsForEquationNumber() {
    var equations = parse("<w:p><m:oMathPara><m:oMath><m:eqArr><m:e>"
        + "<m:f><m:num><m:r><m:t>#</m:t></m:r></m:num>"
        + "<m:den><m:r><m:t>(3-1)</m:t></m:r></m:den></m:f>"
        + "</m:e></m:eqArr></m:oMath></m:oMathPara></w:p>");

    assertNull(equations.get(0).number());
    assertNull(equations.get(0).numberEvidence());
  }

  @Test
  void doesNotTreatParenthesizedFormulaContentAsOmmlEquationNumberWithoutDelimiter() {
    var equations = parse("<w:p><m:oMathPara><m:oMath><m:eqArr><m:e>"
        + "<m:r><m:t>x(3-1)+y</m:t></m:r>"
        + "</m:e></m:eqArr></m:oMath></m:oMathPara></w:p>");

    assertNull(equations.get(0).number());
    assertNull(equations.get(0).numberEvidence());
  }

  @Test
  void doesNotParseFullWidthHyphenAsAValidEquationNumber() {
    var equations = parse("<w:p><m:oMathPara><m:oMath><m:r><m:t>x</m:t></m:r></m:oMath></m:oMathPara><w:r><w:t>（3－1）</w:t></w:r></w:p>");

    assertNull(equations.get(0).number());
  }

  @Test
  void recognizesUnparenthesizedEquationNumberForAFormatError() {
    var equations = parse("<w:p><m:oMathPara><m:oMath><m:r><m:t>x</m:t></m:r></m:oMath></m:oMathPara><w:r><w:t>3-1</w:t></w:r></w:p>");

    assertTrue(equations.get(0).standalone());
    assertEquals("3-1", equations.get(0).number());
    assertNotNull(equations.get(0).numberSource());
    assertEquals(java.util.List.of(0, 0), equations.get(0).numberSource().elementPath());
  }

  @Test
  void retainsMismatchedParenthesisInEquationNumberSourceForRuleCheck() {
    var equations = parse("<w:p><m:oMathPara><m:oMath><m:r><m:t>x</m:t></m:r></m:oMath></m:oMathPara>"
        + "<w:r><w:t>（3-1)</w:t></w:r></w:p>");

    assertEquals("3-1", equations.get(0).number());
    assertEquals(0, equations.get(0).numberSource().start());
    assertEquals(5, equations.get(0).numberSource().end());
  }

  @Test
  void readsEquationNumberFromSiblingCellInBorderlessLayoutTable() {
    var equations = parse("<w:tbl><w:tr>"
        + "<w:tc><w:p><m:oMathPara><m:oMath><m:r><m:t>x</m:t></m:r></m:oMath></m:oMathPara></w:p></w:tc>"
        + "<w:tc><w:p><w:r><w:t>(3-1)</w:t></w:r></w:p></w:tc>"
        + "</w:tr></w:tbl>");

    assertEquals(1, equations.size());
    assertTrue(equations.get(0).standalone());
    assertEquals("3-1", equations.get(0).number());
    assertEquals(java.util.List.of(0, 0, 0, 1, 0), equations.get(0).numberSource().elementPath());
  }

  @Test
  void acceptsRightAlignmentOnlyForBorderlessTableWithRightAlignedNumberCell() {
    var equations = parse("<w:tbl><w:tr>"
        + "<w:tc><w:p><w:pPr><w:jc w:val=\"center\"/></w:pPr><m:oMathPara><m:oMath><m:r><m:t>x</m:t></m:r></m:oMath></m:oMathPara></w:p></w:tc>"
        + "<w:tc><w:p><w:pPr><w:jc w:val=\"right\"/></w:pPr><w:r><w:t>(3-1)</w:t></w:r></w:p></w:tc>"
        + "</w:tr></w:tbl>");

    assertEquals(Boolean.TRUE, equations.get(0).numberRightAligned());
    assertEquals("BORDERLESS_TABLE", equations.get(0).layoutEvidence().kind());
    assertEquals(Boolean.TRUE, equations.get(0).layoutEvidence().formulaCentered());
    assertTrue(equations.get(0).layoutEvidence().borderless());
  }

  @Test
  void rejectsRightAlignmentEvidenceWhenEquationTableHasBorders() {
    var equations = parse("<w:tbl><w:tblPr><w:tblBorders><w:top w:val=\"single\"/></w:tblBorders></w:tblPr><w:tr>"
        + "<w:tc><w:p><w:pPr><w:jc w:val=\"center\"/></w:pPr><m:oMathPara><m:oMath><m:r><m:t>x</m:t></m:r></m:oMath></m:oMathPara></w:p></w:tc>"
        + "<w:tc><w:p><w:pPr><w:jc w:val=\"right\"/></w:pPr><w:r><w:t>(3-1)</w:t></w:r></w:p></w:tc>"
        + "</w:tr></w:tbl>");

    assertEquals(Boolean.FALSE, equations.get(0).numberRightAligned());
  }

  @Test
  void readsSiblingCellNumberEvenWhenTableStyleRequiresTableChecks() {
    var equations = parse("<w:tbl><w:tblPr><w:tblStyle w:val=\"TableGrid\"/></w:tblPr><w:tr>"
        + "<w:tc><w:p><m:oMathPara><m:oMath><m:r><m:t>x</m:t></m:r></m:oMath></m:oMathPara></w:p></w:tc>"
        + "<w:tc><w:p><w:r><w:t>(3-1)</w:t></w:r></w:p></w:tc>"
        + "</w:tr></w:tbl>");

    assertEquals(1, equations.size());
    assertEquals("3-1", equations.get(0).number());
    assertNotNull(equations.get(0).numberSource());
  }
  @Test
  void readsOmmlSummaryAndDetectsOleObject() {
    String xml = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" "
        + "xmlns:m=\"http://schemas.openxmlformats.org/officeDocument/2006/math\"><w:body><w:p>"
        + "<m:oMathPara><m:oMath><m:r><m:t>x+1</m:t></m:r></m:oMath></m:oMathPara>"
        + "<w:r><w:t>(3-1)</w:t></w:r><w:object><o:OLEObject xmlns:o=\"urn:schemas-microsoft-com:office:office\" ProgID=\"Equation.3\"/></w:object></w:p></w:body></w:document>";
    var equations = new EquationParser().parse(xml.getBytes(StandardCharsets.UTF_8));
    assertEquals(2, equations.size());
    assertEquals("OMML", equations.get(0).type());
    assertEquals("x+1", equations.get(0).summary());
    assertFalse(equations.get(1).objectEvidence().internalStructureReadable());
    assertEquals("Equation.3", equations.get(1).objectEvidence().progId());
    assertEquals("OLE", equations.get(1).type());
    assertTrue(equations.get(1).needsReview());
  }

  @Test
  void doesNotTreatOmmlVariableExplanationAsAnUnnumberedEquation() {
    var equations = parse("<w:p><m:oMathPara><m:oMath><m:r><m:t>式中：</m:t></m:r>"
        + "<m:r><m:t>z0为Goos−Hanchen位移；θ0为光波的入射角。</m:t></m:r>"
        + "</m:oMath></m:oMathPara></w:p>");

    assertTrue(equations.isEmpty());
  }

  @Test
  void ignoresMathInsideVariableExplanationWhenPrefixIsOutsideOmml() {
    var equations = parse("<w:p><w:r><w:t>式中：</w:t></w:r>"
        + "<m:oMathPara><m:oMath><m:sSub><m:e><m:r><m:t>z</m:t></m:r></m:e>"
        + "<m:sub><m:r><m:t>0</m:t></m:r></m:sub></m:sSub></m:oMath></m:oMathPara></w:p>");

    assertTrue(equations.isEmpty());
  }

  @Test
  void associatesFollowingOmmlVariableExplanationWithTheEquation() {
    var equations = parse("<w:p><m:oMathPara><m:oMath><m:r><m:t>x</m:t></m:r></m:oMath></m:oMathPara></w:p>"
        + "<w:p><m:oMathPara><m:oMath><m:r><m:t>式中：x为长度。</m:t></m:r></m:oMath></m:oMathPara></w:p>");

    assertEquals(1, equations.size());
    assertEquals("式中：x为长度。", equations.get(0).variableExplanation());
  }

  @Test
  void assignsDistinctNumbersToMultipleEquationsInOneParagraph() {
    var equations = parse("<w:p><m:oMath><m:r><m:t>x</m:t></m:r></m:oMath>"
        + "<w:r><w:t>(3-1) </w:t></w:r><m:oMath><m:r><m:t>y</m:t></m:r></m:oMath>"
        + "<w:r><w:t>(3-2)</w:t></w:r></w:p>");

    assertEquals(java.util.List.of("3-1", "3-2"), equations.stream().map(EquationModel::number).toList());
  }

  @Test
  void marksNumberedPictureFormulaForReviewWithoutTreatingOrdinaryPicturesAsEquations() {
    var equations = parse("<w:p><w:r><w:drawing><a:graphic><a:graphicData>"
        + "<pic:pic/></a:graphicData></a:graphic></w:drawing><w:t>(3-4)</w:t></w:r></w:p>");

    assertEquals(1, equations.size());
    assertEquals("PICTURE", equations.get(0).type());
    assertEquals("3-4", equations.get(0).number());
    assertTrue(equations.get(0).needsReview());
  }

  @Test
  void doesNotTreatAnInlineIllustrationAndNearbyNumberAsAPictureEquation() {
    var equations = parse("<w:p><w:r><w:t>结构如图</w:t>"
        + "<w:drawing><a:graphic><a:graphicData><pic:pic/></a:graphicData></a:graphic></w:drawing>"
        + "<w:t>所示，版本编号为 3-4。</w:t></w:r></w:p>");

    assertTrue(equations.isEmpty());
  }

  @Test
  void readsAppendixEquationNumber() {
    var equations = parse("<w:p><m:oMath><m:r><m:t>x</m:t></m:r></m:oMath><w:r><w:t>(B3)</w:t></w:r></w:p>");

    assertEquals(1, equations.size());
    assertEquals("B3", equations.get(0).number());
  }

  @Test
  void parsesStrictOoXmlMathNamespace() {
    String xml = "<w:document xmlns:w=\"http://purl.oclc.org/ooxml/wordprocessingml/main\" "
        + "xmlns:m=\"http://purl.oclc.org/ooxml/officeDocument/math\"><w:body><w:p>"
        + "<m:oMathPara><m:oMath><m:r><m:t>x+1</m:t></m:r></m:oMath></m:oMathPara>"
        + "<w:r><w:t>(3-1)</w:t></w:r></w:p></w:body></w:document>";

    var equations = new EquationParser().parse(xml.getBytes(StandardCharsets.UTF_8));

    assertEquals(1, equations.size());
    assertEquals("OMML", equations.get(0).type());
    assertEquals("x+1", equations.get(0).summary());
  }

  @Test
  void readsOmmlStructureAndExplicitStyleEvidence() {
    var equations = parse("<w:p><m:oMathPara><m:oMath><m:f><m:num><m:r><m:rPr><m:sty m:val=\"p\"/><m:ctrlPr><w:rPr xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:rFonts w:ascii=\"Arial\"/></w:rPr></m:ctrlPr></m:rPr><m:t>x</m:t></m:r></m:num><m:den><m:r><m:t>y</m:t></m:r></m:den></m:f></m:oMath></m:oMathPara></w:p>");

    var equation = equations.get(0);
    assertTrue(equation.nodeTypes().contains("f"));
    assertTrue(equation.fonts().contains("Arial"));
    assertEquals(Boolean.FALSE, equation.italicVariables());
  }

  @Test
  void preservesBoundariesBetweenAdjacentMathTextTokens() {
    var equations = parse("<w:p><m:oMathPara><m:oMath><m:r><m:t>BreakfastRate</m:t></m:r>"
        + "<m:r><m:t>BreakfastTimes</m:t></m:r><m:r><m:t>MidBreakfastTimes</m:t></m:r></m:oMath></m:oMathPara>"
        + "<w:r><w:t>(3-1)</w:t></w:r></w:p>");

    assertEquals("BreakfastRate BreakfastTimes MidBreakfastTimes", equations.get(0).summary());
    assertEquals(java.util.Set.of("BreakfastRate", "BreakfastTimes", "MidBreakfastTimes"),
        equations.get(0).variables());
  }

  @Test
  void joinsTextFragmentsInsideOneMathRunButKeepsAdjacentMathRunsSeparate() {
    var equations = parse("<w:p><m:oMath><m:r><m:t>Breakfast</m:t><m:t>Rate</m:t></m:r>"
        + "<m:r><m:t>x</m:t></m:r><m:r><m:t>y</m:t></m:r></m:oMath></w:p>");

    assertEquals("BreakfastRate x y", equations.get(0).summary());
    assertEquals(java.util.Set.of("BreakfastRate", "x", "y"), equations.get(0).variables());
  }

  @Test
  void ignoresStructuredFunctionNameAndReadsSubscriptSymbols() {
    var equations = parse("<w:p><m:oMath><m:func><m:fName><m:r><m:t>sin</m:t></m:r></m:fName>"
        + "<m:e><m:sSub><m:e><m:r><m:t>x</m:t></m:r></m:e>"
        + "<m:sub><m:r><m:t>i</m:t></m:r></m:sub></m:sSub></m:e></m:func></m:oMath></w:p>");

    assertEquals(java.util.Set.of("x", "i"), equations.get(0).variables());
  }

  @Test
  void retainsSubscriptAndSuperscriptAsOneStructuredVariable() {
    var equations = parse("<w:p><m:oMathPara><m:oMath><m:sSubSup>"
        + "<m:e><m:r><m:rPr><m:sty m:val=\"i\"/></m:rPr><m:t>x</m:t></m:r></m:e>"
        + "<m:sub><m:r><m:t>i</m:t></m:r></m:sub>"
        + "<m:sup><m:r><m:t>2</m:t></m:r></m:sup>"
        + "</m:sSubSup></m:oMath></m:oMathPara><w:r><w:t>(3-1)</w:t></w:r></w:p>");

    assertEquals(1, equations.get(0).structuredVariables().size());
    var variable = equations.get(0).structuredVariables().get(0);
    assertEquals("x", variable.base());
    assertEquals("i", variable.subscript());
    assertEquals("2", variable.superscript());
    assertEquals("x_i^2", variable.identifier());
    assertEquals(Boolean.TRUE, variable.baseItalic());
    assertFalse(variable.source().elementPath().isEmpty());
  }

  @Test
  void leavesVariableItalicEvidenceUnknownWhenOmmlDoesNotSetItExplicitly() {
    var equations = parse("<w:p><m:oMath><m:r><m:t>x</m:t></m:r></m:oMath></w:p>");

    assertNull(equations.get(0).structuredVariables().get(0).baseItalic());
  }
}
