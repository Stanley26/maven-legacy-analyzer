package io.github.mavenlegacy;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import static io.github.mavenlegacy.Model.*;

/** Traverses all depths, without assuming that directory nesting means Maven inheritance. */
public final class Scanner {
    public record Result(List<Model.Module> modules, List<Issue> issues) {}
    public Result scan(Path input, Path output) throws IOException {
        Path root = input.toRealPath();
        var modules = new ArrayList<Model.Module>();
        var issues = new ArrayList<Issue>();
        var reader = new PomReader();
        boolean singleRepository = Files.isRegularFile(root.resolve("pom.xml"));
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.getFileName().toString().equals(".git") || dir.equals(output)) return FileVisitResult.SKIP_SUBTREE;
                return FileVisitResult.CONTINUE;
            }
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (!attrs.isRegularFile() || !file.getFileName().toString().equalsIgnoreCase("pom.xml")) return FileVisitResult.CONTINUE;
                var module = new Model.Module();
                module.id = root.relativize(file).toString().replace('\\', '/');
                module.pomPath = file.toString();
                var relative = root.relativize(file);
                Path repository = singleRepository || relative.getNameCount() == 1 ? root : root.resolve(relative.getName(0));
                module.repository = repository.toString();
                Path wrapper = findWrapper(file.getParent(), repository);
                module.wrapper = wrapper == null ? null : wrapper.toString();
                if (relative.toString().replace('\\', '/').contains("/target/"))
                    module.issues.add(new Issue("GENERATED_LOCATION", "POM found under target; inventoried but skipped for Maven execution."));
                try { module.declared = reader.read(file); }
                catch (Exception ex) {
                    module.status = "FAILED";
                    module.issues.add(new Issue("INVALID_POM", ex.getClass().getSimpleName() + ": " + ex.getMessage()));
                }
                modules.add(module);
                return FileVisitResult.CONTINUE;
            }
            public FileVisitResult visitFileFailed(Path file, IOException ex) {
                issues.add(new Issue("UNREADABLE_PATH", root.relativize(file) + ": " + ex.getClass().getSimpleName()));
                return FileVisitResult.CONTINUE;
            }
        });
        modules.sort(Comparator.comparing(m -> m.id));
        return new Result(modules, issues);
    }
    static Path findWrapper(Path start, Path repository) {
        String name = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "mvnw.cmd" : "mvnw";
        for (Path dir = start; dir != null && dir.startsWith(repository); dir = dir.getParent()) {
            if (Files.isRegularFile(dir.resolve(name))) return dir.resolve(name);
        }
        return null;
    }
}
