package cn.luwneshacha.thesis.document;

import static cn.luwneshacha.thesis.document.AutomaticNumberingModel.Kind.LISTNUM_FIELD;
import static cn.luwneshacha.thesis.document.AutomaticNumberingModel.Kind.NUMBERED_LIST;
import static cn.luwneshacha.thesis.document.AutomaticNumberingModel.Kind.SEQUENCE_FIELD;
import static cn.luwneshacha.thesis.document.AutomaticNumberingModel.Kind.UNKNOWN_LIST;

import java.math.BigInteger;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.docx4j.wml.NumberFormat;
import org.w3c.dom.Element;

/** Reads active list and field evidence without interpreting displayed numbers as metadata. */
public final class AutomaticNumberingParser {
  private static final String MAIN = "word/document.xml";
  private static final String REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
  private static final String STRICT_REL = "http://purl.oclc.org/ooxml/officeDocument/relationships";
  private static final String PACKAGE_REL = "http://schemas.openxmlformats.org/package/2006/relationships";
  private static final String MC = "http://schemas.openxmlformats.org/markup-compatibility/2006";
  private static final Pattern NUMBERING_FIELD = Pattern.compile(
      "^\\s*(SEQ|LISTNUM|AUTONUM(?:LGL|OUT)?)(?=\\s|$)", Pattern.CASE_INSENSITIVE);

  public List<AutomaticNumberingModel> parse(RawDocx raw) throws Exception {
    return new Reading(raw).read();
  }

  private static final class Reading {
    private final RawDocx raw;
    private final Map<String, Element> roots = new HashMap<>();
    private final Set<AutomaticNumberingModel> result = new LinkedHashSet<>();
    private final Map<String, Set<String>> noteIds = new LinkedHashMap<>();
    private Element styles;
    private Element numbering;

    Reading(RawDocx raw) { this.raw = raw; }

    List<AutomaticNumberingModel> read() throws Exception {
      styles = relatedRoot("styles");
      numbering = relatedRoot("numbering");
      walk(root(MAIN), MAIN, List.of(), null, new ArrayDeque<>());
      for (String story : headerFooterStories())
        walk(root(story), story, List.of(), null, new ArrayDeque<>());
      readNotes("footnote");
      readNotes("endnote");
      return List.copyOf(result);
    }

    private Element root(String name) throws Exception {
      Element root = roots.get(name);
      if (root == null) {
        root = XmlPartSupport.parse(raw.part(name).bytes()).getDocumentElement();
        roots.put(name, root);
      }
      return root;
    }

    private Element relatedRoot(String type) throws Exception {
      String part = relationshipTarget(type, null);
      return part == null ? null : root(part);
    }

    private String relationshipTarget(String type, String id) throws Exception {
      String rels = "word/_rels/document.xml.rels";
      if (!raw.parts().containsKey(rels)) return null;
      Element match = null;
      for (Element relation : XmlPartSupport.children(root(rels))) {
        if (!PACKAGE_REL.equals(relation.getNamespaceURI()) || !"Relationship".equals(relation.getLocalName())) continue;
        String relationType = relation.getAttribute("Type");
        if (!(REL + "/" + type).equals(relationType) && !(STRICT_REL + "/" + type).equals(relationType)) continue;
        if (id != null && !id.equals(relation.getAttribute("Id"))) continue;
        if (match != null) throw new IllegalArgumentException("ambiguous " + type + " relationship");
        match = relation;
      }
      if (match == null) return null;
      String target = match.getAttribute("Target");
      if (target.isBlank() || "External".equalsIgnoreCase(match.getAttribute("TargetMode")))
        throw new IllegalArgumentException("invalid " + type + " relationship target");
      URI resolved = URI.create("/" + MAIN).resolve(target).normalize();
      if (resolved.isAbsolute() || resolved.getAuthority() != null || resolved.getPath() == null)
        throw new IllegalArgumentException("invalid internal part target: " + target);
      String part = resolved.getPath().replaceFirst("^/", "");
      if (!raw.parts().containsKey(part)) throw new IllegalArgumentException("referenced part is missing: " + part);
      return part;
    }

