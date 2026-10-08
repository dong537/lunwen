package cn.luwneshacha.thesis.document;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class EquationParserTest {
  @Test
  void readsOmmlSummaryAndDetectsOleObject() {
    String xml = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" "
        + "xmlns:m=\"http://schemas.openxmlformats.org/officeDocument/2006/math\"><w:body><w:p>"
        + "<m:oMathPara><m:oMath><m:r><m:t>x+1</m:t></m:r></m:oMath></m:oMathPara>"
        + "<w:r><w:t>(3-1)</w:t></w:r><w:object/></w:p></w:body></w:document>";
    var equations = new EquationParser().parse(xml.getBytes(StandardCharsets.UTF_8));
    assertEquals(2, equations.size());
    assertEquals("OMML", equations.get(0).type());
    assertEquals("x+1", equations.get(0).summary());
    assertEquals("OLE", equations.get(1).type());
    assertTrue(equations.get(1).needsReview());
  }
}
