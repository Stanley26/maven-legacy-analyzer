package io.github.mavenlegacy;

import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import static io.github.mavenlegacy.Model.*;

/** Rebuilds presentation solely from saved data. Does not call Maven or access original repositories. */
public final class SavedReports {
    static final ObjectMapper MAPPER = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    static Report read(Path directory) throws Exception {
        var tree = MAPPER.readTree(directory.resolve("analysis.json").toFile());
        String schema = tree.path("schemaVersion").asText();
        if (!Set.of("0.1", "0.2").contains(schema)) throw new IllegalArgumentException("Unsupported report schema: " + schema);
        Report report = MAPPER.treeToValue(tree, Report.class);
        if (report.modules() == null || report.counts() == null) throw new IllegalArgumentException("Invalid saved report");
        return report;
    }

    public Path render(Path input, Path output) throws Exception {
        Path source = (Files.isDirectory(input) ? input : input.getParent()).toRealPath();
        Report report = read(source);
        Path destination = output == null ? source : output.toAbsolutePath().normalize();
        if (output != null) {
            Path existing = destination;
            while (!Files.exists(existing)) existing = existing.getParent();
            destination = existing.toRealPath().resolve(existing.relativize(destination));
        }
        if (!destination.equals(source)) {
            if (destination.startsWith(source) || source.startsWith(destination)) throw new IllegalArgumentException("Copy output must be separate from the saved report");
            if (Files.exists(destination)) try (var files = Files.list(destination)) {
                if (files.findAny().isPresent()) throw new IllegalArgumentException("Copy output must be new or empty");
            }
            Path copyRoot = destination;
            Files.walkFileTree(source, new SimpleFileVisitor<>() {
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws java.io.IOException {
                    Files.createDirectories(copyRoot.resolve(source.relativize(dir))); return FileVisitResult.CONTINUE;
                }
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws java.io.IOException {
                    if (attrs.isSymbolicLink()) throw new java.io.IOException("Symbolic links in saved reports are not supported");
                    Files.copy(file, copyRoot.resolve(source.relativize(file))); return FileVisitResult.CONTINUE;
                }
            });
        }
        var layout = new ReportLayout(destination);
        for (var module : report.modules()) {
            restoreNames(module);
            var retainedSources = new ArrayList<SourcePom>();
            for (var snapshot : module.sources) {
                Path file = ProvenanceWriter.safeResolve(destination, snapshot.evidence());
                if (!Files.isRegularFile(file)) {
                    module.issues.add(new Issue("SAVED_SOURCE_MISSING", "Source snapshot unavailable: " + snapshot.evidence()));
                    continue;
                }
                String hash = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
                if (!hash.equals(snapshot.sha256())) {
                    module.issues.add(new Issue("SAVED_SOURCE_CHANGED", "Source snapshot checksum differs: " + snapshot.evidence()));
                    continue;
                }
                retainedSources.add(snapshot);
            }
            module.sources = retainedSources;
            // Old JSON contains fewer line fields; the retained effective XML supplies them without Maven.
            String effective = module.evidence.get("effectivePom");
            if (effective != null && Files.isRegularFile(ProvenanceWriter.safeResolve(destination, effective))) {
                try { module.effective = new PomReader().read(ProvenanceWriter.safeResolve(destination, effective)); }
                catch (Exception ex) { module.issues.add(new Issue("SAVED_EVIDENCE_UNREADABLE", "Effective POM could not be read: " + ex.getMessage())); }
            }
            if (!module.dependencies.isEmpty() || !module.sources.isEmpty() || !module.pomRelations.isEmpty()) {
                Path page = layout.evidenceDirectory(module).resolve("why-versions.html");
                module.evidence.put("versionProvenance", ReportLayout.relativeLink(destination, page));
                module.explanations = new Provenance().explain(module);
            } else module.evidence.remove("versionProvenance");
            module.context.put("presentationMode", "Regenerated from saved analysis; no Maven invocation and no access to original source repositories");
        }
        // Preserve the original JSON, XML and logs byte-for-byte. Only presentation pages/assets are regenerated.
        new ReportWriter().render(report, destination);
        return destination;
    }

    private static void restoreNames(Model.Module module) {
        String repository = Objects.toString(module.repository, "project").replace('\\', '/').replaceAll("/+$", "");
        if (module.projectName == null || module.projectName.isBlank()) module.projectName = repository.substring(repository.lastIndexOf('/') + 1);
        if (module.modulePath == null || module.modulePath.isBlank()) {
            String pom = Objects.toString(module.pomPath, "").replace('\\', '/');
            module.modulePath = pom.startsWith(repository + "/") ? pom.substring(repository.length() + 1) : Objects.toString(module.id, "pom.xml");
        }
    }
}
