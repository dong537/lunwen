package cn.luwneshacha.thesis.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.docx4j.XmlUtils;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.openpackaging.parts.PartName;
import org.docx4j.openpackaging.parts.WordprocessingML.CommentsPart;
import org.docx4j.wml.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class InspectionApiTest {
  @Autowired MockMvc mvc;
  @TempDir Path temp;

  @BeforeEach
  void configureStorage() {
    System.setProperty("thesis.storage-root", temp.resolve("storage").toString());
  }

  @Test
  void uploadsDocxAndDownloadsAnnotationCopy() throws Exception {
    byte[] source = validDocx();
    var response = mvc.perform(multipart("/api/inspections")
        .file(new MockMultipartFile("file", "thesis.docx", MediaType.APPLICATION_OCTET_STREAM_VALUE, source))
        .param("annotate", "true"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.requestId").isString())
        .andExpect(jsonPath("$.sourceSha256").isString())
        .andExpect(jsonPath("$.report.ruleSetId").value("school-v1"))
        .andExpect(jsonPath("$.annotation.available").value(true))
        .andReturn().getResponse().getContentAsString();
    String requestId = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).get("requestId").asText();

    var download = mvc.perform(get("/api/inspections/{id}/annotated", requestId))
        .andExpect(status().isOk())
        .andReturn();
    assertTrue(download.getResponse().getContentType().startsWith("application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
    assertTrue(download.getResponse().getContentAsByteArray().length > 0);
    Path annotated = temp.resolve("annotated.docx");
    java.nio.file.Files.write(annotated, download.getResponse().getContentAsByteArray());
    var pkg = WordprocessingMLPackage.load(annotated.toFile());
    var comments = (CommentsPart) pkg.getParts().getParts().get(new PartName("/word/comments.xml"));
    assertTrue(comments.getContents().getComment().size() > 0);
  }

  @Test
  void rejectsNonDocxAndMissingAnnotation() throws Exception {
    mvc.perform(multipart("/api/inspections")
        .file(new MockMultipartFile("file", "thesis.txt", MediaType.TEXT_PLAIN_VALUE, "bad".getBytes())))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_DOCX"));

    mvc.perform(get("/api/inspections/missing/annotated"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("ANNOTATION_NOT_FOUND"));
  }

  private byte[] validDocx() throws Exception {
    var pkg = WordprocessingMLPackage.createPackage();
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(
        "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>\u6b63\u6587</w:t></w:r></w:p><w:sectPr><w:pgMar w:top=\"1474\" w:right=\"1474\" w:bottom=\"1474\" w:left=\"1474\"/></w:sectPr></w:body></w:document>"));
    Path source = temp.resolve("source.docx");
    pkg.save(source.toFile());
    return java.nio.file.Files.readAllBytes(source);
  }
}
