package io.github.mavenlegacy;

import java.nio.file.*;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Human-readable names, with deterministic numeric suffixes for filesystem collisions. */
public final class ReportLayout {
    private final Path output;
    private final Map<String, String> projects = new LinkedHashMap<>();
    private final Set<String> projectNames = new HashSet<>();
    private final Map<String, Set<String>> moduleNames = new HashMap<>();
    private final Map<String, Path> allocated = new HashMap<>();

    public ReportLayout(Path output) { this.output = output.toAbsolutePath().normalize(); }

    public Path evidenceDirectory(Model.Module module) {
        String key = module.repository + "\n" + module.id;
        return allocated.computeIfAbsent(key, ignored -> {
            String project = projects.computeIfAbsent(module.repository,
                    repository -> unique(segment(module.projectName, "project"), projectNames));
            Path source = Path.of(module.modulePath);
            String directory = source.getParent() == null ? "root" : source.getParent().toString().replace('\\', '/').replace("/", "--");
            String name = unique(segment(directory, "root"), moduleNames.computeIfAbsent(project, p -> new HashSet<>()));
            return output.resolve("evidence").resolve(project).resolve(name);
        });
    }

    static String relativeLink(Path output, Path file) {
        Path root = output.toAbsolutePath().normalize();
        Path absolute = file.toAbsolutePath().normalize();
        if (!absolute.startsWith(root)) throw new IllegalArgumentException("Evidence must remain inside the report directory");
        return root.relativize(absolute).toString().replace('\\', '/');
    }

    public static Path defaultOutput() throws Exception {
        Path location = Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        return nextRun(applicationDirectory(location), LocalDateTime.now());
    }

    static Path applicationDirectory(Path location) {
        Path absolute = location.toAbsolutePath().normalize();
        Path directory = Files.isDirectory(absolute) ? absolute : absolute.getParent();
        Path target = directory.getFileName().toString().equals("classes") ? directory.getParent() : directory;
        if (target.getFileName().toString().equals("target") && Files.isRegularFile(target.getParent().resolve("pom.xml")))
            return target.getParent();
        return directory;
    }

    static Path nextRun(Path applicationDirectory, LocalDateTime time) {
        String name = "scan-" + time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS"));
        Path reports = applicationDirectory.resolve("reports");
        Path candidate = reports.resolve(name);
        for (int index = 2; Files.exists(candidate); index++) candidate = reports.resolve(name + "-" + index);
        return candidate;
    }

    static String segment(String value, String fallback) {
        String normalized = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").replaceAll("[^A-Za-z0-9._-]+", "-")
                .replaceAll("^[. -]+|[. -]+$", "");
        if (normalized.isEmpty()) normalized = fallback;
        if (normalized.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?")) normalized = "module-" + normalized;
        return normalized.substring(0, Math.min(64, normalized.length())).replaceAll("[. ]+$", "");
    }

    private static String unique(String base, Set<String> used) {
        String candidate = base;
        for (int index = 2; !used.add(candidate.toLowerCase(Locale.ROOT)); index++) candidate = base + "-" + index;
        return candidate;
    }
}
