package io.github.mavenlegacy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SavedReportsTest {
    @TempDir Path root;
    private Path oldReport() throws Exception {
        Path report = root.resolve("old-report"); Files.createDirectories(report.resolve("evidence/old-hash"));
        Files.writeString(report.resolve("analysis.json"), """
            {"schemaVersion":"0.1","generatedAt":"2025-01-01T00:00:00Z","root":"/missing/projects","mode":"MAVEN",
            "modules":[{"id":"app/pom.xml","repository":"/missing/projects/app","projectName":"app","modulePath":"pom.xml",
              "pomPath":"/missing/projects/app/pom.xml","wrapper":"/must-not-run/mvnw","status":"RESOLVED",
              "dependencies":[{"coordinate":{"groupId":"g","artifactId":"lib","version":"2"},"type":"jar","classifier":"","scope":"provided","optional":false,"path":["g:app:1","g:lib:2"]}],
              "evidence":{"effectivePom":"evidence/old-hash/effective-pom.xml","dependencyTree":"evidence/old-hash/tree.json"}}],
            "issues":[],"internalConsumers":[],"counts":{"RESOLVED":1}}
            """);
        Files.writeString(report.resolve("evidence/old-hash/effective-pom.xml"), "<project><groupId>g</groupId><artifactId>app</artifactId><version>1</version><dependencies><dependency><groupId>g</groupId><artifactId>lib</artifactId><version>2</version><!-- g:parent:1, line 8 --></dependency></dependencies></project>");
        Files.writeString(report.resolve("evidence/old-hash/tree.json"), "{\"saved\":true}");
        return report;
    }
    @Test void reusesOldSchemaAndOriginalEvidenceWithoutOriginalReposOrMaven() throws Exception {
        Path report = oldReport();
        byte[] json = Files.readAllBytes(report.resolve("analysis.json"));
        byte[] xml = Files.readAllBytes(report.resolve("evidence/old-hash/effective-pom.xml"));
        new SavedReports().render(report, null);
        assertArrayEquals(json, Files.readAllBytes(report.resolve("analysis.json")));
        assertArrayEquals(xml, Files.readAllBytes(report.resolve("evidence/old-hash/effective-pom.xml")));
        String html = Files.readString(report.resolve("details.html"));
        assertTrue(html.contains("2025-01-01T00:00:00Z"));
        assertTrue(html.contains("Pourquoi cette version ?"));
        assertTrue(Files.readString(report.resolve("index.html")).contains("Vue du parc"));
        assertTrue(Files.readString(report.resolve("explorer/data.js")).contains("2025-01-01T00:00:00Z"));
        String detail = Files.readString(report.resolve("evidence/app/root/why-versions.html"));
        assertTrue(detail.contains("Explication partielle"));
        assertTrue(detail.contains("Non collecté dans ce scan"));
        assertTrue(detail.contains("../../old-hash/effective-pom.xml.html#L1"));
        assertTrue(Files.readString(report.resolve("evidence/old-hash/effective-pom.xml.html")).contains("id='L1'"));
    }
    @Test void importsOldReportsAndCatalogKeepsMultipleScans() throws Exception {
        Path old = oldReport(), home = root.resolve("analyzer"), target = home.resolve("reports/imported");
        new SavedReports().render(old, target);
        assertArrayEquals(Files.readAllBytes(old.resolve("analysis.json")), Files.readAllBytes(target.resolve("analysis.json")));
        assertFalse(Files.exists(old.resolve("index.html")));
        var library = new ReportLibrary(home);
        library.register(target); library.register(old);
        String html = Files.readString(home.resolve("index.html"));
        assertTrue(html.contains("reports/imported/index.html"));
        assertTrue(html.contains("old-report"));
        assertEquals(2, library.reports().size());
    }
    @Test void refusesUnknownSchemaAndOutputInsideArchive() throws Exception {
        Path report = oldReport();
        assertThrows(IllegalArgumentException.class, () -> new SavedReports().render(report, report.resolve("nested")));
        Files.writeString(report.resolve("analysis.json"), "{\"schemaVersion\":\"99\"}");
        assertThrows(IllegalArgumentException.class, () -> new SavedReports().render(report, null));
    }
    @Test void restoresLabelsForEarliestReportsWithoutProjectFields() throws Exception {
        Path report = oldReport(), json = report.resolve("analysis.json");
        Files.writeString(json, Files.readString(json).replace(",\"projectName\":\"app\",\"modulePath\":\"pom.xml\"", ""));
        new SavedReports().render(report, null);
        assertTrue(Files.readString(report.resolve("explorer/data.js")).contains("app/pom.xml"));
        assertTrue(Files.isRegularFile(report.resolve("evidence/app/root/why-versions.html")));
    }
    @Test void alteredSourceSnapshotIsExcludedFromProofsWithoutChangingArchive() throws Exception {
        Path report = oldReport();
        var data = SavedReports.read(report);
        var module = data.modules().getFirst();
        Path source = report.resolve("parent.xml");
        Files.writeString(source, "<project><groupId>g</groupId><artifactId>parent</artifactId><version>1</version></project>");
        String hash = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)));
        module.sources.add(new Model.SourcePom(new Model.Coordinate("g", "parent", "1"), "PARENT_CACHE", "unavailable", "parent.xml", hash, new PomReader().read(source)));
        SavedReports.MAPPER.writeValue(report.resolve("analysis.json").toFile(), data);
        Files.writeString(source, "changed");
        byte[] original = Files.readAllBytes(report.resolve("analysis.json"));
        new SavedReports().render(report, null);
        assertTrue(Files.readString(report.resolve("explorer/data.js")).contains("SAVED_SOURCE_CHANGED"));
        assertArrayEquals(original, Files.readAllBytes(report.resolve("analysis.json")));
        assertEquals("changed", Files.readString(source));
    }
    @Test void rejectsEvidenceTraversalAndPreservesUnrelatedHomePage() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> ProvenanceWriter.safeResolve(root, "../outside.xml"));
        Path home = root.resolve("analyzer"); Files.createDirectories(home);
        Files.writeString(home.resolve("index.html"), "existing user content");
        assertThrows(IllegalArgumentException.class, () -> new ReportLibrary(home).register(oldReport()));
        assertEquals("existing user content", Files.readString(home.resolve("index.html")));
    }
}
