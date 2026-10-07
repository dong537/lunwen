package research.thesis;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.ZipFile;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.XmlUtils;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import static org.junit.jupiter.api.Assertions.*;

class SchoolTemplateTest {
    static final String HASH = "3c9c3dc012b779b4ebd20ea35cb86142d73fb911c7733e3b0d1789ce2d6ac2b2";

    Path source() {
        String path = System.getProperty("school.docx");
        if (path == null || path.isBlank()) throw new IllegalArgumentException("Provide -Dschool.docx=absolute-path; real template is required");
        return Path.of(path);
    }

    Document xml() throws Exception {
        return xml("word/document.xml");
    }

    Document xml(String part) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        try (var zip = new ZipFile(source().toFile()); var stream = zip.getInputStream(zip.getEntry(part))) {
            return factory.newDocumentBuilder().parse(stream);
        }
    }

    int count(Document xml, String expression) throws Exception {
        return ((Double) XPathFactory.newInstance().newXPath().evaluate("count(" + expression + ")", xml, XPathConstants.NUMBER)).intValue();
    }

    @Test void realPackageLoadsAndOriginalBytesStayUnchanged() throws Exception {
        byte[] before = Files.readAllBytes(source());
        assertEquals(HASH, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(before)));
        long start = System.nanoTime();
        var pkg = WordprocessingMLPackage.load(new ByteArrayInputStream(before));
        assertFalse(pkg.getMainDocumentPart().getContent().isEmpty());
        System.out.printf("SCHOOL_PACKAGE_LOAD_MS=%.1f%n", (System.nanoTime() - start) / 1_000_000.0);
        assertArrayEquals(before, Files.readAllBytes(source()));
    }

    @Test void allSectionsAreCheckedAndSeventhRightMarginIsNoncompliant() throws Exception {
        var nodes = (NodeList) XPathFactory.newInstance().newXPath().evaluate(
            "//*[local-name()='sectPr']/*[local-name()='pgMar']", xml(), XPathConstants.NODESET);
        assertEquals(10, nodes.getLength());
        int violations = 0;
        for (int i = 0; i < nodes.getLength(); i++) {
            var margin = (Element) nodes.item(i);
            for (String side : new String[] {"top", "bottom", "left", "right"}) {
                int actual = Integer.parseInt(margin.getAttributeNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main", side));
                if (actual != 1474) {
                    violations++;
                    assertEquals(6, i);
                    assertEquals("right", side);
                    assertEquals(1418, actual);
                }
            }
        }
        assertEquals(1, violations, "Template itself is not wholly compliant with the confirmed school rule");
    }

    @Test void legacyEquationsAreNotMistakenForNativeOmml() throws Exception {
        Document xml = xml();
        assertEquals(2, count(xml, "//*[local-name()='OLEObject' and @ProgID='Equation.3']"));
        assertEquals(0, count(xml, "//*[local-name()='oMath']"));
        // This identifies storage type; it does not decode MTEF or validate equation typography.
    }

    @Test void alternateTextboxesHaveTwoRepresentationsThatMustNotBeDoubleCounted() throws Exception {
        Document xml = xml();
        assertEquals(57, count(xml, "//*[local-name()='Choice']//*[local-name()='txbxContent']"));
        assertEquals(57, count(xml, "//*[local-name()='Fallback']//*[local-name()='txbxContent']"));
        // Evidence of duplicate representation only, not an implementation of MCE selection.
    }

    @Test void unsupportedWpsSettingIsLostInJaxbViewSoRawEvidenceMustBeRetained() throws Exception {
        assertEquals(1, count(xml("word/settings.xml"), "//*[local-name()='typoFeatureVersion']"));
        var pkg = WordprocessingMLPackage.load(source().toFile());
        String effectiveSettings = XmlUtils.marshaltoString(pkg.getMainDocumentPart().getDocumentSettingsPart().getContents());
        assertFalse(effectiveSettings.contains("typoFeatureVersion"));
        assertEquals(1, count(xml("word/settings.xml"), "//*[local-name()='typoFeatureVersion']"));
        // Characterizes library loss, not acceptance of unknown WPS layout semantics.
    }
}
