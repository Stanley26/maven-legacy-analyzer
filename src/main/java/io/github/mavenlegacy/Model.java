package io.github.mavenlegacy;

import java.util.*;

public final class Model {
    private Model() {}

    public record Coordinate(String groupId, String artifactId, String version) {
        public String ga() { return groupId + ":" + artifactId; }
        public String gav() { return ga() + ":" + version; }
        public boolean concrete() { return !groupId.isBlank() && !artifactId.isBlank() && !version.isBlank() && !gav().contains("${"); }
    }
    public record Parent(Coordinate coordinate, String relativePath) {}
    public record Declaration(Coordinate coordinate, String type, String classifier, String scope,
                              boolean optional, List<String> exclusions, String profile, int line, String origin,
                              int versionLine, String declarationOrigin) {}
    public record Property(String name, String value, String profile, int line, String origin) {}
    public record Pom(Coordinate coordinate, String packaging, Parent parent, List<String> modules,
                      List<Property> properties, List<Declaration> dependencies, List<Declaration> managed,
                      List<String> profiles) {}
    public record ResolvedDependency(Coordinate coordinate, String type, String classifier, String scope,
                                     boolean optional, List<String> path) {}
    public record Issue(String code, String message) {}
    public record Invocation(String goal, int exitCode, boolean timedOut, String log, long durationMillis) {}
    public record SourcePom(Coordinate coordinate, String kind, String originalPath, String evidence,
                            String sha256, Pom model) {}
    public record Fact(String kind, String text, String source, int line) {}
    public record VersionExplanation(ResolvedDependency dependency, String coverage, List<Fact> facts,
                                     List<String> limitations) {}
    public record PomRelation(String from, Coordinate target, String relation, String profile, String status, String detail) {}
    public static final class Module {
        public String id;
        public String repository;
        public String projectName;
        public String modulePath;
        public String pomPath;
        public String wrapper;
        public String status = "INVENTORIED";
        public Pom declared;
        public Pom effective;
        public List<ResolvedDependency> dependencies = new ArrayList<>();
        public List<Issue> issues = new ArrayList<>();
        public List<Invocation> invocations = new ArrayList<>();
        public Map<String, Object> context = new LinkedHashMap<>();
        public Map<String, String> evidence = new LinkedHashMap<>();
        public List<SourcePom> sources = new ArrayList<>();
        public List<VersionExplanation> explanations = new ArrayList<>();
        public List<PomRelation> pomRelations = new ArrayList<>();
        public String displayName() {
            return projectName == null || modulePath == null ? id : projectName + "/" + modulePath;
        }
    }
    public record Consumer(String module, String dependency, String scope, boolean direct, List<String> path) {}
    public record Report(String schemaVersion, String generatedAt, String root, String mode,
                         List<Module> modules, List<Issue> issues, List<Consumer> internalConsumers,
                         Map<String, Long> counts) {}
}
