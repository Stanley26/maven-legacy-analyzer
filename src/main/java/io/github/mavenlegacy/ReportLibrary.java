package io.github.mavenlegacy;

import java.nio.file.*;
import java.util.*;
import static io.github.mavenlegacy.ReportWriter.escape;

/** Local catalog, outside Git, of completed analyses. Paths refer only to local report directories. */
public final class ReportLibrary {
    private static final String MARKER = "<!-- maven-legacy-analyzer report library -->";
    private final Path home;
    public ReportLibrary(Path home) { this.home = home.toAbsolutePath().normalize(); }
    public static ReportLibrary installed() throws Exception {
        Path location = Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        return new ReportLibrary(ReportLayout.applicationDirectory(location));
    }
    public List<Path> reports() throws Exception {
        var paths = new LinkedHashSet<Path>();
        Path catalog = home.resolve("reports/catalog.json");
        if (Files.isRegularFile(catalog)) {
            var data = SavedReports.MAPPER.readTree(catalog.toFile());
            for (var entry : data) paths.add(home.resolve(entry.asText()).toAbsolutePath().normalize());
        }
        Path reports = home.resolve("reports");
        if (Files.isDirectory(reports)) try (var files = Files.walk(reports)) {
            files.filter(p -> p.getFileName().toString().equals("analysis.json")).sorted().forEach(p -> paths.add(p.getParent()));
        }
        return new ArrayList<>(paths);
    }
    public Path register(Path report) throws Exception {
        var paths = new LinkedHashSet<>(reports());
        if (report != null) paths.add(report.toAbsolutePath().normalize());
        Path index = home.resolve("index.html");
        if (Files.exists(index) && !Files.readString(index).contains(MARKER))
            throw new IllegalArgumentException("Existing index.html is not an analyzer catalog; it was left untouched");
        var body = new StringBuilder(MARKER).append("<h1>Maven Legacy Analyzer</h1><p>Analyses sauvegardées</p>")
                .append("<p class='muted'>Chaque rapport conserve les résultats de son scan. Régénérer les pages après une mise à jour ne relance pas Maven et ne met pas à jour les dépendances analysées.</p>")
                .append("<section><table><tr><th>Analyse</th><th>Date du scan</th><th>POM</th><th>Résultats</th></tr>");
        var stored = new ArrayList<String>();
        for (Path path : paths) {
            stored.add(location(path));
            try {
                var reportData = SavedReports.read(path);
                body.append("<tr><td><a href='").append(escape(url(path.resolve("index.html")))).append("'>")
                        .append(escape(path.getFileName().toString())).append("</a><br><span class='muted'>")
                        .append(escape(reportData.root())).append("</span></td><td>").append(escape(reportData.generatedAt()))
                        .append("</td><td>").append(reportData.modules().size()).append("</td><td>")
                        .append(escape(reportData.counts().toString())).append("</td></tr>");
            } catch (Exception ex) {
                body.append("<tr><td>").append(escape(path.toString())).append("</td><td colspan='3'>Analyse indisponible : ")
                        .append(escape(ex.getMessage())).append("</td></tr>");
            }
        }
        body.append("</table></section><p>Interface Web locale : <code>java -jar target/maven-legacy-analyzer.jar serve</code> puis <a href='http://127.0.0.1:8080'>http://127.0.0.1:8080</a>.</p>")
                .append("<p>Après une mise à jour : <code>java -jar target/maven-legacy-analyzer.jar refresh-reports</code></p>");
        Files.createDirectories(home.resolve("reports"));
        SavedReports.MAPPER.writerWithDefaultPrettyPrinter().writeValue(home.resolve("reports/catalog.json").toFile(), stored);
        Files.writeString(index, ProvenanceWriter.document("Maven Legacy Analyzer — analyses", body.toString()));
        return index;
    }
    private String location(Path path) {
        return path.startsWith(home) ? home.relativize(path).toString().replace('\\', '/') : path.toString();
    }
    private String url(Path path) {
        if (!path.startsWith(home)) return path.toUri().toASCIIString();
        return String.join("/", Arrays.stream(home.relativize(path).toString().replace('\\', '/').split("/"))
                .map(s -> java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20")).toList());
    }
}