    private Set<String> headerFooterStories() throws Exception {
      var used = new LinkedHashSet<String>();
      var effective = new HashMap<String, String>();
      Element settings = relatedRoot("settings");
      boolean even = XmlOnOff.enabled(direct(settings, "evenAndOddHeaders"));
      for (Element section : descendants(root(MAIN), "sectPr")) {
        for (Element reference : XmlPartSupport.children(section)) {
          String local = reference.getLocalName();
          if (!isWord(reference) || !("headerReference".equals(local) || "footerReference".equals(local))) continue;
          String kind = local.equals("headerReference") ? "header" : "footer";
          String type = value(reference, "type");
          if (type == null || type.isBlank()) type = "default";
          String id = reference.getAttributeNS(REL, "id");
          if (id.isBlank()) id = reference.getAttributeNS(STRICT_REL, "id");
          String target = id.isBlank() ? null : relationshipTarget(kind, id);
          if (target == null) throw new IllegalArgumentException("missing " + kind + " relationship: " + id);
          effective.put(kind + ":" + type, target);
        }
        for (String kind : List.of("header", "footer")) {
          addPart(used, effective.get(kind + ":default"));
          if (XmlOnOff.enabled(direct(section, "titlePg"))) addPart(used, effective.get(kind + ":first"));
          if (even) addPart(used, effective.get(kind + ":even"));
        }
      }
      return used;
    }

    private void readNotes(String kind) throws Exception {
      Set<String> ids = noteIds.getOrDefault(kind, Set.of());
      if (ids.isEmpty()) return;
      String part = relationshipTarget(kind + "s", null);
      if (part == null) throw new IllegalArgumentException("missing referenced " + kind + " part");
      var found = new HashSet<String>();
      List<Element> notes = XmlPartSupport.children(root(part));
      for (int index = 0; index < notes.size(); index++) {
        Element note = notes.get(index);
        String id = value(note, "id");
        if (!isWord(note) || !kind.equals(note.getLocalName()) || !ids.contains(id)) continue;
        if (!found.add(id)) throw new IllegalArgumentException("duplicate " + kind + " id: " + id);
        walk(note, part, List.of(index), null, new ArrayDeque<>());
      }
      if (!found.containsAll(ids)) throw new IllegalArgumentException("referenced " + kind + " is missing");
    }

    private void walk(Element element, String part, List<Integer> path,
        SourceAddress paragraph, Deque<Field> fields) {
      if (excluded(element)) return;
      if (isWord(element)) {
        String name = element.getLocalName();
        if ("p".equals(name)) {
          paragraph = new SourceAddress(part, path, 0, 0);
          AutomaticNumberingModel.Kind kind = listKind(element);
          if (kind != null) result.add(new AutomaticNumberingModel(paragraph, kind));
        } else if ("footnoteReference".equals(name) || "endnoteReference".equals(name)) {
          String id = value(element, "id");
          if (id == null || id.isBlank()) throw new IllegalArgumentException("note reference id is missing");
          String kind = name.equals("footnoteReference") ? "footnote" : "endnote";
          noteIds.computeIfAbsent(kind, ignored -> new LinkedHashSet<>()).add(id);
        } else if ("fldSimple".equals(name)) {
          addField(value(element, "instr"), paragraph);
        } else if ("fldChar".equals(name)) {
          String type = value(element, "fldCharType");
          if ("begin".equals(type)) {
            if (!fields.isEmpty() && !fields.peek().result) fields.peek().instruction.append(' ');
            fields.push(new Field(paragraph));
          } else if (!fields.isEmpty() && "separate".equals(type)) {
            Field field = fields.peek();
            if (!field.result) addField(field.instruction.toString(), field.source);
            field.result = true;
          } else if (!fields.isEmpty() && "end".equals(type)) {
            Field field = fields.pop();
            if (!field.result) addField(field.instruction.toString(), field.source);
          }
        } else if ("instrText".equals(name) && !fields.isEmpty() && !fields.peek().result) {
          fields.peek().instruction.append(element.getTextContent());
        }
      }
      List<Element> children = XmlPartSupport.children(element);
      for (int index = 0; index < children.size(); index++) {
        var childPath = new ArrayList<>(path);
        childPath.add(index);
        walk(children.get(index), part, List.copyOf(childPath), paragraph, fields);
      }
    }

