package io.github.mavenlegacy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AnalysisTest {
    @TempDir Path root;
    @Test void distinguishesDirectAndTransitiveConsumersAtExactVersion() throws Exception {
        Path library = root.resolve("library.xml");
        Files.writeString(library, "<project><groupId>g</groupId><artifactId>shared</artifactId><version>1</version></project>");
        var producer = new Model.Module(); producer.id = "library"; producer.effective = new PomReader().read(library);
        var consumer = new Model.Module(); consumer.id = "application";
        consumer.dependencies = List.of(
                new Model.ResolvedDependency(new Model.Coordinate("g", "shared", "1"), "jar", "", "compile", false, List.of("g:app:1", "g:shared:1")),
                new Model.ResolvedDependency(new Model.Coordinate("g", "shared", "2"), "jar", "", "compile", false, List.of("g:app:1", "g:shared:2")),
                new Model.ResolvedDependency(new Model.Coordinate("g", "shared", "1"), "jar", "", "runtime", false, List.of("g:app:1", "g:other:1", "g:shared:1")));
        var result = Analysis.report(root.toString(), "MAVEN", List.of(producer, consumer), List.of());
        assertEquals(2, result.internalConsumers().size());
        assertTrue(result.internalConsumers().getFirst().direct());
        assertFalse(result.internalConsumers().get(1).direct());
    }
    @Test void keepsAllSelectedDependencyScopesAndPaths() throws Exception {
        Path tree = root.resolve("tree.json");
        Files.writeString(tree, """
            {"groupId":"g","artifactId":"app","version":"1","children":[
              {"groupId":"g","artifactId":"a","version":"1","type":"jar","scope":"provided","children":[
                {"groupId":"g","artifactId":"b","version":"2","type":"jar","scope":"provided"}]},
              {"groupId":"g","artifactId":"t","version":"1","type":"jar","scope":"test"}]}
            """);
        var result = new DependencyTreeReader().read(tree);
        assertEquals(3, result.size());
        assertEquals("provided", result.get(1).scope());
        assertEquals(List.of("g:app:1", "g:a:1", "g:b:2"), result.get(1).path());
        assertEquals("test", result.get(2).scope());
    }
    @Test void htmlEscapesUntrustedPomText() {
        var module = new Model.Module(); module.id = "<script>alert('x')</script>";
        var report = Analysis.report("root", "INVENTORY", List.of(module), List.of());
        String html = new ReportWriter().html(report);
        assertFalse(html.contains("<script>alert"));
        assertTrue(html.contains("&lt;script&gt;"));
        assertTrue(html.contains("1 POM"));
    }
}
