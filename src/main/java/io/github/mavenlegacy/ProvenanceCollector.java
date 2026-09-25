package io.github.mavenlegacy;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static io.github.mavenlegacy.Model.*;

/** Uses the project's Maven for context; reads already-resolved POMs without a second resolver. */
public final class ProvenanceCollector {
    public void collect(Model.Module module, Configuration.Options options, Path directory, Path report, boolean offline) throws Exception {
        snapshot(module, module.evidence.containsKey("rawPom") ? report.resolve(module.evidence.get("rawPom")) : Path.of(module.pomPath), module.effective == null ? module.declared.coordinate() : module.effective.coordinate(),
                "MODULE", directory, report);
        readRuntime(module, directory.resolve("effective-pom.log"));
        if (module.effective == null) return;
        Path profiles = directory.resolve("active-profiles.txt");
        invoke(module, options, directory, report, offline, "active-profiles", "active-profiles",
                List.of("-Doutput=" + profiles), () -> {
                    module.context.put("activeProfiles", Files.readString(profiles).trim());
                    module.evidence.put("activeProfiles", ReportLayout.relativeLink(report, profiles));
                });
        String cacheText = evaluate(module, options, directory, report, offline, "settings.localRepository", "local-repository");
        Path cache = cacheText == null ? null : Path.of(cacheText).toAbsolutePath().normalize();
        if (cache != null) module.context.put("localRepository", cache.toString());

        // Ask Maven for each parent file: folder ancestry and matching GAVs alone are not sufficient.
        String expression = "project";
        var seen = new HashSet<Path>();
        Coordinate expected = module.effective.parent() == null ? null : module.effective.parent().coordinate();
        for (int depth = 1; expected != null; depth++) {
            expression += ".parent";
            String value = evaluate(module, options, directory, report, offline, expression + ".file", "parent-" + depth);
            // Some Maven versions expose no file on remote parent projects. Keep that distinction in the evidence.
            String kind = value == null ? "PARENT_CACHE" : "PARENT";
            Path file = value == null ? (cache == null ? null : cachedPom(cache, expected)) : Path.of(value).toAbsolutePath().normalize();
            if (file == null || !Files.isRegularFile(file)) { missing(module, "Parent source unavailable: " + expected.gav()); break; }
            if (!seen.add(file)) break;
            Pom model = new PomReader().read(file);
            if (!identity(model).equals(expected)) { missing(module, "Parent source identity could not be verified: " + expected.gav()); break; }
            snapshot(module, file, expected, kind, directory, report);
            if (model.parent() == null) break;
            expected = model.parent().coordinate();
        }

        // Origin comments name the models contributing effective versions/properties, including imported BOMs.
        var coordinates = new LinkedHashMap<String, Coordinate>();
        for (var declaration : module.effective.dependencies()) addOrigin(coordinates, declaration.origin());
        for (var declaration : module.effective.managed()) addOrigin(coordinates, declaration.origin());
        for (var property : module.effective.properties()) if (property.profile().isBlank()) addOrigin(coordinates, property.origin());
        for (var coordinate : coordinates.values()) {
            if (module.sources.stream().anyMatch(s -> s.coordinate().equals(coordinate))) continue;
            Path file = cache == null ? null : cachedPom(cache, coordinate);
            if (file == null || !Files.isRegularFile(file)) { missing(module, "Source POM unavailable in Maven's reported local repository: " + coordinate.gav()); continue; }
            try {
                Pom model = new PomReader().read(file);
                if (!identity(model).equals(coordinate)) { missing(module, "Cached source identity could not be verified: " + coordinate.gav()); continue; }
                snapshot(module, file, coordinate, "MAVEN_CACHE", directory, report);
            } catch (Exception ex) { missing(module, "Cannot snapshot " + coordinate.gav() + ": " + ex.getMessage()); }
        }
        new PomGraphCollector().collect(module, options, directory, report, cache, offline);
    }

