package research.thesis;

import org.docx4j.XmlUtils;
import org.docx4j.TraversalUtil;
import org.docx4j.finders.ClassFinder;
import org.docx4j.convert.out.common.preprocess.ParagraphStylesInTableFix;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.wml.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class EffectiveFormattingTest {
    static final String NS = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"";
    static final String STYLES = "<w:styles " + NS + ">" + """
        <w:docDefaults><w:rPrDefault><w:rPr><w:sz w:val="22"/></w:rPr></w:rPrDefault></w:docDefaults>
        <w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/>
          <w:rPr><w:rFonts w:ascii="Times New Roman" w:hAnsi="Times New Roman" w:eastAsia="宋体"/><w:sz w:val="24"/></w:rPr>
        </w:style>
        <w:style w:type="paragraph" w:styleId="Heading"><w:name w:val="Heading"/><w:basedOn w:val="Normal"/>
          <w:rPr><w:rFonts w:eastAsia="黑体"/><w:sz w:val="32"/></w:rPr>
        </w:style>
        <w:style w:type="character" w:styleId="Emphasis"><w:name w:val="Emphasis"/><w:rPr><w:i/></w:rPr></w:style>
        <w:style w:type="table" w:styleId="HeaderTable"><w:name w:val="HeaderTable"/>
          <w:tblStylePr w:type="firstRow"><w:rPr><w:b/></w:rPr></w:tblStylePr>
        </w:style></w:styles>
        """;

    @TempDir Path temp;

    WordprocessingMLPackage document(String body) throws Exception {
        var pkg = WordprocessingMLPackage.createPackage();
        pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(
            "<w:document " + NS + "><w:body>" + body + "</w:body></w:document>"));
        pkg.getMainDocumentPart().getStyleDefinitionsPart().setContents((Styles) XmlUtils.unmarshalString(STYLES));
        // Real ZIP/OPC serialization and loading, not only in-memory object tests.
        Path file = temp.resolve("fixture.docx");
        pkg.save(file.toFile());
        return WordprocessingMLPackage.load(file.toFile());
    }

    P paragraph(WordprocessingMLPackage pkg) {
        var finder = new ClassFinder(P.class);
        new TraversalUtil(pkg.getMainDocumentPart().getContent(), finder);
        return (P) finder.results.get(0);
    }

    RPr effective(WordprocessingMLPackage pkg) throws Exception {
        P p = paragraph(pkg);
        R r = (R) XmlUtils.unwrap(p.getContent().get(0));
        return pkg.getMainDocumentPart().getPropertyResolver().getEffectiveRPr(r.getRPr(), p.getPPr());
    }

    @Test void implicitNormalStyleOverridesDocumentDefaults() throws Exception {
        var pkg = document("<w:p><w:r><w:t>正文ABC</w:t></w:r></w:p>");
        assertEquals(24, effective(pkg).getSz().getVal().intValue());
    }

    @Test void mixedScriptFontSlotsSurviveStyleInheritance() throws Exception {
        var pkg = document("<w:p><w:pPr><w:pStyle w:val=\"Heading\"/></w:pPr><w:r><w:rPr><w:rStyle w:val=\"Emphasis\"/></w:rPr><w:t>标题ABC</w:t></w:r></w:p>");
        RPr props = effective(pkg);
        assertEquals("黑体", props.getRFonts().getEastAsia());
        assertEquals("Times New Roman", props.getRFonts().getAscii());
        assertEquals(32, props.getSz().getVal().intValue());
        assertTrue(props.getI().isVal());
    }

    @Test void directSizeOverridesInheritedSize() throws Exception {
        var pkg = document("<w:p><w:pPr><w:pStyle w:val=\"Heading\"/></w:pPr><w:r><w:rPr><w:sz w:val=\"21\"/></w:rPr><w:t>direct</w:t></w:r></w:p>");
        assertEquals(21, effective(pkg).getSz().getVal().intValue());
    }

    @Test void paragraphMarkSizeDoesNotOverrideTextSize() throws Exception {
        var pkg = document("<w:p><w:pPr><w:rPr><w:sz w:val=\"40\"/></w:rPr></w:pPr><w:r><w:t>text</w:t></w:r></w:p>");
        assertEquals(24, effective(pkg).getSz().getVal().intValue());
    }

    @Test void characterIndentAndLineRuleRemainDistinct() throws Exception {
        var pkg = document("<w:p><w:pPr><w:ind w:firstLineChars=\"200\" w:firstLine=\"480\"/><w:spacing w:line=\"360\" w:lineRule=\"auto\"/></w:pPr><w:r><w:t>text</w:t></w:r></w:p>");
        PPr props = pkg.getMainDocumentPart().getPropertyResolver().getEffectivePPr(paragraph(pkg).getPPr());
        assertEquals(200, props.getInd().getFirstLineChars().intValue());
        assertEquals(480, props.getInd().getFirstLine().intValue());
        assertEquals(STLineSpacingRule.AUTO, props.getSpacing().getLineRule());
        assertEquals(360, props.getSpacing().getLine().intValue());
        // Confirms typed evidence preservation, not visual equivalence or layout.
    }

    @Test void tableHeaderConditionalBoldMustReachEffectiveRun() throws Exception {
        var pkg = document("""
          <w:tbl><w:tblPr><w:tblStyle w:val="HeaderTable"/><w:tblLook w:firstRow="1" w:firstColumn="0" w:lastRow="0" w:lastColumn="0" w:noHBand="1" w:noVBand="1"/></w:tblPr>
          <w:tblGrid><w:gridCol w:w="2000"/></w:tblGrid><w:tr><w:tc><w:tcPr><w:tcW w:w="2000" w:type="dxa"/></w:tcPr>
          <w:p><w:r><w:t>header</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
          """);
        if (Boolean.getBoolean("probe.tableAware")) ParagraphStylesInTableFix.process(pkg);
        RPr props = effective(pkg);
        assertTrue(props.getB() != null && props.getB().isVal(),
            "first-row table style must make header bold; standalone PropertyResolver lacks table context");
    }

    @Test void fixedEighteenPointSpacingIsNotOnePointFiveLines() throws Exception {
        var pkg = document("<w:p><w:pPr><w:spacing w:line=\"360\" w:lineRule=\"exact\"/></w:pPr><w:r><w:t>text</w:t></w:r></w:p>");
        var spacing = pkg.getMainDocumentPart().getPropertyResolver().getEffectivePPr(paragraph(pkg).getPPr()).getSpacing();
        assertEquals(360, spacing.getLine().intValue());
        assertEquals(STLineSpacingRule.EXACT, spacing.getLineRule());
        assertNotEquals(STLineSpacingRule.AUTO, spacing.getLineRule());
    }

    @Test void tablePreprocessingMutatesWorkingModelAndRequiresSourceMapping() throws Exception {
        var pkg = document("""
          <w:tbl><w:tblPr><w:tblStyle w:val="HeaderTable"/><w:tblLook w:firstRow="1"/></w:tblPr>
          <w:tblGrid><w:gridCol w:w="2000"/></w:tblGrid><w:tr><w:tc><w:tcPr><w:tcW w:w="2000" w:type="dxa"/></w:tcPr>
          <w:p><w:r><w:t>header</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
          """);
        String original = XmlUtils.marshaltoString(pkg.getMainDocumentPart().getContents());
        ParagraphStylesInTableFix.process(pkg);
        assertNotEquals(original, XmlUtils.marshaltoString(pkg.getMainDocumentPart().getContents()));
        assertNotNull(paragraph(pkg).getPPr().getPStyle());
    }
}