    private void addField(String instruction, SourceAddress source) {
      if (instruction == null || source == null) return;
      var command = NUMBERING_FIELD.matcher(instruction);
      if (command.find()) result.add(new AutomaticNumberingModel(source,
          command.group(1).equalsIgnoreCase("SEQ") ? SEQUENCE_FIELD : LISTNUM_FIELD));
    }

    private AutomaticNumberingModel.Kind listKind(Element paragraph) {
      ParagraphFormatting formatting = paragraphProperties(paragraph);
      Element numId = null;
      Element level = null;
      boolean hasNumPr = false;
      for (Element pPr : formatting.properties()) {
        Element numPr = direct(pPr, "numPr");
        if (numPr == null) continue;
        hasNumPr = true;
        if (numId == null) numId = direct(numPr, "numId");
        if (level == null) level = direct(numPr, "ilvl");
      }
      if (!hasNumPr) return null;
      BigInteger id = integer(value(numId, "val"));
      if (BigInteger.ZERO.equals(id)) return null;
      BigInteger ilvl = level == null ? BigInteger.ZERO : integer(value(level, "val"));
      if (id == null || id.signum() < 0) return UNKNOWN_LIST;
      boolean directLevel = direct(direct(direct(paragraph, "pPr"), "numPr"), "ilvl") != null;
      Element definition = listLevel(id, ilvl, directLevel ? List.of() : formatting.styles(), new HashSet<>());
      String format = value(direct(definition, "numFmt"), "val");
      if (format == null) return UNKNOWN_LIST;
      try {
        NumberFormat.fromValue(format);
      } catch (IllegalArgumentException invalidFormat) {
        return UNKNOWN_LIST;
      }
      return "bullet".equals(format) || "none".equals(format) ? null : NUMBERED_LIST;
    }

    private ParagraphFormatting paragraphProperties(Element paragraph) {
      var result = new ArrayList<Element>();
      var styleIds = new ArrayList<String>();
      Element pPr = direct(paragraph, "pPr");
      if (pPr != null) result.add(pPr);
      String styleId = value(direct(pPr, "pStyle"), "val");
      if (styleId == null && styles != null) {
        for (Element style : XmlPartSupport.children(styles))
          if (isWord(style) && "style".equals(style.getLocalName())
              && "paragraph".equals(value(style, "type")) && XmlOnOff.attribute(style, "default", false))
            styleId = value(style, "styleId");
      }
      Set<String> visited = new HashSet<>();
      while (styleId != null && !styleId.isBlank() && visited.add(styleId)) {
        Element style = style(styleId);
        if (style == null) break;
        if ("paragraph".equals(value(style, "type"))) styleIds.add(styleId);
        Element styleProperties = direct(style, "pPr");
        if (styleProperties != null) result.add(styleProperties);
        styleId = value(direct(style, "basedOn"), "val");
      }
      Element defaults = direct(direct(direct(styles, "docDefaults"), "pPrDefault"), "pPr");
      if (defaults != null) result.add(defaults);
      return new ParagraphFormatting(result, styleIds);
    }

