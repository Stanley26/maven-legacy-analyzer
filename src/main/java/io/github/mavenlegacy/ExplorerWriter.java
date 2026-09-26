package io.github.mavenlegacy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import static io.github.mavenlegacy.Model.*;

/** Compact index and lazy module evidence; original analysis files remain the source of truth. */
public final class ExplorerWriter {
    private static final ObjectMapper JSON = new ObjectMapper();
    public void write(Report report, Path output) throws Exception {
        Path assets = ProvenanceWriter.safeResolve(output, "explorer");
        Files.createDirectories(assets);
        for (String name : List.of("app.js", "engine.js", "structures.js", "style.css")) Files.write(ProvenanceWriter.safeResolve(output, "explorer/" + name), resource(name));
        Files.write(ProvenanceWriter.safeResolve(output, "index.html"), resource("index.html"));
        String index = safeJson(index(report));
        Files.writeString(ProvenanceWriter.safeResolve(output, "explorer/data.js"), "window.MLA_DATA=" + index + ";");
        Files.writeString(ProvenanceWriter.safeResolve(output, "explorer/data.json"), index);
        for (int i = 0; i < report.modules().size(); i++) {
            var m = report.modules().get(i);
            var data = new LinkedHashMap<String, Object>();
            data.put("dependencies", m.dependencies); data.put("explanations", m.explanations);
            data.put("context", m.context); data.put("evidence", m.evidence);
            data.put("sources", m.sources.stream().map(s -> Map.of("gav", s.coordinate().gav(), "kind", s.kind(), "evidence", s.evidence(), "sha256", s.sha256())).toList());
            data.put("relations", m.pomRelations);
            Files.writeString(ProvenanceWriter.safeResolve(output, "explorer/module-" + i + ".js"), "window.MLA_DETAILS=window.MLA_DETAILS||{};window.MLA_DETAILS[" + i + "]=" + safeJson(data) + ";");
        }
    }

    static Map<String, Object> index(Report report) {
        var projects = new ArrayList<Map<String, Object>>();
        var projectIds = new LinkedHashMap<String, Integer>();
        var modules = new ArrayList<Map<String, Object>>();
        var coordinates = new ArrayList<Map<String, Object>>();
        var coordinateIds = new HashMap<String, Integer>();
        var nodes = new ArrayList<String>(); var nodeIds = new HashMap<String, Integer>();
        var uses = new ArrayList<List<Object>>();
        var known = new HashSet<String>();
        for (var m : report.modules()) {
            var p = m.effective != null ? m.effective : m.declared;
            if (p != null && p.coordinate().concrete()) known.add(p.coordinate().gav());
        }
        for (int i = 0; i < report.modules().size(); i++) {
            var m = report.modules().get(i);
            String key = Objects.toString(m.repository, Objects.toString(m.id, "project"));
            int project = projectIds.computeIfAbsent(key, k -> {
                int id = projects.size(); projects.add(Map.of("id", id, "name", Objects.toString(m.projectName, Objects.toString(m.id, "project")))); return id;
            });
            var p = m.effective != null ? m.effective : m.declared;
            var module = new LinkedHashMap<String, Object>();
            module.put("id", i); module.put("project", project); module.put("label", m.displayName());
            module.put("path", Objects.toString(m.modulePath, m.id));
            module.put("gav", p == null ? "" : p.coordinate().gav()); module.put("status", m.status);
            module.put("issues", m.issues); module.put("why", m.evidence.get("versionProvenance"));
            module.put("provenance", Objects.toString(m.context.get("provenanceCollection"), "UNKNOWN"));
            module.put("structure", StructureIndex.module(m));
            modules.add(module);
            for (int j = 0; j < m.dependencies.size(); j++) {
                var d = m.dependencies.get(j);
                String ck = d.coordinate().gav() + ":" + d.type() + ":" + d.classifier();
                int c = coordinateIds.computeIfAbsent(ck, ignored -> {
                    int id = coordinates.size();
                    coordinates.add(Map.of("ga", d.coordinate().ga(), "v", d.coordinate().version(), "type", d.type(), "classifier", d.classifier(), "internal", known.contains(d.coordinate().gav())));
                    return id;
                });
                var path = d.path().stream().map(n -> nodeIds.computeIfAbsent(n, ignored -> { int id = nodes.size(); nodes.add(n); return id; })).toList();
                var explanation = j < m.explanations.size() ? m.explanations.get(j) : null;
                boolean override = explanation != null && explanation.facts().stream().anyMatch(f -> f.kind().equals("PROPERTY_OVERRIDE"));
                String origin = origin(m, explanation);
                // [module, coordinate, scope, path node IDs, documented override, origin category, dependency index]
                uses.add(List.of(i, c, d.scope(), path, override, origin, j));
            }
        }
        return Map.of("date", report.generatedAt(), "mode", report.mode(), "root", report.root(), "projects", projects,
                "modules", modules, "coordinates", coordinates, "nodes", nodes, "uses", uses, "issues", report.issues());
    }

    private static String origin(Model.Module module, VersionExplanation explanation) {
        if (explanation == null) return "unknown";
        for (var fact : explanation.facts()) if (Set.of("MANAGED_SOURCE", "DECLARED_SOURCE").contains(fact.kind())) {
            var source = module.sources.stream().filter(s -> Objects.equals(s.evidence(), fact.source())).findFirst();
            if (source.isPresent()) {
                if (source.get().kind().equals("MODULE")) return fact.kind().equals("MANAGED_SOURCE") ? "local-management" : "local";
                if (source.get().kind().startsWith("PARENT")) return "parent";
                boolean bom = module.pomRelations.stream().anyMatch(r -> r.relation().equals("BOM_IMPORT_DECLARATION") && r.target().equals(source.get().coordinate()));
                return bom ? "bom" : "external-management";
            }
        }
        return "unknown";
    }
    static String safeJson(Object value) throws Exception {
        return JSON.writeValueAsString(value).replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026")
                .replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
    }
    private static byte[] resource(String name) throws Exception {
        try (var input = ExplorerWriter.class.getResourceAsStream("/explorer/" + name)) {
            if (input == null) throw new IllegalStateException("Missing explorer asset: " + name);
            return input.readAllBytes();
        }
    }
}
