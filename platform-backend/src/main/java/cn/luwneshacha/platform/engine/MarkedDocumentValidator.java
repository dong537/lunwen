package cn.luwneshacha.platform.engine;

import java.io.*;
import java.net.URI;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipFile;
import javax.xml.stream.*;

/** Verifies the returned OOXML artifact independently of the engine's JSON claims. */
final class MarkedDocumentValidator {
  private static final String MAIN="word/document.xml";
  private static final String COMMENTS="word/comments.xml";
  private static final long MAX_XML=100L*1024*1024;
  private record WordPart(boolean body,boolean content,Set<String> references) {}

  static void validate(Path original,Path marked,boolean requireComments,Set<String> anchorParts) throws IOException {
    try(var output=new ZipFile(marked.toFile());var input=new ZipFile(original.toFile())) {
      if(output.getEntry("[Content_Types].xml")==null) throw new IOException("missing OOXML content types");
      WordPart actual=wordPart(output,MAIN);
      if(!actual.body()) throw new IOException("Word document body is missing");
      if(wordPart(input,MAIN).content()&&!actual.content()) throw new IOException("marked document lost its original body content");
      if(!requireComments) return;
      Set<String> comments=commentIds(output,true),existing=commentIds(input,false);
      var parts=new LinkedHashSet<>(anchorParts);
      if(parts.isEmpty()) parts.add(MAIN);
      for(String part:parts) {
        if(!part.matches("word/[A-Za-z0-9_/-]+\\.xml")||part.contains("..")) throw new IOException("invalid annotation part");
        Set<String> references=part.equals(MAIN)?actual.references():wordPart(output,part).references();
        Set<String> added=new HashSet<>(references);added.removeAll(existing);
        if(added.isEmpty()||!comments.containsAll(added))
          throw new IOException("written annotations have no new nonempty Word comment references");
        requireCommentsRelationship(output,part);
      }
    }
  }

  private static WordPart wordPart(ZipFile archive,String part) throws IOException {
    boolean[] state=new boolean[3];
    Set<String> references=new HashSet<>();
    read(archive,part,xml->{
      if(xml.getEventType()!=XMLStreamConstants.START_ELEMENT) return;
      boolean word=isWord(xml.getNamespaceURI());String name=xml.getLocalName();
      if(!state[0]) {
        state[0]=true;
        if(part.equals(MAIN)&&(!word||!name.equals("document"))) throw new IOException("invalid Word document root");
      }
      boolean math="http://schemas.openxmlformats.org/officeDocument/2006/math".equals(xml.getNamespaceURI())
          ||"http://purl.oclc.org/ooxml/officeDocument/math".equals(xml.getNamespaceURI());
      if(math&&(name.equals("oMath")||name.equals("oMathPara"))) state[2]=true;
      if(math&&name.equals("t")&&!xml.getElementText().isBlank()) state[2]=true;
      if(!word) return;
      if(name.equals("body")) state[1]=true;
      if(Set.of("drawing","pict","object","tbl").contains(name)) state[2]=true;
      if(name.equals("t")&&!xml.getElementText().isBlank()) state[2]=true;
      if(name.equals("commentReference")) references.add(attribute(xml,"id"));
    });
    return new WordPart(state[1],state[2],references);
  }

  private static Set<String> commentIds(ZipFile archive,boolean requireNonempty) throws IOException {
    if(archive.getEntry(COMMENTS)==null) return Set.of();
    Set<String> ids=new HashSet<>();String[] current={null};boolean[] nonempty={false};
    read(archive,COMMENTS,xml->{
      if(!isWord(xml.getNamespaceURI())) return;
      if(xml.getEventType()==XMLStreamConstants.START_ELEMENT) {
        if(xml.getLocalName().equals("comment")) {current[0]=attribute(xml,"id");nonempty[0]=false;}
        else if(current[0]!=null&&xml.getLocalName().equals("t")&&!xml.getElementText().isBlank()) nonempty[0]=true;
      } else if(xml.getEventType()==XMLStreamConstants.END_ELEMENT&&xml.getLocalName().equals("comment")) {
        if(current[0]!=null&&(!requireNonempty||nonempty[0])) ids.add(current[0]);
        current[0]=null;
      }
    });
    return ids;
  }

  private static void requireCommentsRelationship(ZipFile archive,String part) throws IOException {
    int slash=part.lastIndexOf('/');
    String relationships=part.substring(0,slash+1)+"_rels/"+part.substring(slash+1)+".rels";
    boolean[] linked={false};
    read(archive,relationships,xml->{
      if(xml.getEventType()!=XMLStreamConstants.START_ELEMENT||!xml.getLocalName().equals("Relationship")) return;
      String type=xml.getAttributeValue(null,"Type"),target=xml.getAttributeValue(null,"Target");
      if(("http://schemas.openxmlformats.org/officeDocument/2006/relationships/comments".equals(type)
          ||"http://purl.oclc.org/ooxml/officeDocument/relationships/comments".equals(type))
          &&!"External".equals(xml.getAttributeValue(null,"TargetMode"))&&target!=null
          &&URI.create("/"+part).resolve(target).normalize().toString().equals("/"+COMMENTS)) linked[0]=true;
    });
    if(!linked[0]) throw new IOException("Word comments relationship is missing");
  }

  private static String attribute(XMLStreamReader xml,String local) throws IOException {
    for(int i=0;i<xml.getAttributeCount();i++) if(local.equals(xml.getAttributeLocalName(i))) {
      String value=xml.getAttributeValue(i);
      if(value.matches("[0-9]{1,12}")) return value;
    }
    throw new IOException("invalid Word comment identifier");
  }
  private static boolean isWord(String namespace) {
    return "http://schemas.openxmlformats.org/wordprocessingml/2006/main".equals(namespace)
        ||"http://purl.oclc.org/ooxml/wordprocessingml/main".equals(namespace);
  }
  @FunctionalInterface private interface XmlVisitor {void accept(XMLStreamReader xml) throws IOException,XMLStreamException;}
  private static void read(ZipFile archive,String part,XmlVisitor visitor) throws IOException {
    var entry=archive.getEntry(part);
    if(entry==null||entry.isDirectory()||entry.getSize()>MAX_XML) throw new IOException("missing or oversized Word XML part");
    var factory=XMLInputFactory.newFactory();
    factory.setProperty(XMLInputFactory.SUPPORT_DTD,false);
    factory.setProperty("javax.xml.stream.isSupportingExternalEntities",false);
    try(var stream=new FilterInputStream(archive.getInputStream(entry)) {
      long total;
      private int count(int read) throws IOException {if(read>0&&((total+=read)>MAX_XML)) throw new IOException("inflated XML exceeds limit");return read;}
      @Override public int read() throws IOException {int value=in.read();if(value>=0)count(1);return value;}
      @Override public int read(byte[] bytes,int offset,int length) throws IOException {return count(in.read(bytes,offset,length));}
    }) {
      XMLStreamReader xml=factory.createXMLStreamReader(stream);
      try {
        while(xml.hasNext()) {
          int event=xml.next();
          if(event==XMLStreamConstants.DTD||event==XMLStreamConstants.ENTITY_REFERENCE) throw new IOException("external XML content is forbidden");
          if(event==XMLStreamConstants.START_ELEMENT||event==XMLStreamConstants.END_ELEMENT) visitor.accept(xml);
        }
      } finally {xml.close();}
    } catch(XMLStreamException error) {throw new IOException("unreadable Word XML",error);}
  }
}
