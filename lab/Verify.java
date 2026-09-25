import java.nio.file.*;
import java.lang.reflect.*;

/** Verifies actual Maven results using Jackson from the packaged analyzer's classpath. */
public class Verify {
    public static void main(String[] args) throws Exception {
        var mapperClass = Class.forName("com.fasterxml.jackson.databind.ObjectMapper");
        var mapper = mapperClass.getConstructor().newInstance();
        Object tree = mapperClass.getMethod("readTree", java.io.File.class).invoke(mapper, Path.of(args[0]).toFile());
        Method path = Class.forName("com.fasterxml.jackson.databind.JsonNode").getMethod("path", String.class);
        Method asText = Class.forName("com.fasterxml.jackson.databind.JsonNode").getMethod("asText");
        Method size = Class.forName("com.fasterxml.jackson.databind.JsonNode").getMethod("size");
        Object modules = path.invoke(tree, "modules");
        if ((int) size.invoke(modules) != 5) throw new AssertionError("Expected five discovered POMs");
        boolean alternativeVersion = false, provided = false, origin = false, alignmentDifference = false;
        boolean inheritedSource = false, propertyOverride = false, bomProperty = false, activeProfile = false;
        var sourceArtifacts = new java.util.HashSet<String>();
        boolean inactiveImport = false;
        for (Object module : (Iterable<?>) modules) {
            if (!asText.invoke(path.invoke(module, "status")).equals("RESOLVED")) throw new AssertionError("Incomplete Maven collection: " + module);
            String project = (String) asText.invoke(path.invoke(module, "projectName"));
            if (project.isBlank()) throw new AssertionError("Missing project label");
            Object context = path.invoke(module, "context");
            if (!asText.invoke(path.invoke(context, "provenanceCollection")).equals("COLLECTED")) throw new AssertionError("Incomplete provenance collection: " + module);
            if (((String) asText.invoke(path.invoke(context, "mavenRuntime"))).isBlank() || ((String) asText.invoke(path.invoke(context, "javaRuntime"))).isBlank())
                throw new AssertionError("Missing actual Maven/JDK context");
            activeProfile |= ((String) asText.invoke(path.invoke(context, "activeProfiles"))).contains("default-marker");
            for (Object source : (Iterable<?>) path.invoke(module, "sources")) {
                sourceArtifacts.add((String) asText.invoke(path.invoke(path.invoke(source, "coordinate"), "artifactId")));
                inheritedSource |= ((String) asText.invoke(path.invoke(source, "kind"))).startsWith("PARENT")
                        && asText.invoke(path.invoke(path.invoke(source, "coordinate"), "artifactId")).equals("base-parent");
            }
            for (Object relation : (Iterable<?>) path.invoke(module, "pomRelations")) {
                if (!asText.invoke(path.invoke(relation, "status")).equals("COLLECTED")) throw new AssertionError("Uncollected POM graph relation: " + relation);
                inactiveImport |= asText.invoke(path.invoke(relation, "profile")).equals("catalog-only");
            }
            for (Object explanation : (Iterable<?>) path.invoke(module, "explanations")) {
                boolean external = false, consumerProperty = false;
                for (Object fact : (Iterable<?>) path.invoke(explanation, "facts")) {
                    String kind = (String) asText.invoke(path.invoke(fact, "kind"));
                    propertyOverride |= kind.equals("PROPERTY_OVERRIDE");
                    external |= kind.equals("EXTERNAL_PROPERTY");
                    consumerProperty |= kind.equals("EFFECTIVE_PROPERTY");
                }
                if (external && consumerProperty) throw new AssertionError("Consumer properties leaked into BOM explanation");
                bomProperty |= external;
            }
            for (Object evidence : (Iterable<?>) path.invoke(module, "evidence")) {
                String link = (String) asText.invoke(evidence);
                if (!link.startsWith("evidence/" + project + "/") || !Files.isRegularFile(Path.of(args[0]).getParent().resolve(link)))
                    throw new AssertionError("Missing or unreadable evidence link: " + link);
            }
            for (Object dependency : (Iterable<?>) path.invoke(module, "dependencies")) {
                Object coordinate = path.invoke(dependency, "coordinate");
                alternativeVersion |= asText.invoke(path.invoke(coordinate, "artifactId")).equals("component-core") && asText.invoke(path.invoke(coordinate, "version")).equals("2.0.0");
                provided |= asText.invoke(path.invoke(dependency, "scope")).equals("provided");
            }
            for (Object dependency : (Iterable<?>) path.invoke(path.invoke(module, "effective"), "dependencies"))
                origin |= !((String) asText.invoke(path.invoke(dependency, "origin"))).isBlank();
            for (Object issue : (Iterable<?>) path.invoke(module, "issues"))
                alignmentDifference |= asText.invoke(path.invoke(issue, "code")).equals("VERSION_ALIGNMENT_DIFFERENCE");
        }
        if (!alternativeVersion || !provided || !origin || !alignmentDifference) throw new AssertionError("Missing override, provided scope, configured alignment difference or Maven origin evidence");
        if (!inheritedSource || !propertyOverride || !bomProperty || !activeProfile) throw new AssertionError("Missing parent source, property override, BOM property isolation or actual active profile");
        if (!sourceArtifacts.containsAll(java.util.List.of("nested-bom", "bom-parent", "platform-bom", "catalog-bom", "shared-utils", "platform-core", "component-context", "component-core", "server-api")) || !inactiveImport)
            throw new AssertionError("Missing recursive raw POM graph, dependency POM or inactive-profile import: " + sourceArtifacts);
        if ((int) size.invoke(path.invoke(tree, "internalConsumers")) < 2) throw new AssertionError("Missing internal consumers");
        System.out.println("LAB PASS: 5 POMs, inherited provenance, isolated BOM property, active profile, Maven/JDK context, provided scope, consumers.");
    }
}
