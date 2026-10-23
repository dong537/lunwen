package research.thesis;

import java.util.List;
import java.util.Set;
import java.util.ArrayList;
import java.util.HexFormat;
import java.security.MessageDigest;
import java.io.ByteArrayInputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Research-only interface: explicit capability profile, original XML addresses. */
final class SourceIndex {
    static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    static final String MC = "http://schemas.openxmlformats.org/markup-compatibility/2006";
    record Text(String value, String kind, String story, List<Integer> address) {}
    record Result(String part, String sha256, List<Text> texts, List<String> diagnostics) {}
    static Result scan(byte[] xml, String part, Set<String> capabilities) throws Exception {
        var texts = new ArrayList<Text>();
        var diagnostics = new ArrayList<String>();
        walk(parse(xml),List.of(),"main","text",capabilities,texts,diagnostics);
        return new Result(part, hash(xml), List.copyOf(texts), List.copyOf(diagnostics));
    }
    static String resolve(byte[] xml, Result result, Text text) throws Exception {
        if (!hash(xml).equals(result.sha256())) throw new IllegalArgumentException("Source XML hash changed");
        if (!result.texts().contains(text)) throw new IllegalArgumentException("Address is not in this source index");
        Element current = parse(xml);
        for (int index : text.address()) current = children(current).get(index);
        if (!current.getTextContent().equals(text.value())) throw new IllegalArgumentException("Source content mismatch");
        return current.getTextContent();
    }

    private static String hash(byte[] xml) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(xml));
    }
    private static Element parse(byte[] xml) throws Exception {
        var factory=DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml)).getDocumentElement();
    }
    private static List<Element> children(Element parent) {
        var elements=new ArrayList<Element>();
        for(Node n=parent.getFirstChild();n!=null;n=n.getNextSibling()) if(n instanceof Element e) elements.add(e);
        return elements;
    }
    private static List<Integer> childAddress(List<Integer> parent,int index) {
        var address=new ArrayList<>(parent); address.add(index); return List.copyOf(address);
    }
    private static boolean is(Element e,String namespace,String local) {
        return namespace.equals(e.getNamespaceURI()) && local.equals(e.getLocalName());
    }
    private static void walk(Element e,List<Integer> address,String story,String kind,Set<String> capabilities,
                             List<Text> texts,List<String> diagnostics) {
        if(is(e,MC,"AlternateContent")) {
            List<Element> choices=children(e);
            int selected=-1, fallback=-1;
            for(int i=0;i<choices.size();i++) {
                Element choice=choices.get(i);
                if(is(choice,MC,"Fallback")) fallback=i;
                if(is(choice,MC,"Choice") && selected<0) {
                    String requires=choice.getAttribute("Requires").trim();
                    boolean supported=!requires.isEmpty();
                    for(String prefix:requires.split("\\s+")) {
                        String uri=choice.lookupNamespaceURI(prefix);
                        if(uri==null || !capabilities.contains(uri)) supported=false;
                    }
                    if(supported) selected=i;
                }
            }
            if(selected<0) selected=fallback;
            if(selected<0) { diagnostics.add("UNSUPPORTED_ALTERNATE_CONTENT at "+address); return; }
            walk(choices.get(selected),childAddress(address,selected),story,kind,capabilities,texts,diagnostics);
            return;
        }
        if(is(e,W,"txbxContent")) story="textbox";
        if(is(e,W,"del") || is(e,W,"moveFrom")) {kind="deleted";diagnostics.add("REVISION at "+address);}
        if(is(e,W,"ins") || is(e,W,"moveTo")) {kind="inserted";diagnostics.add("REVISION at "+address);}
        if(is(e,W,"instrText")) texts.add(new Text(e.getTextContent(),"fieldInstruction",story,address));
        else if(is(e,W,"t") || is(e,W,"delText")) texts.add(new Text(e.getTextContent(),kind,story,address));
        var nested=children(e);
        for(int i=0;i<nested.size();i++) walk(nested.get(i),childAddress(address,i),story,kind,capabilities,texts,diagnostics);
    }
}
