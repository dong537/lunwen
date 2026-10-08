package cn.luwneshacha.thesis.document;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class EquationParserTest {
  private java.util.List<EquationModel> parse(String body) {
    return new EquationParser().parse(("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" xmlns:m=\"http://schemas.openxmlformats.org/officeDocument/2006/math\" xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\" xmlns:o=\"urn:schemas-microsoft-com:office:office\"><w:body>" + body + "</w:body></w:document>").getBytes(StandardCharsets.UTF_8));
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
  void readsOmmlSummaryAndDetectsOleObject() {
    String xml = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" "
        + "xmlns:m=\"http://schemas.openxmlformats.org/officeDocument/2006/math\"><w:body><w:p>"
        + "<m:oMathPara><m:oMath><m:r><m:t>x+1</m:t></m:r></m:oMath></m:oMathPara>"
        + "<w:r><w:t>(3-1)</w:t></w:r><w:object><o:OLEObject xmlns:o=\"urn:schemas-microsoft-com:office:office\" ProgID=\"Equation.3\"/></w:object></w:p></w:body></w:document>";
    var equations = new EquationParser().parse(xml.getBytes(StandardCharsets.UTF_8));
    assertEquals(2, equations.size());
    assertEquals("OMML", equations.get(0).type());
    assertEquals("x+1", equations.get(0).summary());
    assertEquals("OLE", equations.get(1).type());
    assertTrue(equations.get(1).needsReview());
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
}
