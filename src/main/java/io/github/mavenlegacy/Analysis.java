package io.github.mavenlegacy;

import java.time.Instant;
import java.util.*;
import static io.github.mavenlegacy.Model.*;

public final class Analysis {
    private Analysis() {}
    public static Report report(String root, String mode, List<Model.Module> modules, List<Issue> issues) {
        var known = new HashSet<String>();
        for (var module : modules) {
            Pom pom = module.effective != null ? module.effective : module.declared;
            if (pom != null && pom.coordinate().concrete()) known.add(pom.coordinate().gav());
        }
        var consumers = new ArrayList<Consumer>();
        for (var module : modules) for (var dependency : module.dependencies)
            if (known.contains(dependency.coordinate().gav())) consumers.add(new Consumer(module.id,
                    dependency.coordinate().gav(), dependency.scope(), dependency.path().size() == 2, dependency.path()));
        var counts = new TreeMap<String, Long>();
        modules.forEach(m -> counts.merge(m.status, 1L, Long::sum));
        return new Report("0.2", Instant.now().toString(), root, mode, modules, issues, consumers, counts);
    }
}
