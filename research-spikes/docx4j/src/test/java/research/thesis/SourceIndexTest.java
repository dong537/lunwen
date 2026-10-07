package research.thesis;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SourceIndexTest {
    static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    static final String MC = "http://schemas.openxmlformats.org/markup-compatibility/2006";
    static final String WPS = "http://schemas.microsoft.com/office/word/2010/wordprocessingShape";
    static byte[] document(String body) {
        return ("<w:document xmlns:w=\""+W+"\" xmlns:mc=\""+MC+"\" xmlns:wps=\""+WPS+"\"><w:body>"+body+"</w:body></w:document>").getBytes(StandardCharsets.UTF_8);
    }
    static String paragraph(String text) { return "<w:p><w:r><w:t>"+text+"</w:t></w:r></w:p>"; }

    @Test void hyperlinksFieldsTablesAndTextboxesAreVisitedOnce() throws Exception {
        byte[] xml=document("<w:p><w:r><w:t>A</w:t></w:r><w:hyperlink><w:r><w:t>B</w:t></w:r></w:hyperlink>"
            +"<w:r><w:instrText> REF bookmark </w:instrText></w:r><w:r><w:t>C</w:t></w:r>"
            +"<w:r><w:pict><w:txbxContent>"+paragraph("BOX")+"</w:txbxContent></w:pict></w:r></w:p>"
            +"<w:tbl><w:tr><w:tc>"+paragraph("CELL")+"</w:tc></w:tr></w:tbl>");
        var result=SourceIndex.scan(xml,"/word/document.xml",Set.of());
        assertEquals(java.util.List.of("A","B"," REF bookmark ","C","BOX","CELL"),result.texts().stream().map(SourceIndex.Text::value).toList());
        assertEquals("fieldInstruction",result.texts().get(2).kind());
        assertEquals("textbox",result.texts().get(4).story());
        for(var text:result.texts()) assertEquals(text.value(),SourceIndex.resolve(xml,result,text));
    }

    @Test void choiceSelectionUsesNamespaceCapabilitiesNotPrefixSpelling() throws Exception {
        byte[] xml=document("<mc:AlternateContent><mc:Choice Requires=\"wps\">"+paragraph("MODERN")
            +"</mc:Choice><mc:Fallback>"+paragraph("LEGACY")+"</mc:Fallback></mc:AlternateContent>");
        var modern=SourceIndex.scan(xml,"/word/document.xml",Set.of(WPS));
        var legacy=SourceIndex.scan(xml,"/word/document.xml",Set.of());
        assertEquals(java.util.List.of("MODERN"),modern.texts().stream().map(SourceIndex.Text::value).toList());
        assertEquals(java.util.List.of("LEGACY"),legacy.texts().stream().map(SourceIndex.Text::value).toList());
        assertEquals("MODERN",SourceIndex.resolve(xml,modern,modern.texts().get(0)));
    }

    @Test void unknownChoiceWithoutFallbackProducesCoverageDiagnostic() throws Exception {
        byte[] xml=document("<mc:AlternateContent><mc:Choice Requires=\"wps\">"+paragraph("HIDDEN")+"</mc:Choice></mc:AlternateContent>");
        var result=SourceIndex.scan(xml,"/word/document.xml",Set.of());
        assertTrue(result.texts().isEmpty());
        assertEquals(1,result.diagnostics().size());
    }

    @Test void duplicateTextHasDistinctAddressesAndChangedSourceIsRejected() throws Exception {
        byte[] xml=document(paragraph("重复")+paragraph("重复"));
        var result=SourceIndex.scan(xml,"/word/document.xml",Set.of());
        assertEquals(2,result.texts().size());
        assertNotEquals(result.texts().get(0).address(),result.texts().get(1).address());
        assertEquals("重复",SourceIndex.resolve(xml,result,result.texts().get(1)));
        assertThrows(IllegalArgumentException.class,()->SourceIndex.resolve(document(paragraph("changed")),result,result.texts().get(0)));
    }

    @Test void unresolvedRevisionsAreFlaggedInsteadOfMergedIntoNormalText() throws Exception {
        byte[] xml=document("<w:p><w:del><w:r><w:delText>OLD</w:delText></w:r></w:del><w:ins><w:r><w:t>NEW</w:t></w:r></w:ins></w:p>");
        var result=SourceIndex.scan(xml,"/word/document.xml",Set.of());
        assertEquals(2,result.texts().size());
        assertEquals(java.util.List.of("deleted","inserted"),result.texts().stream().map(SourceIndex.Text::kind).toList());
        assertFalse(result.diagnostics().isEmpty());
    }
}
