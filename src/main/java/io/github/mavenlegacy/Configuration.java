package io.github.mavenlegacy;

import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.util.*;

public final class Configuration {
    public record Options(String javaHome, String settings, String globalSettings, List<String> profiles,
                          Map<String, String> properties, int timeoutSeconds, String helpPluginVersion,
                          String dependencyPluginVersion, List<String> alignmentGroups) {}
    private final JsonNode root;
    private final Path inputRoot;
    public Configuration(Path file, Path inputRoot) throws Exception {
        this.inputRoot = inputRoot;
        root = file == null ? new ObjectMapper().createObjectNode() : new ObjectMapper().readTree(file.toFile());
        if (!root.isObject()) throw new IllegalArgumentException("Configuration must be a JSON object");
    }
    public Options forRepository(Path repository) {
        var merged = new ObjectMapper().createObjectNode();
        if (root.path("defaults").isObject()) merged.setAll((com.fasterxml.jackson.databind.node.ObjectNode) root.path("defaults"));
        String key = inputRoot.relativize(repository).toString().replace('\\', '/');
        if (key.isEmpty()) key = ".";
        var perRepo = root.path("repositories").path(key);
        if (perRepo.isObject()) merged.setAll((com.fasterxml.jackson.databind.node.ObjectNode) perRepo);
        var profiles = new ArrayList<String>();
        merged.path("profiles").forEach(n -> profiles.add(n.asText()));
        var properties = new TreeMap<String, String>();
        merged.path("properties").fields().forEachRemaining(e -> properties.put(e.getKey(), e.getValue().asText()));
        var alignmentGroups = new ArrayList<String>();
        merged.path("alignmentGroups").forEach(n -> alignmentGroups.add(n.asText()));
        int timeout = merged.path("timeoutSeconds").asInt(180);
        if (timeout < 1) throw new IllegalArgumentException("timeoutSeconds must be positive");
        return new Options(merged.path("javaHome").asText(""), merged.path("settings").asText(""),
                merged.path("globalSettings").asText(""), profiles, properties, timeout,
                merged.path("helpPluginVersion").asText("3.4.1"), merged.path("dependencyPluginVersion").asText("3.8.1"),
                alignmentGroups);
    }
}
