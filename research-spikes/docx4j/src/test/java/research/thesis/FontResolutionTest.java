package research.thesis;

import java.nio.file.Path;
import org.docx4j.XmlUtils;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.wml.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class FontResolutionTest {
    @TempDir Path temp;
    static final String W="http://schemas.openxmlformats.org/wordprocessingml/2006/main";

    private WordprocessingMLPackage fixture(String direct) throws Exception {
        var pkg=WordprocessingMLPackage.createPackage();
        pkg.getMainDocumentPart().getThemePart().setContents((org.docx4j.dml.Theme)XmlUtils.unmarshalString(
            new String(RealSourceIndexTest.part("word/theme/theme1.xml"),java.nio.charset.StandardCharsets.UTF_8)));
        pkg.getMainDocumentPart().getDocumentSettingsPart().setContents((CTSettings)XmlUtils.unwrap(XmlUtils.unmarshalString(
            "<w:settings xmlns:w=\""+W+"\"><w:themeFontLang w:val=\"en-US\" w:eastAsia=\"zh-CN\"/></w:settings>")));
        pkg.getMainDocumentPart().getStyleDefinitionsPart().setContents((Styles)XmlUtils.unmarshalString("""
            <w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/>
                <w:rPr><w:rFonts w:ascii="Arial" w:hAnsi="Arial" w:eastAsia="黑体"
                 w:asciiTheme="minorHAnsi" w:hAnsiTheme="minorHAnsi" w:eastAsiaTheme="minorEastAsia"/>
                 <w:sz w:val="24"/><w:szCs w:val="32"/></w:rPr></w:style>
              <w:style w:type="character" w:styleId="Emphasis"><w:name w:val="Emphasis"/>
                <w:rPr><w:sz w:val="28"/><w:szCs w:val="36"/></w:rPr></w:style>
            </w:styles>
            """));
        pkg.getMainDocumentPart().setContents((Document)XmlUtils.unmarshalString(
            "<w:document xmlns:w=\""+W+"\"><w:body><w:p><w:r><w:rPr>"+direct+
            "</w:rPr><w:t>A中ا</w:t></w:r></w:p></w:body></w:document>"));
        Path file=temp.resolve("font-fixture.docx");
        pkg.save(file.toFile());
        return WordprocessingMLPackage.load(file.toFile());
    }

    private RPr effective(WordprocessingMLPackage pkg) throws Exception {
        P p=(P)pkg.getMainDocumentPart().getContent().get(0);
        R r=(R)XmlUtils.unwrap(p.getContent().get(0));
        return pkg.getMainDocumentPart().getPropertyResolver().getEffectiveRPr(r.getRPr(),p.getPPr());
    }

    private String font(WordprocessingMLPackage pkg,int codePoint) throws Exception {
        var selector=new org.docx4j.fonts.RunFontSelector(pkg,
            new org.docx4j.anon.RunFontCharVisitorMinimal(),
            org.docx4j.fonts.RunFontSelector.RunFontActionType.XSL_FO);
        return selector.documentFontFor(null,effective(pkg),codePoint,true);
    }

    @Test void themeReferencesOverrideCoexistingLiteralNames() throws Exception {
        var pkg=fixture("");
        assertEquals("Calibri",font(pkg,'A'));
        assertEquals("宋体",font(pkg,'中'));
    }

    @Test void directLiteralNamesOverrideInheritedThemeReferences() throws Exception {
        var pkg=fixture("<w:rFonts w:ascii=\"Times New Roman\" w:hAnsi=\"Times New Roman\" w:eastAsia=\"黑体\"/>");
        assertEquals("Times New Roman",font(pkg,'A'));
        assertEquals("黑体",font(pkg,'中'));
    }

    @Test void characterStyleSizeOverridesParagraphButDirectSizeWins() throws Exception {
        var inherited=effective(fixture("<w:rStyle w:val=\"Emphasis\"/>"));
        assertEquals(28,inherited.getSz().getVal().intValue());
        assertEquals(36,inherited.getSzCs().getVal().intValue());
        var direct=effective(fixture("<w:rStyle w:val=\"Emphasis\"/><w:sz w:val=\"21\"/><w:szCs w:val=\"40\"/>"));
        assertEquals(21,direct.getSz().getVal().intValue());
        assertEquals(40,direct.getSzCs().getVal().intValue());
    }

    @Test void eastAsiaTimesNewRomanWithEqualLatinSlotsUsesLatinFace() throws Exception {
        var pkg=fixture("<w:rFonts w:ascii=\"Arial\" w:hAnsi=\"Arial\" w:eastAsia=\"Times New Roman\"/>");
        assertEquals("Arial",font(pkg,'中'));
    }

    @Test void complexScriptFlagAffectsEvenLatinCharacters() throws Exception {
        String fonts="<w:rFonts w:ascii=\"Arial\" w:hAnsi=\"Arial\" w:eastAsia=\"黑体\" w:cs=\"Courier New\"/>";
        assertEquals("Courier New",font(fixture(fonts+"<w:cs/>"),'A'));
        assertEquals("Arial",font(fixture(fonts+"<w:cs w:val=\"0\"/>"),'A'));
    }

    @Test void realSchoolDirectFormattingMatchesIndependentXmlSamples() throws Exception {
        var pkg=WordprocessingMLPackage.load(Path.of(System.getProperty("school.docx")).toFile());
        var paragraphs=pkg.getMainDocumentPart().getContent().stream().map(XmlUtils::unwrap).filter(P.class::isInstance).map(P.class::cast).toList();
        var selector=new org.docx4j.fonts.RunFontSelector(pkg,new org.docx4j.anon.RunFontCharVisitorMinimal(),
            org.docx4j.fonts.RunFontSelector.RunFontActionType.XSL_FO);
        // Zero-based direct body paragraph/run positions, independently read with Python/lxml.
        int[][] positions={{3,1,72},{8,0,32},{8,2,36}};
        String[] expectedFonts={"方正小标宋简体","仿宋","黑体"};
        String[] expectedText={"本科生毕业设计（论文）","题    目：","线性表的设计和实现"};
        for(int i=0;i<positions.length;i++) {
            P p=paragraphs.get(positions[i][0]);
            var runs=p.getContent().stream().map(XmlUtils::unwrap).filter(R.class::isInstance).map(R.class::cast).toList();
            R r=runs.get(positions[i][1]);
            String text=r.getContent().stream().map(XmlUtils::unwrap).filter(Text.class::isInstance).map(Text.class::cast).map(Text::getValue).collect(java.util.stream.Collectors.joining());
            assertEquals(expectedText[i],text);
            var props=pkg.getMainDocumentPart().getPropertyResolver().getEffectiveRPr(r.getRPr(),p.getPPr());
            assertEquals(positions[i][2],props.getSz().getVal().intValue());
            assertEquals(expectedFonts[i],selector.documentFontFor(p.getPPr(),props,text.codePointAt(0),true));
        }
    }
}
