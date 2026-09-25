package io.github.mavenlegacy;

import picocli.CommandLine;
import picocli.CommandLine.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Callable;
import static io.github.mavenlegacy.Model.*;

@Command(name = "maven-legacy-analyzer", mixinStandardHelpOptions = true, version = "0.1.0-SNAPSHOT",
        description = "Analyse locale des POM et des dépendances Maven.", subcommands = Main.Scan.class)
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

    @Command(name = "scan", mixinStandardHelpOptions = true, description = "Scanner un dépôt ou un dossier contenant plusieurs dépôts.")
    public static class Scan implements Callable<Integer> {
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
                if (!inventoryOnly) {
                    try {
                        var options = configuration.forRepository(Path.of(module.repository));
                        new Collector().collect(module, options, layout.evidenceDirectory(module), output, offline);
                    } catch (Exception ex) {
                        module.status = "PARTIAL";
                        module.issues.add(new Issue("CONFIG_ERROR", ex.getClass().getSimpleName() + ": " + ex.getMessage()));
                    }
                }
            }
            var report = Analysis.report(root.toString(), inventoryOnly ? "INVENTORY" : "MAVEN", scan.modules(), scan.issues());
            new ReportWriter().write(report, output);
            System.out.println("Rapport : " + output.resolve("index.html"));
            System.out.println("Résultats : " + report.counts());
            return scan.issues().isEmpty() && scan.modules().stream().noneMatch(m -> m.status.equals("FAILED") || m.status.equals("PARTIAL")) ? 0 : 2;
        }
    }
}