    private Element listLevel(BigInteger id, BigInteger ilvl, List<String> styleIds, Set<BigInteger> visited) {
      if (numbering == null || !visited.add(id)) return null;
      Element num = uniqueNumberedChild(numbering, "num", "numId", id);
      if (num == null) return null;
      BigInteger abstractId = integer(value(direct(num, "abstractNumId"), "val"));
      Element abstractNum = uniqueNumberedChild(numbering, "abstractNum", "abstractNumId", abstractId);
      if (abstractNum == null) return null;
      // For a numbered paragraph style, lvl/pStyle takes precedence over style-level ilvl.
      for (String styleId : styleIds) {
        var matches = XmlPartSupport.children(abstractNum).stream()
            .filter(candidate -> isWord(candidate) && "lvl".equals(candidate.getLocalName())
                && styleId.equals(value(direct(candidate, "pStyle"), "val"))).toList();
        if (matches.size() > 1) return null;
        if (matches.size() == 1) {
          ilvl = integer(value(matches.get(0), "ilvl"));
          if (ilvl == null || ilvl.signum() < 0 || ilvl.compareTo(BigInteger.valueOf(8)) > 0) return null;
          break;
        }
      }
      if (ilvl == null || ilvl.signum() < 0 || ilvl.compareTo(BigInteger.valueOf(8)) > 0) return null;
      List<Element> overrides = numberedChildren(num, "lvlOverride", "ilvl", ilvl);
      if (overrides.size() > 1) return null;
      Element override = overrides.isEmpty() ? null : overrides.get(0);
      Element overrideLevel = direct(override, "lvl");
      if (overrideLevel != null) return overrideLevel;
      String linkedStyle = value(direct(abstractNum, "numStyleLink"), "val");
      if (linkedStyle != null) {
        Element linkedNum = direct(direct(style(linkedStyle), "pPr"), "numPr");
        BigInteger linkedId = integer(value(direct(linkedNum, "numId"), "val"));
        return linkedId == null ? null : listLevel(linkedId, ilvl, styleIds, visited);
      }
      return uniqueNumberedChild(abstractNum, "lvl", "ilvl", ilvl);
    }

    private Element style(String id) {
      if (styles == null) return null;
      Element match = null;
      for (Element style : XmlPartSupport.children(styles)) {
        if (!isWord(style) || !"style".equals(style.getLocalName()) || !id.equals(value(style, "styleId"))) continue;
        if (match != null) return null;
        match = style;
      }
      return match;
    }
  }

  private record ParagraphFormatting(List<Element> properties, List<String> styles) { }

  private static final class Field {
    final SourceAddress source;
    final StringBuilder instruction = new StringBuilder();
    boolean result;
    Field(SourceAddress source) { this.source = source; }
  }

  private static Element uniqueNumberedChild(Element parent, String name, String attribute, BigInteger id) {
    List<Element> matches = numberedChildren(parent, name, attribute, id);
    return matches.size() == 1 ? matches.get(0) : null;
  }

  private static List<Element> numberedChildren(Element parent, String name, String attribute, BigInteger id) {
    if (parent == null || id == null) return List.of();
    var matches = new ArrayList<Element>();
    for (Element child : XmlPartSupport.children(parent)) {
      if (!isWord(child) || !name.equals(child.getLocalName()) || !id.equals(integer(value(child, attribute)))) continue;
      matches.add(child);
    }
    return matches;
  }

  private static BigInteger integer(String value) {
    if (value == null || !value.matches("[+-]?[0-9]+")) return null;
    return new BigInteger(value);
  }

  private static Element direct(Element parent, String name) {
    if (parent == null) return null;
    for (Element child : XmlPartSupport.children(parent))
      if (isWord(child) && name.equals(child.getLocalName())) return child;
    return null;
  }

  private static String value(Element element, String name) {
    return element == null || !element.hasAttributeNS(XmlPartSupport.W, name)
        ? null : element.getAttributeNS(XmlPartSupport.W, name);
  }

  private static boolean isWord(Element element) { return XmlPartSupport.W.equals(element.getNamespaceURI()); }

  private static boolean excluded(Element element) {
    String name = element.getLocalName();
    return MC.equals(element.getNamespaceURI()) && "Fallback".equals(name)
        || isWord(element) && ("del".equals(name) || "moveFrom".equals(name)
            || "delInstrText".equals(name) || name.endsWith("PrChange"));
  }

  private static List<Element> descendants(Element element, String name) {
    var result = new ArrayList<Element>();
    if (excluded(element)) return result;
    if (isWord(element) && name.equals(element.getLocalName())) result.add(element);
    for (Element child : XmlPartSupport.children(element)) result.addAll(descendants(child, name));
    return result;
  }

  private static void addPart(Set<String> parts, String part) { if (part != null) parts.add(part); }
}
