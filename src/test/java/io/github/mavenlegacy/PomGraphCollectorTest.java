package io.github.mavenlegacy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static io.github.mavenlegacy.Model.*;
import static org.junit.jupiter.api.Assertions.*;

class PomGraphCollectorTest {
    @TempDir Path root;
    @Test void traversesParentsNestedAndInactiveImportsAndStopsCyclesWithoutMaven() throws Exception {
        Path cache = root.resolve("cache"), report = root.resolve("report"), evidence = report.resolve("evidence");
        Files.createDirectories(evidence);
        Path a = publish(cache, "a", "<parent><groupId>g</groupId><artifactId>parent</artifactId><version>1</version></parent>" + imports("b"));
        publish(cache, "b", imports("a"));
        publish(cache, "parent", "");
        var module = new Model.Module(); module.effective = new PomReader().read(a);
        module.sources.add(new SourcePom(new Coordinate("g", "a", "1"), "MODULE", a.toString(), "a.xml", "", module.effective));
        var options = new Configuration(null, root).forRepository(root);
        new PomGraphCollector().collect(module, options, evidence, report, cache, true);
        assertEquals(3, module.sources.size());
        assertEquals(3, module.pomRelations.size());
        assertTrue(module.pomRelations.stream().allMatch(r -> r.status().equals("COLLECTED")));
        assertTrue(module.pomRelations.stream().anyMatch(r -> r.profile().equals("inactive")));
        assertTrue(module.invocations.isEmpty());
    }
    @Test void recordsUnresolvedImportWithoutInventingCoordinates() throws Exception {
        Path cache = root.resolve("cache"), report = root.resolve("report"), evidence = report.resolve("evidence");
        Files.createDirectories(evidence);
        Path a = publish(cache, "a", imports("${unknown}"));
        var module = new Model.Module(); module.effective = new PomReader().read(a);
        module.sources.add(new SourcePom(new Coordinate("g", "a", "1"), "MODULE", a.toString(), "a.xml", "", module.effective));
        new PomGraphCollector().collect(module, new Configuration(null, root).forRepository(root), evidence, report, cache, true);
        assertEquals("UNRESOLVED", module.pomRelations.getFirst().status());
        assertEquals("${unknown}", module.pomRelations.getFirst().target().artifactId());
    }
    private Path publish(Path cache, String artifact, String extra) throws Exception {
        Path file = cache.resolve("g/" + artifact + "/1/" + artifact + "-1.pom"); Files.createDirectories(file.getParent());
        Files.writeString(file, "<project><groupId>g</groupId><artifactId>" + artifact + "</artifactId><version>1</version>" + extra + "</project>");
        return file;
    }
    private String imports(String artifact) {
        return "<profiles><profile><id>inactive</id><dependencyManagement><dependencies><dependency><groupId>g</groupId><artifactId>" + artifact
                + "</artifactId><version>1</version><type>pom</type><scope>import</scope></dependency></dependencies></dependencyManagement></profile></profiles>";
    }
}
