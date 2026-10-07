package research.thesis;

import java.util.ArrayList;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Research-only legacy profile. Changes a working DOM, never original bytes. */
final class LegacyProjection {
    static void selectFallbacks(Element parent) {
        var children=new ArrayList<Element>();
        for(Node child=parent.getFirstChild();child!=null;child=child.getNextSibling()) {
            if(child instanceof Element e) children.add(e);
        }
        for(Element child:children) {
            if(SourceIndex.MC.equals(child.getNamespaceURI()) && "AlternateContent".equals(child.getLocalName())) {
                Element fallback=null;
                for(Node branch=child.getFirstChild();branch!=null;branch=branch.getNextSibling()) {
                    if(branch instanceof Element e && SourceIndex.MC.equals(e.getNamespaceURI()) && "Fallback".equals(e.getLocalName())) fallback=e;
                }
                if(fallback==null) throw new IllegalArgumentException("Legacy projection requires explicit mc:Fallback");
                selectFallbacks(fallback);
                while(fallback.hasChildNodes()) parent.insertBefore(fallback.getFirstChild(),child);
                parent.removeChild(child);
            } else selectFallbacks(child);
        }
    }
}
