package research.thesis;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RealSourceIndexTest {
    static byte[] part(String name) throws Exception {
        try(var zip=new ZipFile(Path.of(System.getProperty("school.docx")).toFile());
            var input=zip.getInputStream(zip.getEntry(name))) { return input.readAllBytes(); }
    }

    @Test void allLegacyProfileTextAddressesMatchIndependentTemplateCounts() throws Exception {
        byte[] xml=part("word/document.xml");
        var result=SourceIndex.scan(xml,"/word/document.xml",Set.of());
        assertTrue(result.diagnostics().isEmpty());
        assertEquals(611,result.texts().size());
        assertEquals(536,result.texts().stream().filter(t->t.kind().equals("text")).count());
        assertEquals(75,result.texts().stream().filter(t->t.kind().equals("fieldInstruction")).count());
        assertEquals(131,result.texts().stream().filter(t->t.story().equals("textbox")).count());
        var addresses=new HashSet<java.util.List<Integer>>();
        for(var text:result.texts()) {
            assertTrue(addresses.add(text.address()),"Duplicate source address");
            assertEquals(text.value(),SourceIndex.resolve(xml,result,text));
        }
    }

    @Test void modernAndLegacyTextViewsAgreeForThisTemplateWithoutSharingAddresses() throws Exception {
        byte[] xml=part("word/document.xml");
        var legacy=SourceIndex.scan(xml,"/word/document.xml",Set.of());
        var modern=SourceIndex.scan(xml,"/word/document.xml",Set.of(
            "http://schemas.microsoft.com/office/word/2010/wordprocessingShape",
            "http://schemas.microsoft.com/office/word/2010/wordprocessingGroup"));
        assertEquals(legacy.texts().stream().map(SourceIndex.Text::value).toList(),modern.texts().stream().map(SourceIndex.Text::value).toList());
        assertNotEquals(legacy.texts().stream().map(SourceIndex.Text::address).toList(),modern.texts().stream().map(SourceIndex.Text::address).toList());
    }
}