    private static void readRuntime(Model.Module module, Path log) throws Exception {
        if (!Files.isRegularFile(log)) return;
        var lines = new String(Files.readAllBytes(log), java.nio.charset.StandardCharsets.UTF_8).lines()
                .map(s -> s.replaceAll("\u001B\\[[;\\d]*m", "")).toList();
        lines.stream().filter(s -> s.startsWith("Apache Maven ")).findFirst().ifPresent(s -> module.context.put("mavenRuntime", s));
        lines.stream().filter(s -> s.startsWith("Java version:")).findFirst().ifPresent(s -> module.context.put("javaRuntime", s));
    }

    private static String evaluate(Model.Module module, Configuration.Options options, Path directory, Path report,
                                   boolean offline, String expression, String name) {
        Path output = directory.resolve(name + ".txt");
        String[] value = { null };
        invoke(module, options, directory, report, offline, "evaluate", name,
                List.of("-Dexpression=" + expression, "-Doutput=" + output), () -> {
                    String result = Files.readString(output).trim();
                    module.evidence.put(name, ReportLayout.relativeLink(report, output));
                    if (!result.isEmpty() && !result.equals("null object or invalid expression")) value[0] = result;
                });
        return value[0];
    }

    private static void invoke(Model.Module module, Configuration.Options options, Path directory, Path report,
                               boolean offline, String goal, String name, List<String> args, Collector.ReadResult read) {
        var parameters = new ArrayList<>(args);
        if (offline) parameters.add("-o");
        Collector.collectOne(module, new MavenRunner(), Path.of(module.wrapper), Path.of(module.pomPath),
                Path.of(module.repository), options, "org.apache.maven.plugins:maven-help-plugin:" + options.helpPluginVersion() + ":" + goal,
                parameters, directory.resolve(name + ".log"), report, read);
    }

    static Coordinate identity(Pom model) {
        Coordinate c = model.coordinate(), p = model.parent() == null ? new Coordinate("", "", "") : model.parent().coordinate();
        return new Coordinate(c.groupId().isBlank() ? p.groupId() : c.groupId(), c.artifactId(), c.version().isBlank() ? p.version() : c.version());
    }

    static Path cachedPom(Path cache, Coordinate c) {
        if (!c.concrete() || !c.groupId().matches("[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)*")
                || !safePart(c.artifactId()) || !safePart(c.version())) return null;
        Path file = cache.resolve(c.groupId().replace('.', '/')).resolve(c.artifactId()).resolve(c.version())
                .resolve(c.artifactId() + "-" + c.version() + ".pom").normalize();
        return file.startsWith(cache) ? file : null;
    }
    private static boolean safePart(String value) { return value.matches("[A-Za-z0-9_][A-Za-z0-9_.-]*"); }

    private static void addOrigin(Map<String, Coordinate> target, String origin) {
        var location = Provenance.origin(origin);
        if (location != null) target.putIfAbsent(location.coordinate().gav(), location.coordinate());
    }

    static void snapshot(Model.Module module, Path file, Coordinate coordinate, String kind, Path directory, Path report) throws Exception {
        if (module.sources.stream().anyMatch(s -> s.coordinate().equals(coordinate))) return;
        Path sources = directory.resolve("sources");
        Files.createDirectories(sources);
        String name = ReportLayout.segment(coordinate.artifactId() + "-" + coordinate.version(), "pom");
        Path copy = sources.resolve(name + ".xml");
        for (int i = 2; Files.exists(copy); i++) copy = sources.resolve(name + "-" + i + ".xml");
        byte[] bytes = Files.readAllBytes(file);
        Files.write(copy, bytes);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        module.sources.add(new SourcePom(coordinate, kind, kind.equals("MODULE") ? module.pomPath : file.toString(), ReportLayout.relativeLink(report, copy), sha, new PomReader().read(copy)));
    }
    private static void missing(Model.Module module, String message) { module.issues.add(new Issue("PROVENANCE_SOURCE_UNAVAILABLE", message)); }
}
