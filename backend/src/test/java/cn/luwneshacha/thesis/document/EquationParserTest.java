package cn.luwneshacha.thesis.document;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class EquationParserTest {
  private java.util.List<EquationModel> parse(String body) {
    return new EquationParser().parse(("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" xmlns:m=\"http://schemas.openxmlformats.org/officeDocument/2006/math\" xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:o=\"urn:schemas-microsoft-com:office:office\"><w:body>" + body + "</w:body></w:document>").getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void ordinaryPicturesAndExcelObjectsAreNotEquations() {
    assertTrue(parse("<w:p><w:r><w:drawing><a:graphic/></w:drawing><w:object><o:OLEObject ProgID=\"Excel.Sheet.12\"/></w:object></w:r></w:p>").isEmpty());
  }

  @Test
  void inlineMathIsNotAStandaloneNumberedEquation() {
    var equations = parse("<w:p><w:r><w:t>For </w:t></w:r><m:oMath><m:r><m:t>x</m:t></m:r></m:oMath><w:r><w:t> we have a value.</w:t></w:r></w:p>");
    assertFalse(equations.get(0).standalone());
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
}
