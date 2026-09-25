package io.github.mavenlegacy;

import java.nio.file.*;
import java.util.*;
import static io.github.mavenlegacy.Model.*;

public final class Collector {
    public void collect(Model.Module module, Configuration.Options options, Path evidenceDirectory, Path reportDirectory, boolean offline) {
        if (module.declared == null) return;
        if (module.issues.stream().anyMatch(i -> i.code().equals("GENERATED_LOCATION"))) return;
        if (module.wrapper == null) {
            module.status = "PARTIAL";
            module.issues.add(new Issue("NO_WRAPPER", "No platform-compatible Maven Wrapper found. No system Maven fallback was used."));
            return;
        }
        module.context.put("javaHome", options.javaHome().isBlank() ? "inherited environment" : options.javaHome());
        module.context.put("settings", options.settings().isBlank() ? "wrapper / .mvn/maven.config / Maven defaults" : options.settings());
        module.context.put("globalSettings", options.globalSettings());
        module.context.put("requestedProfiles", options.profiles());
        module.context.put("propertyNames", options.properties().keySet());
        module.context.put("offline", offline);
        module.context.put("alignmentGroups", options.alignmentGroups());
        module.context.put("reactorMode", "single module (-N); artifacts from siblings must already be available");
        module.context.put("provenanceCoverage", "effective element origins and raw local declarations; full override history not yet reconstructed");
        try {
            Files.createDirectories(evidenceDirectory);
            var runner = new MavenRunner();
            Path wrapper = Path.of(module.wrapper), pom = Path.of(module.pomPath), repo = Path.of(module.repository);
            Path effective = evidenceDirectory.resolve("effective-pom.xml");
            Path tree = evidenceDirectory.resolve("dependency-tree.json");
            var effectiveArgs = new ArrayList<>(List.of("-Dverbose=true", "-Doutput=" + effective));
            var treeArgs = new ArrayList<>(List.of("-DoutputType=json", "-Dverbose=false", "-DoutputFile=" + tree, "-DappendOutput=false"));
            if (offline) { effectiveArgs.add("-o"); treeArgs.add("-o"); }
            collectOne(module, runner, wrapper, pom, repo, options,
                    "org.apache.maven.plugins:maven-help-plugin:" + options.helpPluginVersion() + ":effective-pom",
                    effectiveArgs, evidenceDirectory.resolve("effective-pom.log"), reportDirectory, () -> {
                        module.effective = new PomReader().read(effective);
                        module.evidence.put("effectivePom", ReportLayout.relativeLink(reportDirectory, effective));
                    });
            collectOne(module, runner, wrapper, pom, repo, options,
                    "org.apache.maven.plugins:maven-dependency-plugin:" + options.dependencyPluginVersion() + ":tree",
                    treeArgs, evidenceDirectory.resolve("dependency-tree.log"), reportDirectory, () -> {
                        module.dependencies = new DependencyTreeReader().read(tree);
                        module.evidence.put("dependencyTree", ReportLayout.relativeLink(reportDirectory, tree));
                    });
            boolean resolved = module.effective != null && module.evidence.containsKey("dependencyTree");
            module.status = resolved ? "RESOLVED" : "PARTIAL";
            if (resolved) detectVersionDifferences(module, options.alignmentGroups());
        } catch (Exception ex) {
            module.status = "PARTIAL";
            module.issues.add(new Issue("COLLECTION_ERROR", ex.getClass().getSimpleName() + ": " + ex.getMessage()));
        }
    }
    @FunctionalInterface private interface ReadResult { void read() throws Exception; }
    private static void collectOne(Model.Module module, MavenRunner runner, Path wrapper, Path pom, Path repo,
                                   Configuration.Options options, String goal, List<String> args, Path log, Path reportDirectory, ReadResult read) {
        try {
            var invocation = runner.run(wrapper, pom, repo, options, goal, args, log);
            module.invocations.add(invocation);
            module.evidence.put(log.getFileName().toString(), ReportLayout.relativeLink(reportDirectory, log));
            if (invocation.exitCode() == 0 && !invocation.timedOut()) read.read();
            else module.issues.add(new Issue(invocation.timedOut() ? "MAVEN_TIMEOUT" : "MAVEN_FAILURE",
                    goal + ": exit=" + invocation.exitCode() + ". See local evidence log."));
        } catch (Exception ex) {
            module.issues.add(new Issue("MAVEN_ERROR", goal + ": " + ex.getClass().getSimpleName() + ": " + ex.getMessage()));
        }
    }
    private static void detectVersionDifferences(Model.Module module, List<String> groups) {
        for (String group : groups) {
            var versions = new TreeSet<String>();
            module.dependencies.stream().filter(d -> d.coordinate().groupId().equals(group))
                    .forEach(d -> versions.add(d.coordinate().version()));
            if (versions.size() > 1) module.issues.add(new Issue("VERSION_ALIGNMENT_DIFFERENCE",
                    "Multiple versions in configured alignment group " + group + ": " + versions + ". Review required; not a runtime compatibility verdict."));
        }
    }
}
