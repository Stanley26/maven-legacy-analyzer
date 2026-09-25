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
    @Test void groupsCoordinatePropertiesInsteadOfStartingMavenForEachImport() throws Exception {
        Path cache = root.resolve("cache"), report = root.resolve("report"), evidence = report.resolve("evidence");
        Files.createDirectories(evidence);
        StringBuilder imports = new StringBuilder("<dependencyManagement><dependencies>");
        for (int i = 0; i < 30; i++) {
            publish(cache, "bom-" + i, "");
            imports.append("<dependency><groupId>g</groupId><artifactId>bom-").append(i).append("</artifactId><version>${bom.")
                    .append(i).append(".version}</version><type>pom</type><scope>import</scope></dependency>");
        }
        imports.append("</dependencies></dependencyManagement>");
        Path a = publish(cache, "owner", imports.toString());
        var module = new Model.Module(); module.effective = new PomReader().read(a);
        module.sources.add(new SourcePom(new Coordinate("g","owner","1"),"ARTIFACT_CACHE",a.toString(),"owner.xml","",module.effective));
        var calls = new ArrayList<String>();
        new PomGraphCollector((owner, expression) -> {
            calls.add(expression);
            return ("${" + expression + "}").replaceAll("\\$\\{bom\\.[0-9]+\\.version}", "1");
        }).collect(module,new Configuration(null,root).forRepository(root),evidence,report,cache,true);
        assertEquals(30,module.pomRelations.size());
        assertTrue(module.pomRelations.stream().allMatch(r -> r.status().equals("COLLECTED")));
        assertEquals(1,calls.size(),"30 properties in the same Maven context should require one evaluation process");
    }
    private String imports(String artifact) {
        return "<profiles><profile><id>inactive</id><dependencyManagement><dependencies><dependency><groupId>g</groupId><artifactId>" + artifact
                + "</artifactId><version>1</version><type>pom</type><scope>import</scope></dependency></dependencies></dependencyManagement></profile></profiles>";
    }
    @Test void sharesOnlyConsumerEvaluationsAndKeepsExternalModelPropertiesIsolated() throws Exception {
        Path cache = root.resolve("cache"), report = root.resolve("report"), evidence = report.resolve("evidence");
        Files.createDirectories(evidence);
        publish(cache,"local-value",""); publish(cache,"external-value","");
        Path a = publish(cache,"app",imports("${selected.artifact}"));
        Path parent = publish(cache,"parent",imports("${selected.artifact}"));
        Path external = publish(cache,"external",imports("${selected.artifact}"));
        var module = new Model.Module(); module.effective = new PomReader().read(a);
        module.sources.add(new SourcePom(new Coordinate("g","app","1"),"MODULE",a.toString(),"a.xml","",module.effective));
        module.sources.add(new SourcePom(new Coordinate("g","parent","1"),"PARENT_CACHE",parent.toString(),"parent.xml","",new PomReader().read(parent)));
        module.sources.add(new SourcePom(new Coordinate("g","external","1"),"BOM_CACHE",external.toString(),"external.xml","",new PomReader().read(external)));
        var calls = new ArrayList<String>();
        new PomGraphCollector((owner,expression) -> { calls.add(owner.kind()); return owner.kind().equals("BOM_CACHE") ? "external-value" : "local-value"; })
                .collect(module,new Configuration(null,root).forRepository(root),evidence,report,cache,true);
        assertEquals(List.of("MODULE","BOM_CACHE"),calls);
        assertEquals(List.of("local-value","local-value","external-value"),module.pomRelations.stream().map(r -> r.target().artifactId()).toList());
    }
    @Test void invalidBatchResponseLeavesCoordinatesUnknownWithoutRetryStorm() throws Exception {
        Path cache = root.resolve("cache"), report = root.resolve("report"), evidence = report.resolve("evidence");
        Files.createDirectories(evidence);
        Path a = publish(cache,"app",imports("${group.part}.${artifact.part}"));
        var module = new Model.Module(); module.effective = new PomReader().read(a);
        module.sources.add(new SourcePom(new Coordinate("g","app","1"),"MODULE",a.toString(),"a.xml","",module.effective));
        var calls = new ArrayList<String>();
        new PomGraphCollector((owner,expression) -> { calls.add(expression); return "invalid"; })
                .collect(module,new Configuration(null,root).forRepository(root),evidence,report,cache,true);
        assertEquals(1,calls.size());
        assertEquals("UNRESOLVED",module.pomRelations.getFirst().status());
        assertEquals("${group.part}.${artifact.part}",module.pomRelations.getFirst().target().artifactId());
    }
}
