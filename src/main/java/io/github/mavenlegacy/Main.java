package io.github.mavenlegacy;

import picocli.CommandLine;
import picocli.CommandLine.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Callable;
import static io.github.mavenlegacy.Model.*;

@Command(name = "maven-legacy-analyzer", mixinStandardHelpOptions = true, version = "0.3.0-SNAPSHOT",
        description = "Analyse locale des POM et des dépendances Maven.", subcommands = { Main.Scan.class, Main.Render.class, Main.Refresh.class, Main.Serve.class })
public class Main implements Runnable {
    public static void main(String[] args) {
        var cli = new CommandLine(new Main());
        cli.setExecutionExceptionHandler((ex, command, result) -> {
            command.getErr().println("Erreur : " + ex.getMessage());
            return 2;
        });
        System.exit(cli.execute(args));
    }
    public void run() { CommandLine.usage(this, System.out); }

    @Command(name = "serve", mixinStandardHelpOptions = true, description = "Explorer les analyses sauvegardées dans une interface Web locale.")
    public static class Serve implements Callable<Integer> {
        @Parameters(index = "0", arity = "0..1", description = "Rapport à ouvrir ; sinon utiliser le catalogue de l'analyseur") Path report;
        @Option(names = "--port", defaultValue = "8080", description = "Port local (8080 par défaut)") int port;
        public Integer call() throws Exception {
            if (port < 0 || port > 65535) throw new IllegalArgumentException("Le port doit être compris entre 0 et 65535");
            var paths = report == null ? ReportLibrary.installed().reports() : List.of(Files.isDirectory(report) ? report : report.toAbsolutePath().getParent());
            try (var server = new LocalServer(paths, port)) {
                server.start();
                Runtime.getRuntime().addShutdownHook(new Thread(server::close));
                System.out.println("Interface locale : http://127.0.0.1:" + server.port());
                System.out.println("Analyses sauvegardées uniquement. Aucun appel Maven. Ctrl+C pour arrêter.");
                new java.util.concurrent.CountDownLatch(1).await();
            }
            return 0;
        }
    }

    @Command(name = "scan", mixinStandardHelpOptions = true, description = "Scanner un dépôt ou un dossier contenant plusieurs dépôts.")
    public static class Scan implements Callable<Integer> {
        private final ReportLibrary library;
        public Scan() { this(null); }
        Scan(ReportLibrary library) { this.library = library; }
        @Parameters(index = "0", description = "Dossier à analyser") Path root;
        @Option(names = {"-o", "--output"}, description = "Dossier de sortie neuf ou vide ; par défaut : reports/scan-<date> dans le dossier de l'analyseur") Path output;
        @Option(names = "--inventory-only", description = "Lire les POM sans lancer leurs wrappers") boolean inventoryOnly;
        @Option(names = "--offline", description = "Passer -o à Maven (cache déjà préparé)") boolean offline;
        @Option(names = "--config", description = "Fichier JSON de configuration par dépôt") Path config;

        public Integer call() throws Exception {
            root = root.toRealPath();
            if (!Files.isDirectory(root)) throw new IllegalArgumentException("Input must be a directory");
            if (output == null) output = ReportLayout.defaultOutput();
            output = output.toAbsolutePath().normalize();
            if (Files.exists(output)) output = output.toRealPath();
            if (output.equals(root) || root.startsWith(output)) throw new IllegalArgumentException("Output cannot contain the input root");
            if (Files.exists(output)) try (var entries = Files.list(output)) {
                if (entries.findAny().isPresent()) throw new IllegalArgumentException("Output must be empty; use a new directory per scan");
            }
            var configuration = new Configuration(config, root);
            var scan = new Scanner().scan(root, output);
            if (scan.modules().isEmpty()) {
                System.err.println("Aucun pom.xml trouvé.");
                return 2;
            }
            Files.createDirectories(output);
            output = output.toRealPath();
            var layout = new ReportLayout(output);
            int index = 0;
            for (var module : scan.modules()) {
                System.out.printf("[%d/%d] %s%n", ++index, scan.modules().size(), module.displayName());
                Path moduleEvidence = layout.evidenceDirectory(module);
                Files.createDirectories(moduleEvidence);
                Path rawPom = moduleEvidence.resolve("raw-pom.xml");
                try {
                    Files.copy(Path.of(module.pomPath), rawPom);
                    module.evidence.put("rawPom", ReportLayout.relativeLink(output, rawPom));
                } catch (Exception ex) { module.issues.add(new Issue("RAW_POM_COPY_ERROR", ex.getMessage())); }
                if (!inventoryOnly) {
                    try {
                        var options = configuration.forRepository(Path.of(module.repository));
                        new Collector().collect(module, options, moduleEvidence, output, offline);
                    } catch (Exception ex) {
                        module.status = "PARTIAL";
                        module.issues.add(new Issue("CONFIG_ERROR", ex.getClass().getSimpleName() + ": " + ex.getMessage()));
                    }
                }
            }
            var report = Analysis.report(root.toString(), inventoryOnly ? "INVENTORY" : "MAVEN", scan.modules(), scan.issues());
            new ReportWriter().write(report, output);
            try { System.out.println("Accueil : " + (library == null ? ReportLibrary.installed() : library).register(output)); }
            catch (Exception ex) { System.err.println("Catalogue non mis à jour : " + ex.getMessage()); }
            System.out.println("Rapport : " + output.resolve("index.html"));
            System.out.println("Résultats : " + report.counts());
            return scan.issues().isEmpty() && scan.modules().stream().noneMatch(m -> m.status.equals("FAILED") || m.status.equals("PARTIAL")
                    || "PARTIAL".equals(m.context.get("provenanceCollection")) || m.issues.stream().anyMatch(i -> i.code().equals("RAW_POM_COPY_ERROR"))) ? 0 : 2;
        }
    }

    @Command(name = "report", mixinStandardHelpOptions = true, description = "Régénérer le HTML depuis une analyse sauvegardée, sans lancer Maven.")
    public static class Render implements Callable<Integer> {
        @Parameters(index = "0", description = "Dossier du rapport ou fichier analysis.json") Path input;
        @Option(names = {"-o", "--output"}, description = "Copier l'analyse dans un dossier neuf avant de régénérer ; sinon mettre à jour son HTML sur place") Path output;
        public Integer call() throws Exception {
            Path report = new SavedReports().render(input.toAbsolutePath(), output);
            System.out.println("Rapport régénéré sans Maven : " + report.resolve("index.html"));
            System.out.println("Accueil : " + ReportLibrary.installed().register(report));
            return 0;
        }
    }

    @Command(name = "refresh-reports", mixinStandardHelpOptions = true, description = "Régénérer tous les rapports du catalogue et du dossier reports, sans lancer Maven.")
    public static class Refresh implements Callable<Integer> {
        public Integer call() throws Exception {
            var library = ReportLibrary.installed();
            int failures = 0, completed = 0;
            for (Path report : library.reports()) {
                try { new SavedReports().render(report, null); completed++; System.out.println("Régénéré : " + report); }
                catch (Exception ex) { failures++; System.err.println("Échec : " + report + " : " + ex.getMessage()); }
            }
            System.out.println("Accueil : " + library.register(null));
            System.out.printf("%d rapport(s) régénéré(s), %d échec(s). Aucun appel Maven.%n", completed, failures);
            return failures == 0 ? 0 : 2;
        }
    }
}
