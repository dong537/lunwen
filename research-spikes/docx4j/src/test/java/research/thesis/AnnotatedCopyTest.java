package research.thesis;

import java.nio.file.Path;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.openpackaging.parts.PartName;
import org.docx4j.openpackaging.parts.WordprocessingML.CommentsPart;
import org.docx4j.wml.Comments;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;

/** Independent reader check for the generated sample, not a Word rendering test. */
@EnabledIfSystemProperty(named="probe.annotatedDocx", matches=".+")
class AnnotatedCopyTest {
    @Test void generatedPackageLoadsWithResolvableClassicComment() throws Exception {
        var pkg=WordprocessingMLPackage.load(Path.of(System.getProperty("probe.annotatedDocx")).toFile());
        var part=pkg.getParts().getParts().get(new PartName("/word/comments.xml"));
        assertInstanceOf(CommentsPart.class,part);
        Comments comments=((CommentsPart)part).getContents();
        assertEquals(1,comments.getComment().size());
        assertEquals("论文格式筛查",comments.getComment().get(0).getAuthor());
        assertFalse(pkg.getMainDocumentPart().getContent().isEmpty());
        var relations=pkg.getMainDocumentPart().getRelationshipsPart();
        var commentRelation=relations.getRelationships().getRelationship().stream()
            .filter(r->r.getType().endsWith("/comments")).toList();
        assertEquals(1,commentRelation.size());
        assertSame(part,relations.getPart(commentRelation.get(0)));
    }
}
