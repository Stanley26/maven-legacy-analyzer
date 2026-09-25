package io.github.mavenlegacy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ReportLayoutTest {
    @TempDir Path root;

    @Test void evidenceLinksAreReadableAndResolveToTheirFile() throws Exception {
        Path output = root.resolve("report");
        var layout = new ReportLayout(output);
        var module = module("project-a", "ear/pom.xml");
        Path directory = layout.evidenceDirectory(module);
        Files.createDirectories(directory);
        Path file = directory.resolve("effective-pom.xml");
        Files.writeString(file, "<project/>");
        String link = ReportLayout.relativeLink(output, file);
        assertEquals("evidence/project-a/ear/effective-pom.xml", link);
        assertTrue(Files.isSameFile(file, output.resolve(link)));
        assertEquals(directory, layout.evidenceDirectory(module));
    }

    @Test void normalizedNamesNeverMergeEvidenceFromDifferentModulesOrProjects() {
        var layout = new ReportLayout(root);
        var paths = new HashSet<Path>();
        paths.add(layout.evidenceDirectory(module("project-a", "pom.xml")));
        paths.add(layout.evidenceDirectory(module("project-a", "root/pom.xml")));
        paths.add(layout.evidenceDirectory(module("project-a", "a/b/pom.xml")));
        paths.add(layout.evidenceDirectory(module("project-a", "a--b/pom.xml")));
        paths.add(layout.evidenceDirectory(module("équipe", "pom.xml")));
        paths.add(layout.evidenceDirectory(module("equipe", "pom.xml")));
        assertEquals(6, paths.size());
        assertTrue(paths.stream().allMatch(p -> p.startsWith(root.resolve("evidence"))));
    }

    @Test void usesAnalyzerLocationAndSeparateDatedRuns() throws Exception {
        Path application = root.resolve("analyzer");
        Files.createDirectories(application.resolve("target/classes"));
        Files.writeString(application.resolve("pom.xml"), "<project/>");
        assertEquals(application, ReportLayout.applicationDirectory(application.resolve("target/analyzer.jar")));
        assertEquals(application, ReportLayout.applicationDirectory(application.resolve("target/classes")));
        assertEquals(root, ReportLayout.applicationDirectory(root.resolve("portable.jar")));
        var time = LocalDateTime.of(2026, 1, 2, 3, 4, 5);
        Path first = ReportLayout.nextRun(application, time);
        assertTrue(first.startsWith(application.resolve("reports")));
        Files.createDirectories(first);
        assertNotEquals(first, ReportLayout.nextRun(application, time));
    }

    @Test void evidenceCannotLinkOutsideReport() {
        assertThrows(IllegalArgumentException.class,
                () -> ReportLayout.relativeLink(root.resolve("report"), root.resolve("other.xml")));
    }

    private Model.Module module(String project, String pom) {
        var module = new Model.Module();
        module.projectName = project;
        module.repository = root.resolve(project).toString();
        module.id = project + "/" + pom;
        module.modulePath = pom;
        return module;
    }
}
