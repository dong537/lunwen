package research.thesis;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import org.docx4j.TraversalUtil;
import org.docx4j.XmlUtils;
import org.docx4j.finders.ClassFinder;
import org.docx4j.convert.out.common.preprocess.ParagraphStylesInTableFix;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.wml.P;
import org.docx4j.wml.R;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;

/** Diagnostic timing only: one template, one process, no throughput claim. */
@EnabledIfSystemProperty(named = "probe.measure", matches = "true")
class ParsingMeasurementTest {
    @Test void recordWarmLoadAndResolutionSeparately() throws Exception {
        var path = Path.of(System.getProperty("school.docx"));
        byte[] original = Files.readAllBytes(path);
        var loadMs = new ArrayList<Double>();
        var resolutionMs = new ArrayList<Double>();
        int measuredParagraphs = 0;
        int measuredRuns = 0;
        for (int iteration = 0; iteration < 12; iteration++) {
            long start = System.nanoTime();
            var pkg = WordprocessingMLPackage.load(new ByteArrayInputStream(original));
            long loaded = System.nanoTime();
            ParagraphStylesInTableFix.process(pkg);
            var finder = new ClassFinder(P.class);
            new TraversalUtil(pkg.getMainDocumentPart().getContent(), finder);
            var resolver = pkg.getMainDocumentPart().getPropertyResolver();
            int runs = 0;
            for (Object object : finder.results) {
                P p = (P) object;
                resolver.getEffectivePPr(p.getPPr());
                for (Object child : p.getContent()) {
                    if (XmlUtils.unwrap(child) instanceof R r) {
                        assertNotNull(resolver.getEffectiveRPr(r.getRPr(), p.getPPr()));
                        runs++;
                    }
                }
            }
            long resolved = System.nanoTime();
            assertTrue(runs > 0);
            if (iteration >= 2) {
                loadMs.add((loaded - start) / 1_000_000.0);
                resolutionMs.add((resolved - loaded) / 1_000_000.0);
            }
            measuredParagraphs = finder.results.size();
            measuredRuns = runs;
        }
        Collections.sort(loadMs);
        Collections.sort(resolutionMs);
        String result = """
          {"samples":10,"warmup":2,"bytes":%d,"traversedParagraphs":%d,"directChildRuns":%d,
          "loadMedianMs":%.3f,"loadMaxMs":%.3f,"preprocessAndResolveMedianMs":%.3f,"preprocessAndResolveMaxMs":%.3f}
          """.formatted(original.length, measuredParagraphs, measuredRuns,
            (loadMs.get(4)+loadMs.get(5))/2, loadMs.get(9),
            (resolutionMs.get(4)+resolutionMs.get(5))/2, resolutionMs.get(9));
        Files.createDirectories(Path.of("evidence"));
        Files.writeString(Path.of("evidence/timing.json"), result);
        System.out.println(result);
        assertArrayEquals(original, Files.readAllBytes(path));
    }
}
