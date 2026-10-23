package research.thesis;

import java.io.ByteArrayInputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.docx4j.XmlUtils;
import org.docx4j.jaxb.Context;
import org.docx4j.convert.out.common.preprocess.ParagraphStylesInTableFix;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.wml.Styles;
import org.docx4j.wml.R;
import org.docx4j.wml.P;
import org.docx4j.wml.Text;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import static org.junit.jupiter.api.Assertions.*;

class BinderSourceTest {
    @Test void duplicateRunsRetainDistinctXmlNodesThroughTablePreprocessing() throws Exception {
        byte[] xml=SourceIndexTest.document("""
          <w:tbl><w:tblPr><w:tblStyle w:val="HeaderTable"/><w:tblLook w:firstRow="1"/></w:tblPr>
          <w:tblGrid><w:gridCol w:w="2000"/></w:tblGrid><w:tr><w:tc><w:tcPr><w:tcW w:w="2000" w:type="dxa"/></w:tcPr>
          <w:p><w:r><w:t>重复</w:t></w:r><w:r><w:t>重复</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
          """);
        var factory=DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
        var dom=factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        var binder=Context.jc.createBinder();
        var content=(org.docx4j.wml.Document)XmlUtils.unwrap(binder.unmarshal(dom));
        var runs=dom.getElementsByTagNameNS(SourceIndex.W,"r");
        var first=(R)XmlUtils.unwrap(binder.getJAXBNode(runs.item(0)));
        var second=(R)XmlUtils.unwrap(binder.getJAXBNode(runs.item(1)));
        assertNotSame(first,second);
        var pkg=WordprocessingMLPackage.createPackage();
        pkg.getMainDocumentPart().setContents(content);
        pkg.getMainDocumentPart().getStyleDefinitionsPart().setContents((Styles)XmlUtils.unmarshalString(EffectiveFormattingTest.STYLES));
        ParagraphStylesInTableFix.process(pkg);
        assertSame(runs.item(0),binder.getXMLNode(first));
        assertSame(runs.item(1),binder.getXMLNode(second));
        P paragraph=(P)XmlUtils.unwrap(binder.getJAXBNode(runs.item(0).getParentNode()));
        assertNotNull(paragraph.getPPr().getPStyle());
        assertTrue(pkg.getMainDocumentPart().getPropertyResolver().getEffectiveRPr(first.getRPr(),paragraph.getPPr()).getB().isVal());
        // Do not call binder.updateXML(): preserve original source nodes and their addresses.
        assertEquals(0,((Element)runs.item(0).getParentNode()).getElementsByTagNameNS(SourceIndex.W,"pStyle").getLength());
    }
}
