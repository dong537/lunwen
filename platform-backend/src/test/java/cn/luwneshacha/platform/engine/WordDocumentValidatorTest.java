package cn.luwneshacha.platform.engine;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class WordDocumentValidatorTest {
  private static final String W="http://schemas.openxmlformats.org/wordprocessingml/2006/main";
  private static final String REL="http://schemas.openxmlformats.org/package/2006/relationships";
  private static final String OFFICE="http://schemas.openxmlformats.org/officeDocument/2006/relationships/";
  @TempDir Path directory;

  @ParameterizedTest @ValueSource(strings={"header1","footer1","footnotes","endnotes"})
  void storyCommentsUseTheMainDocumentsCommentsPart(String story) throws Exception {
    Path original=write("original.docx",parts(story,false));
    Path marked=write("marked.docx",parts(story,true));
    assertDoesNotThrow(()->WordDocumentValidator.validate(original,marked,true,Set.of("word/"+story+".xml")));
  }

  @Test void unrelatedHeaderRelationshipsDoNotNeedADuplicateCommentsRelationship() throws Exception {
    Path original=write("original.docx",parts("header1",false));
    var marked=parts("header1",true);
    marked.put("word/_rels/header1.xml.rels",relations("<Relationship Id='rLink' Type='"+OFFICE+"hyperlink' Target='https://example.com/' TargetMode='External'/>"));
    assertDoesNotThrow(()->WordDocumentValidator.validate(original,write("marked.docx",marked),true,Set.of("word/header1.xml")));
  }

  @Test void perHeaderLinkCannotReplaceTheMainDocumentCommentsRelationship() throws Exception {
    Path original=write("original.docx",parts("header1",false));
    var marked=parts("header1",true);
    marked.put("word/_rels/document.xml.rels",relations(storyRelationship("header1")));
    marked.put("word/_rels/header1.xml.rels",relations(commentRelationship("comments.xml","")));
    assertThrows(IOException.class,()->WordDocumentValidator.validate(original,write("marked.docx",marked),true,Set.of("word/header1.xml")));
  }

  @ParameterizedTest @CsvSource({"https://example.com/comments.xml,External","other-comments.xml,Internal","../../comments.xml,Internal"})
  void globalCommentsRelationshipMustPointToTheInternalCommentsPart(String target,String mode) throws Exception {
    Path original=write("original.docx",parts("header1",false));
    var marked=parts("header1",true);
    marked.put("word/_rels/document.xml.rels",relations(storyRelationship("header1")+commentRelationship(target,mode)));
    assertThrows(IOException.class,()->WordDocumentValidator.validate(original,write("marked.docx",marked),true,Set.of("word/header1.xml")));
  }

  @Test void unrelatedXmlNamespaceCannotDeclareACommentsRelationship() throws Exception {
    Path original=write("original.docx",parts("header1",false));
    var marked=parts("header1",true);
    marked.put("word/_rels/document.xml.rels","<Relationships xmlns='urn:not-package-relationships'>"+commentRelationship("comments.xml","")+"</Relationships>");
    assertThrows(IOException.class,()->WordDocumentValidator.validate(original,write("marked.docx",marked),true,Set.of("word/header1.xml")));
  }

  @ParameterizedTest @ValueSource(strings={"word/_rels/document.xml.rels","word/comments.xml","word/header1.xml"})
  void requiredPartsAreStillRequired(String missing) throws Exception {
    Path original=write("original.docx",parts("header1",false));
    var marked=parts("header1",true);marked.remove(missing);
    assertThrows(IOException.class,()->WordDocumentValidator.validate(original,write("marked.docx",marked),true,Set.of("word/header1.xml")));
  }

  @ParameterizedTest @ValueSource(strings={"reference-missing","reference-unknown","comment-empty","comment-preexisting","body-lost"})
  void globalRelationshipCannotReplaceRealNewCommentsAndPreservedBody(String defect) throws Exception {
    var original=parts("header1",false);var marked=parts("header1",true);
    switch(defect) {
      case "reference-missing" -> marked.put("word/header1.xml",original.get("word/header1.xml"));
      case "reference-unknown" -> marked.put("word/header1.xml",marked.get("word/header1.xml").replace("w:id='1'","w:id='99'"));
      case "comment-empty" -> marked.put("word/comments.xml",marked.get("word/comments.xml").replace("页眉应居中",""));
      case "comment-preexisting" -> original.put("word/comments.xml",marked.get("word/comments.xml"));
      case "body-lost" -> marked.put("word/document.xml",marked.get("word/document.xml").replace("论文正文",""));
      default -> fail("unknown defect");
    }
    Path source=write("original.docx",original);
    assertThrows(IOException.class,()->WordDocumentValidator.validate(source,write("marked.docx",marked),true,Set.of("word/header1.xml")));
  }

  @ParameterizedTest @ValueSource(strings={"<broken","<!DOCTYPE Relationships [<!ENTITY data SYSTEM 'file:///private'>]><Relationships xmlns='http://schemas.openxmlformats.org/package/2006/relationships'>&data;</Relationships>"})
  void invalidRelationshipXmlAndExternalEntitiesAreStillRejected(String xml) throws Exception {
    Path original=write("original.docx",parts("header1",false));
    var marked=parts("header1",true);marked.put("word/_rels/document.xml.rels",xml);
    assertThrows(IOException.class,()->WordDocumentValidator.validate(original,write("marked.docx",marked),true,Set.of("word/header1.xml")));
  }

  private static Map<String,String> parts(String story,boolean comments) {
    String type=story.startsWith("header")?"header":story.startsWith("footer")?"footer":story;
    String reference=comments?"<w:r><w:commentReference w:id='1'/></w:r>":"";
    String paragraph="<w:p><w:r><w:t>页眉或注释文字</w:t></w:r>"+reference+"</w:p>";
    String storyXml=switch(type) {
      case "header" -> "<w:hdr xmlns:w='"+W+"'>"+paragraph+"</w:hdr>";
      case "footer" -> "<w:ftr xmlns:w='"+W+"'>"+paragraph+"</w:ftr>";
      case "footnotes" -> "<w:footnotes xmlns:w='"+W+"'><w:footnote w:id='2'>"+paragraph+"</w:footnote></w:footnotes>";
      default -> "<w:endnotes xmlns:w='"+W+"'><w:endnote w:id='2'>"+paragraph+"</w:endnote></w:endnotes>";
    };
    String section=type.equals("header")||type.equals("footer")?"<w:sectPr><w:"+type+"Reference w:type='default' r:id='rStory'/></w:sectPr>":"";
    String noteReference=type.endsWith("notes")?"<w:r><w:"+type.substring(0,type.length()-1)+"Reference w:id='2'/></w:r>":"";
    var parts=new LinkedHashMap<String,String>();
    parts.put("[Content_Types].xml","<Types xmlns='http://schemas.openxmlformats.org/package/2006/content-types'><Default Extension='rels' ContentType='application/vnd.openxmlformats-package.relationships+xml'/><Override PartName='/word/document.xml' ContentType='application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml'/><Override PartName='/word/"+story+".xml' ContentType='application/vnd.openxmlformats-officedocument.wordprocessingml."+type+"+xml'/>"+(comments?"<Override PartName='/word/comments.xml' ContentType='application/vnd.openxmlformats-officedocument.wordprocessingml.comments+xml'/>":"")+"</Types>");
    parts.put("_rels/.rels",relations("<Relationship Id='rMain' Type='"+OFFICE+"officeDocument' Target='word/document.xml'/>"));
    parts.put("word/document.xml","<w:document xmlns:w='"+W+"' xmlns:r='"+OFFICE.substring(0,OFFICE.length()-1)+"'><w:body><w:p><w:r><w:t>论文正文</w:t></w:r>"+noteReference+"</w:p>"+section+"</w:body></w:document>");
    parts.put("word/"+story+".xml",storyXml);
    parts.put("word/_rels/document.xml.rels",relations(storyRelationship(story)+(comments?commentRelationship("comments.xml",""):"")));
    if(comments) parts.put("word/comments.xml","<w:comments xmlns:w='"+W+"'><w:comment w:id='1' w:author='规则检查'><w:p><w:r><w:t>页眉应居中</w:t></w:r></w:p></w:comment></w:comments>");
    return parts;
  }

  private static String storyRelationship(String story) {
    String type=story.startsWith("header")?"header":story.startsWith("footer")?"footer":story;
    return "<Relationship Id='rStory' Type='"+OFFICE+type+"' Target='"+story+".xml'/>";
  }
  private static String commentRelationship(String target,String mode) {
    return "<Relationship Id='rComments' Type='"+OFFICE+"comments' Target='"+target+"'"+(mode.isEmpty()?"":" TargetMode='"+mode+"'")+"/>";
  }
  private static String relations(String content) {return "<Relationships xmlns='"+REL+"'>"+content+"</Relationships>";}
  private Path write(String name,Map<String,String> parts) throws IOException {
    Path file=directory.resolve(name);
    try(var zip=new ZipOutputStream(Files.newOutputStream(file))) {
      for(var part:parts.entrySet()) {zip.putNextEntry(new ZipEntry(part.getKey()));zip.write(part.getValue().getBytes(StandardCharsets.UTF_8));zip.closeEntry();}
    }
    return file;
  }
}
