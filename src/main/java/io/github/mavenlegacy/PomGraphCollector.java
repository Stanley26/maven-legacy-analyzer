package io.github.mavenlegacy;

import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import static io.github.mavenlegacy.Model.*;

/** Copies the reachable raw POM graph, including imports whose entries disappear from effective POMs. */
public final class PomGraphCollector {
    private final Set<String> attempted = new HashSet<>();
    private final Map<String, String> evaluated = new HashMap<>();
    private Model.Module module;
    private Configuration.Options options;
    private Path directory, report, cache;
    private boolean offline;
    private int operation;

    public void collect(Model.Module module, Configuration.Options options, Path directory, Path report, Path cache, boolean offline) {
        this.module = module; this.options = options; this.directory = directory; this.report = report; this.cache = cache; this.offline = offline;
        for (var dependency : module.dependencies)
            obtain(module.effective.coordinate().gav(), dependency.coordinate(), "RESOLVED_DEPENDENCY", "", "ARTIFACT_CACHE");
        // The queue grows as raw parents and imports are copied. GAV deduplication also terminates cycles.
        for (int index = 0; index < module.sources.size(); index++) {
            SourcePom source = module.sources.get(index);
            Pom raw = source.model();
            if (raw.parent() != null) {
                Coordinate target = resolve(source, raw.parent().coordinate());
                obtain(source.coordinate().gav(), target, "PARENT", "", "EXTERNAL_PARENT_CACHE");
            }
            for (var declaration : raw.managed()) if (declaration.type().equals("pom") && declaration.scope().equals("import")) {
                Coordinate target = resolve(source, declaration.coordinate());
                obtain(source.coordinate().gav(), target, "BOM_IMPORT_DECLARATION", declaration.profile(), "BOM_CACHE");
            }
        }
        long missing = module.pomRelations.stream().filter(r -> !r.status().equals("COLLECTED")).count();
        module.context.put("pomGraph", Map.of("sources", module.sources.size(), "relations", module.pomRelations.size(), "unavailable", missing,
                "scope", "local POM, resolved dependency POMs, recursive parents and BOM import declarations (including profiles; collection does not prove activation)"));
    }

    private void obtain(String from, Coordinate coordinate, String relation, String profile, String kind) {
        String status = "COLLECTED", detail = "";
        if (!coordinate.concrete()) { status = "UNRESOLVED"; detail = "Coordinates contain an unresolved expression; no version guessed."; }
        else if (find(coordinate) == null && attempted.add(coordinate.gav())) {
            try {
                Path file = cache == null ? null : ProvenanceCollector.cachedPom(cache, coordinate);
                if (file == null || !Files.isRegularFile(file)) {
                    file = download(coordinate);
                    kind = kind.replace("CACHE", "MAVEN");
                }
                if (file != null && Files.isRegularFile(file)) {
                    Pom raw = new PomReader().read(file);
                    Coordinate identity = ProvenanceCollector.identity(raw);
                    // CI-friendly versions may stay symbolic in a raw POM. The Maven request records the resolved GAV.
                    if (identity.concrete() && !identity.equals(coordinate)) throw new IllegalArgumentException("POM identity differs from requested coordinates");
                    ProvenanceCollector.snapshot(module, file, coordinate, kind, directory, report);
                }
            } catch (Exception ex) { detail = ex.getClass().getSimpleName() + ": " + ex.getMessage(); }
        }
        if (status.equals("COLLECTED") && find(coordinate) == null) { status = "UNAVAILABLE"; if (detail.isBlank()) detail = "POM could not be retrieved using the project wrapper/settings; see evidence logs."; }
        module.pomRelations.add(new PomRelation(from, coordinate, relation, profile, status, detail));
        if (!status.equals("COLLECTED")) module.issues.add(new Issue("POM_SOURCE_UNAVAILABLE", relation + " " + coordinate.gav() + ": " + detail));
    }

    private Path download(Coordinate coordinate) throws Exception {
        // Validate before constructing filenames or Maven command arguments.
        if (ProvenanceCollector.cachedPom(directory, coordinate) == null) throw new IllegalArgumentException("Unsupported artifact coordinate syntax");
        if (cache == null) throw new IllegalStateException("Maven local repository location is unavailable");
        String name = "pom-" + (++operation) + "-" + ReportLayout.segment(coordinate.artifactId(), "artifact");
        // Resolve only into Maven's cache; copy the evidence ourselves so project copy-plugin destinations cannot apply.
        var parameters = new ArrayList<>(List.of("-Dartifact=" + coordinate.gav() + ":pom", "-Dtransitive=false", "-Dmdep.skip=false"));
        if (offline) parameters.add("-o");
        Path file = ProvenanceCollector.cachedPom(cache, coordinate);
        Collector.collectOne(module, new MavenRunner(), Path.of(module.wrapper), Path.of(module.pomPath), Path.of(module.repository), options,
                "org.apache.maven.plugins:maven-dependency-plugin:" + options.dependencyPluginVersion() + ":get", parameters,
                directory.resolve(name + ".log"), report, () -> {
                    if (!Files.isRegularFile(file)) throw new IllegalStateException("Maven did not produce the requested POM");
                });
        return file;
    }

    private Coordinate resolve(SourcePom owner, Coordinate coordinate) {
        return new Coordinate(interpolate(owner, coordinate.groupId()), interpolate(owner, coordinate.artifactId()), interpolate(owner, coordinate.version()));
    }

    private String interpolate(SourcePom owner, String value) {
        var matcher = Pattern.compile("\\$\\{([^}]+)}").matcher(value);
        var result = new StringBuilder();
        while (matcher.find()) {
            String property = matcher.group(1);
            String key = owner.coordinate().gav() + "\n" + property;
            String replacement = evaluated.computeIfAbsent(key, ignored -> evaluate(owner, property));
            matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(replacement == null || replacement.isBlank() ? matcher.group() : replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private String evaluate(SourcePom owner, String property) {
        try {
            String name = "coordinate-" + (++operation);
            Path output = directory.resolve(name + ".txt");
            var parameters = new ArrayList<>(List.of("-Dexpression=" + property, "-Doutput=" + output));
            // Inherited imports use the consumer context. Imported BOMs and dependencies use their own model.
            if (!owner.kind().equals("MODULE") && !owner.kind().startsWith("PARENT")) parameters.add("-Dartifact=" + owner.coordinate().gav());
            if (offline) parameters.add("-o");
            String[] value = { "" };
            Collector.collectOne(module, new MavenRunner(), Path.of(module.wrapper), Path.of(module.pomPath), Path.of(module.repository), options,
                    "org.apache.maven.plugins:maven-help-plugin:" + options.helpPluginVersion() + ":evaluate", parameters,
                    directory.resolve(name + ".log"), report, () -> {
                        String text = Files.readString(output).trim();
                        module.evidence.put(name, ReportLayout.relativeLink(report, output));
                        if (!text.equals("null object or invalid expression") && !text.contains("\n") && !text.contains("\r") && !text.startsWith("<")) value[0] = text;
                    });
            return value[0];
        } catch (Exception ex) { return ""; }
    }
    private SourcePom find(Coordinate coordinate) { return module.sources.stream().filter(s -> s.coordinate().equals(coordinate)).findFirst().orElse(null); }
}
