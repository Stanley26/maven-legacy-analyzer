package io.github.mavenlegacy;

import java.util.*;
import static io.github.mavenlegacy.Model.*;

/** Small, evidence-backed projection of the archived parent/BOM models. No resolver or source checkout access. */
final class StructureIndex {
    static Map<String, Object> module(Model.Module module) {
        Pom effective = module.effective != null ? module.effective : module.declared;
        Pom raw = module.declared;
        var own = module.sources.stream().filter(s -> s.kind().equals("MODULE")).findFirst();
        if (own.isPresent()) raw = own.get().model();
        var result = new LinkedHashMap<String, Object>();
        result.put("available", effective != null);
        result.put("packaging", effective == null ? "?" : effective.packaging());
        result.put("children", effective == null ? List.of() : effective.modules());
        result.put("treeKnown", module.status.equals("RESOLVED") && module.evidence.containsKey("dependencyTree"));
        String root = effective == null ? "" : ProvenanceCollector.identity(effective).gav();
        result.put("root", root);
        var models = new LinkedHashMap<String, Map<String, Object>>();
        for (var source : module.sources)
            models.put(source.coordinate().gav(), model(source.coordinate().gav(), source.model(), source.evidence(), module.pomRelations));
        if (effective != null) {
            var node = model(root, raw == null ? effective : raw, own.map(SourcePom::evidence).orElse(module.evidence.getOrDefault("rawPom", "")), module.pomRelations);
            // The direct parent is resolved by Maven; raw CI-friendly coordinates may still contain expressions.
            node.put("parent", effective.parent() == null ? "" : effective.parent().coordinate().gav());
            node.put("importsKnown", raw != null);
            models.put(root, node);
        }
        // Only retain the application's ancestry and recursively imported BOMs, not the parents of every dependency.
        var reachable = new ArrayList<Map<String, Object>>();
        var seen = new HashSet<String>();
        var pending = new ArrayDeque<String>();
        if (!root.isBlank()) pending.add(root);
        while (!pending.isEmpty()) {
            String gav = pending.removeFirst();
            if (!seen.add(gav)) continue;
            var node = models.get(gav);
            if (node == null) continue;
            reachable.add(node);
            String parent = (String) node.get("parent");
            if (!parent.isBlank()) pending.add(parent);
            @SuppressWarnings("unchecked") var imports = (List<Map<String, Object>>) node.get("imports");
            for (var imported : imports) pending.add((String) imported.get("gav"));
        }
        result.put("models", reachable);
        return result;
    }

    private static LinkedHashMap<String, Object> model(String gav, Pom pom, String evidence, List<PomRelation> relations) {
        var result = new LinkedHashMap<String, Object>();
        result.put("gav", gav);
        var parents = relations.stream().filter(r -> r.from().equals(gav) && r.relation().equals("PARENT"))
                .map(PomRelation::target).distinct().toList();
        result.put("parent", parents.size() == 1 ? parents.getFirst().gav() : pom.parent() == null ? "" : pom.parent().coordinate().gav());
        result.put("evidence", evidence);
        result.put("importsKnown", true);
        var imports = new ArrayList<Map<String, Object>>();
        var declarations = pom.managed().stream().filter(d -> d.type().equals("pom") && d.scope().equals("import")).toList();
        var resolved = relations.stream().filter(r -> r.from().equals(gav) && r.relation().equals("BOM_IMPORT_DECLARATION")).toList();
        for (int i = 0; i < declarations.size(); i++) {
            var d = declarations.get(i);
            // Collector records one relation per declaration in source order, including inactive profiles.
            Coordinate target = resolved.size() == declarations.size() ? resolved.get(i).target() : d.coordinate();
            imports.add(Map.of("gav", target.gav(), "profile", d.profile(), "line", d.line()));
        }
        result.put("imports", imports);
        return result;
    }
}
