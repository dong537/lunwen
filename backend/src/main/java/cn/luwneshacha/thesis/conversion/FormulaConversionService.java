package cn.luwneshacha.thesis.conversion;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.time.Duration;
import java.util.zip.ZipFile;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;

/** Uses LibreOffice to turn legacy embedded equation objects into a DOCX that can be parsed as OMML. */
public final class FormulaConversionService {
  private static final String DEFAULT_SOFFICE = "soffice";
  private static final String MATH = "http://schemas.openxmlformats.org/officeDocument/2006/math";
  private static final String STRICT_MATH = "http://purl.oclc.org/ooxml/officeDocument/math";

  public Path convertIfNeeded(Path source, Path workDirectory) {
    try {
      if (!containsOpaqueEquationObject(source)) return source;
    } catch (IOException error) {
      throw new IllegalStateException("cannot inspect DOCX formula objects", error);
    }
    Path odtDirectory = workDirectory.resolve("formula-conversion-odt");
    Path docxDirectory = workDirectory.resolve("formula-conversion-docx");
    try {
      Files.createDirectories(odtDirectory);
      Files.createDirectories(docxDirectory);
      String profile = "-env:UserInstallation=" + workDirectory.resolve("libreoffice-profile").toAbsolutePath().toUri();
      run(workDirectory.resolve("formula-odt.log"), profile, "--headless", "--convert-to", "odt", "--outdir", odtDirectory.toString(), source.toString());
      Path odt = odtDirectory.resolve(stripExtension(source.getFileName().toString()) + ".odt");
      if (!Files.isRegularFile(odt)) throw new IOException("LibreOffice did not create ODT output");
      run(workDirectory.resolve("formula-docx.log"), profile, "--headless", "--convert-to", "docx", "--outdir", docxDirectory.toString(), odt.toString());
      Path converted = docxDirectory.resolve(stripExtension(source.getFileName().toString()) + ".docx");
      if (!Files.isRegularFile(converted)) throw new IOException("LibreOffice did not create DOCX output");
      validateConversion(source, converted);
      Path result = workDirectory.resolve("converted-formulas.docx");
      Files.copy(converted, result, StandardCopyOption.REPLACE_EXISTING);
      return result;
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("formula conversion interrupted", error);
    } catch (IOException error) {
      throw new IllegalStateException("formula conversion failed: " + error.getMessage(), error);
    }
  }

  static void validateConversion(Path source, Path converted) {
    try {
      var before = parseXml(documentXml(source));
      var after = parseXml(documentXml(converted));
      List<Element> sourceFormulas = equationUnits(before.getDocumentElement());
      List<Element> convertedFormulas = equationUnits(after.getDocumentElement());
      int expectedCount = sourceFormulas.size() + countOpaqueEquations(before.getDocumentElement());
      if (expectedCount == sourceFormulas.size()) return;
      if (countOpaqueEquations(after.getDocumentElement()) != 0)
        throw new IllegalStateException("formula conversion left unconverted equation objects");
      if (convertedFormulas.size() < expectedCount
          || convertedFormulas.stream().anyMatch(FormulaConversionService::formulaHasNoText))
        throw new IllegalStateException("formula conversion lost equation objects: before="
            + expectedCount + ", after=" + convertedFormulas.size());
      // Existing native equations must survive in order, including duplicates.
      // Text equality is a loss check, not proof of structural or visual fidelity.
      int convertedIndex = 0;
      for (Element original : sourceFormulas) {
        String originalText = formulaText(original);
        while (convertedIndex < convertedFormulas.size()
            && !originalText.equals(formulaText(convertedFormulas.get(convertedIndex)))) convertedIndex++;
        if (convertedIndex == convertedFormulas.size())
          throw new IllegalStateException("formula conversion changed existing native equation content or order");
        convertedIndex++;
      }
    } catch (IOException error) {
      throw new IllegalStateException("cannot verify converted formula evidence", error);
    }
  }

  private static byte[] documentXml(Path file) throws IOException {
    try (ZipFile zip = new ZipFile(file.toFile())) {
      var entry = zip.getEntry("word/document.xml");
      if (entry == null) throw new IOException("word/document.xml is missing");
      try (var input = zip.getInputStream(entry)) {
        return input.readAllBytes();
      }
    }
  }

  private static org.w3c.dom.Document parseXml(byte[] xml) throws IOException {
    try {
      var factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      return factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(xml));
    } catch (Exception error) {
      throw new IOException("invalid word/document.xml", error);
    }
  }

  private static List<Element> equationUnits(Element root) {
    var result = new ArrayList<Element>();
    collectEquationUnits(root, result);
    return result;
  }

  private static void collectEquationUnits(Element element, List<Element> result) {
    if (isMathNamespace(element)
        && ("oMathPara".equals(element.getLocalName()) || "oMath".equals(element.getLocalName()))) {
      result.add(element);
      return;
    }
    for (var child = element.getFirstChild(); child != null; child = child.getNextSibling())
      if (child instanceof Element childElement) collectEquationUnits(childElement, result);
  }

  private static int countOpaqueEquations(Element root) {
    var objects = root.getElementsByTagNameNS("*", "OLEObject");
    int count = 0;
    for (int index = 0; index < objects.getLength(); index++) {
      String progId = ((Element) objects.item(index)).getAttribute("ProgID");
      if ("Equation.3".equalsIgnoreCase(progId) || progId.toLowerCase(java.util.Locale.ROOT).contains("mathtype"))
        count++;
    }
    return count;
  }

  private static boolean formulaHasNoText(Element formula) {
    return formulaText(formula).isBlank();
  }

  private static String formulaText(Element formula) {
    var text = new StringBuilder();
    var tokens = formula.getElementsByTagNameNS("*", "t");
    for (int index = 0; index < tokens.getLength(); index++)
      if (tokens.item(index) instanceof Element token && isMathNamespace(token))
        text.append(token.getTextContent());
    return text.toString();
  }

  private static boolean isMathNamespace(Element element) {
    return MATH.equals(element.getNamespaceURI()) || STRICT_MATH.equals(element.getNamespaceURI());
  }

  private void run(Path log, String... arguments) throws IOException, InterruptedException {
    String executable = System.getProperty("thesis.soffice", DEFAULT_SOFFICE);
    long seconds = Long.parseLong(System.getProperty("thesis.conversion-timeout-seconds", "120"));
    ConversionProcess.run(List.of(build(executable, arguments)), log, Duration.ofSeconds(seconds));
  }

  private static String[] build(String executable, String[] arguments) {
    String[] command = new String[arguments.length + 1];
    command[0] = executable;
    System.arraycopy(arguments, 0, command, 1, arguments.length);
    return command;
  }

  private static boolean containsOpaqueEquationObject(Path source) throws IOException {
    return countOpaqueEquations(parseXml(documentXml(source)).getDocumentElement()) > 0;
  }

  private static String stripExtension(String name) {
    int dot = name.lastIndexOf('.');
    return dot < 0 ? name : name.substring(0, dot);
  }
}
