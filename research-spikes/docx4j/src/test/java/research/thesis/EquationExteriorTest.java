package research.thesis;

import java.nio.file.Path;
import java.util.Set;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import org.docx4j.XmlUtils;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.wml.*;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import static org.junit.jupiter.api.Assertions.*;

/** Characterizes this template; does not implement a universal equation rule engine. */
class EquationExteriorTest {
    @Test void schoolClauseAppearsOnceAfterCompatibilitySelection() throws Exception {
        var index=SourceIndex.scan(RealSourceIndexTest.part("word/document.xml"),"/word/document.xml",Set.of());
        String selected=index.texts().stream().filter(t->t.kind().equals("text")).map(SourceIndex.Text::value).collect(java.util.stream.Collectors.joining());
        String clause="公式号和公式一起要居中对齐，编号需加圆括号并居右放置（5号，Times New Roman）。";
        int first=selected.indexOf(clause);
        assertTrue(first>=0);
        assertEquals(-1,selected.indexOf(clause,first+clause.length()));
        assertTrue(selected.contains("在文中出现的公式一定要有引用，公式中出现的变量一定要有说明。"));
        assertTrue(selected.contains("式(B3)"),"Appendix numbering is a distinct scope");
    }

    @Test void equationPreviewsAreLinkedToObjectsNotIndependentIllustrations() throws Exception {
        var helper=new SchoolTemplateTest();
        var xml=helper.xml();
        var xpath=XPathFactory.newInstance().newXPath();
        var objects=(NodeList)xpath.evaluate("//*[local-name()='object']",xml,XPathConstants.NODESET);
        assertEquals(2,objects.getLength());
        var rels=helper.xml("word/_rels/document.xml.rels");
        for(int i=0;i<2;i++) {
            Element object=(Element)objects.item(i);
            var ole=(Element)object.getElementsByTagNameNS("urn:schemas-microsoft-com:office:office","OLEObject").item(0);
            var preview=(Element)object.getElementsByTagNameNS("urn:schemas-microsoft-com:vml","imagedata").item(0);
            String ns="http://schemas.openxmlformats.org/officeDocument/2006/relationships";
            assertEquals("rId"+(13+2*i),ole.getAttributeNS(ns,"id"));
            assertEquals("rId"+(14+2*i),preview.getAttributeNS(ns,"id"));
            assertEquals("embeddings/oleObject"+(i+1)+".bin",xpath.evaluate("string(/*/*[@Id='"+ole.getAttributeNS(ns,"id")+"']/@Target)",rels));
            assertEquals("media/image"+(i+2)+".wmf",xpath.evaluate("string(/*/*[@Id='"+preview.getAttributeNS(ns,"id")+"']/@Target)",rels));
        }
    }

    @Test void observedNumberFormattingDoesNotProveRightAlignedLayout() throws Exception {
        var pkg=WordprocessingMLPackage.load(Path.of(System.getProperty("school.docx")).toFile());
        var paragraphs=pkg.getMainDocumentPart().getContent().stream().map(XmlUtils::unwrap).filter(P.class::isInstance).map(P.class::cast).toList();
        P first=paragraphs.get(75),second=paragraphs.get(76);
        var resolver=pkg.getMainDocumentPart().getPropertyResolver();
        assertEquals(JcEnumeration.CENTER,resolver.getEffectivePPr(first.getPPr()).getJc().getVal());
        assertEquals(JcEnumeration.BOTH,resolver.getEffectivePPr(second.getPPr()).getJc().getVal());
        assertEquals("       (3-1)",directText(first));
        assertEquals("",directText(second));
        assertEquals("由式(3-1)给出：",directText(paragraphs.get(74)));
        var selector=new org.docx4j.fonts.RunFontSelector(pkg,new org.docx4j.anon.RunFontCharVisitorMinimal(),
            org.docx4j.fonts.RunFontSelector.RunFontActionType.XSL_FO);
        int numberRuns=0;
        for(Object item:first.getContent()) {
            if(XmlUtils.unwrap(item) instanceof R r) {
                String text=r.getContent().stream().map(XmlUtils::unwrap).filter(Text.class::isInstance).map(Text.class::cast).map(Text::getValue).collect(java.util.stream.Collectors.joining());
                if(text.isBlank()) continue;
                var effective=resolver.getEffectiveRPr(r.getRPr(),first.getPPr());
                assertEquals(21,effective.getSz().getVal().intValue());
                for(int cp:text.codePoints().toArray()) assertEquals("Times New Roman",selector.documentFontFor(first.getPPr(),effective,cp,true));
                numberRuns++;
            }
        }
        assertEquals(3,numberRuns,"Number is split across three runs, not one text node");
    }

    private static String directText(P p) {
        return p.getContent().stream().map(XmlUtils::unwrap).filter(R.class::isInstance).map(R.class::cast)
            .flatMap(r->r.getContent().stream()).map(XmlUtils::unwrap).filter(Text.class::isInstance).map(Text.class::cast)
            .map(Text::getValue).collect(java.util.stream.Collectors.joining());
    }
}
