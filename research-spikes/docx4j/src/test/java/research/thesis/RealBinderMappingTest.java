package research.thesis;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.docx4j.XmlUtils;
import org.docx4j.jaxb.Context;
import org.docx4j.convert.out.common.preprocess.ParagraphStylesInTableFix;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.wml.P;
import org.docx4j.wml.R;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import static org.junit.jupiter.api.Assertions.*;

class RealBinderMappingTest {
    record Bound(Element source, R run, P paragraph) {}

    @Test void selectedSchoolTextRetainsSourceIdentityAfterPreprocessing() throws Exception {
        byte[] xml=RealSourceIndexTest.part("word/document.xml");
        var index=SourceIndex.scan(xml,"/word/document.xml",Set.of());
        var dom=parse(xml);
        var sources=new ArrayList<Element>();
        for(var text:index.texts()) {
            Element node=dom.getDocumentElement();
            for(int wanted:text.address()) {
                int position=0;
                for(Node child=node.getFirstChild();child!=null;child=child.getNextSibling()) {
                    if(child instanceof Element element && position++==wanted) { node=element; break; }
                }
            }
            assertEquals(text.value(),node.getTextContent());
            sources.add(node);
        }
        LegacyProjection.selectFallbacks(dom.getDocumentElement());
        var binder=Context.jc.createBinder();
        var content=(org.docx4j.wml.Document)XmlUtils.unwrap(binder.unmarshal(dom));
        var allText=dom.getElementsByTagNameNS(SourceIndex.W,"t");
        var instructions=dom.getElementsByTagNameNS(SourceIndex.W,"instrText");
        assertEquals(611,allText.getLength()+instructions.getLength(),"Working view must select one compatibility branch");
        var bound=new ArrayList<Bound>();
        for(Element source:sources) {
            Element run=ancestor(source,"r"), paragraph=ancestor(run,"p");
            assertNotNull(run); assertNotNull(paragraph);
            Object runObject=XmlUtils.unwrap(binder.getJAXBNode(run));
            Object paragraphObject=XmlUtils.unwrap(binder.getJAXBNode(paragraph));
            assertInstanceOf(R.class,runObject); assertInstanceOf(P.class,paragraphObject);
            bound.add(new Bound(run,(R)runObject,(P)paragraphObject));
        }
        var original=Files.readAllBytes(Path.of(System.getProperty("school.docx")));
        var pkg=WordprocessingMLPackage.load(new ByteArrayInputStream(original));
        pkg.getMainDocumentPart().setContents(content);
        ParagraphStylesInTableFix.process(pkg);
        var finder=new org.docx4j.finders.ClassFinder(R.class);
        new org.docx4j.TraversalUtil(content,finder);
        var reachable=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Object,Boolean>());
        reachable.addAll(finder.results);
        var resolver=pkg.getMainDocumentPart().getPropertyResolver();
        for(Bound item:bound) {
            assertTrue(reachable.contains(item.run()),"Bound run must remain reachable from working document");
            assertSame(item.source(),binder.getXMLNode(item.run()));
            assertNotNull(resolver.getEffectiveRPr(item.run().getRPr(),item.paragraph().getPPr()));
        }
        assertArrayEquals(original,Files.readAllBytes(Path.of(System.getProperty("school.docx"))));
    }

    @Test void schoolHeadersAndFooterRetainIndependentPartAddresses() throws Exception {
        var names=new ArrayList<String>();
        for(int i=1;i<=7;i++) names.add("word/header"+i+".xml");
        names.add("word/footer1.xml");
        int total=0;
        for(String name:names) {
            byte[] xml=RealSourceIndexTest.part(name);
            var index=SourceIndex.scan(xml,"/"+name,Set.of());
            assertEquals(2,index.texts().size(),name);
            assertTrue(index.diagnostics().isEmpty());
            assertEquals(name.contains("footer")?1:0,index.texts().stream().filter(t->t.kind().equals("fieldInstruction")).count());
            var dom=parse(xml);
            var binder=Context.jc.createBinder();
            binder.unmarshal(dom);
            for(String tag:java.util.List.of("t","instrText")) {
                var nodes=dom.getElementsByTagNameNS(SourceIndex.W,tag);
                for(int i=0;i<nodes.getLength();i++) {
                    var run=ancestor((Element)nodes.item(i),"r");
                    Object mapped=XmlUtils.unwrap(binder.getJAXBNode(run));
                    assertInstanceOf(R.class,mapped);
                    assertSame(run,binder.getXMLNode(mapped));
                    total++;
                }
            }
            for(var text:index.texts()) assertEquals(text.value(),SourceIndex.resolve(xml,index,text));
        }
        assertEquals(16,total);
    }

    private static org.w3c.dom.Document parse(byte[] xml) throws Exception {
        var factory=DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
    }

    private static Element ancestor(Element node,String local) {
        for(Node current=node;current instanceof Element;current=current.getParentNode()) {
            Element e=(Element)current;
            if(SourceIndex.W.equals(e.getNamespaceURI()) && local.equals(e.getLocalName())) return e;
        }
        return null;
    }
}
