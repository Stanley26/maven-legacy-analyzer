package io.github.mavenlegacy;

import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.util.*;
import static io.github.mavenlegacy.Model.*;

public final class DependencyTreeReader {
    public List<ResolvedDependency> read(Path file) throws Exception {
        JsonNode root = new ObjectMapper().readTree(file.toFile());
        if (root == null || !root.isObject() || !root.hasNonNull("artifactId"))
            throw new IllegalArgumentException("Invalid Maven dependency tree JSON");
        var result = new ArrayList<ResolvedDependency>();
        visit(root, new ArrayList<>(), result, true);
        return result;
    }
    private static void visit(JsonNode node, List<String> ancestors, List<ResolvedDependency> result, boolean root) {
        var coordinate = new Coordinate(node.path("groupId").asText(), node.path("artifactId").asText(), node.path("version").asText());
        var path = new ArrayList<>(ancestors);
        path.add(coordinate.gav());
        if (!root) result.add(new ResolvedDependency(coordinate, node.path("type").asText("jar"),
                node.path("classifier").asText(""), node.path("scope").asText("compile"),
                node.path("optional").asBoolean(false), List.copyOf(path)));
        for (var child : node.path("children")) visit(child, path, result, false);
    }
}
