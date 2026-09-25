package io.github.mavenlegacy;

import java.util.*;
import java.util.regex.Pattern;
import static io.github.mavenlegacy.Model.*;

/** Explains observed Maven output. It never replays dependency mediation or profile activation. */
public final class Provenance {
    record Origin(Coordinate coordinate, int line) {}
    private record Located(SourcePom source, Declaration declaration, boolean managed) {}
    private static final Pattern ORIGIN = Pattern.compile("^([^:, ]+):([^:, ]+):([^, ]+), line (\\d+)(?:,.*)?$");
    private static final Pattern PROPERTY = Pattern.compile("^\\$\\{([^}]+)}$");

    static Origin origin(String text) {
        if (text == null) return null;
        var matcher = ORIGIN.matcher(text.trim());
        if (!matcher.matches()) return null;
        try { return new Origin(new Coordinate(matcher.group(1), matcher.group(2), matcher.group(3)), Integer.parseInt(matcher.group(4))); }
        catch (NumberFormatException ex) { return null; }
    }

    public List<VersionExplanation> explain(Model.Module module) {
        var results = new ArrayList<VersionExplanation>();
        for (var dependency : module.dependencies) {
            var facts = new ArrayList<Fact>();
            var limits = new ArrayList<String>();
            boolean direct = dependency.path().size() == 2;
            facts.add(new Fact("INTRODUCTION", (direct ? "Dépendance directe" : "Dépendance transitive") + " : "
                    + String.join(" → ", dependency.path()), module.evidence.get("dependencyTree"), 0));
            if (!direct && dependency.path().size() > 2) {
                String introducer = dependency.path().get(dependency.path().size() - 2);
                SourcePom source = unique(module.sources.stream().filter(s -> s.coordinate().gav().equals(introducer)).toList());
                if (source != null) {
                    Declaration declared = unique(source.model().dependencies().stream().filter(d -> d.profile().isBlank() && matches(d, dependency)).toList());
                    if (declared != null) facts.add(new Fact("INTRODUCER_DECLARATION", "Le POM brut de " + introducer + " déclare ce composant avec "
                            + (declared.coordinate().version().isBlank() ? "une version gérée ailleurs" : "la version demandée " + declared.coordinate().version())
                            + ". Cette déclaration est un élément de preuve ; la médiation finale n'est pas reconstruite.", source.evidence(), declared.versionLine() > 0 ? declared.versionLine() : declared.line()));
                }
            }
            Declaration effective = module.effective == null ? null : unique((direct ? module.effective.dependencies() : module.effective.managed()).stream()
                    .filter(d -> d.profile().isBlank() && matches(d, dependency) && d.coordinate().version().equals(dependency.coordinate().version())).toList());
            boolean linked = false;
            if (effective != null) {
                facts.add(new Fact(direct ? "EFFECTIVE_DECLARATION" : "EFFECTIVE_MANAGEMENT",
                        (direct ? "Version dans la déclaration effective : " : "Version gérée dans le modèle effectif : ")
                                + effective.coordinate().version() + (effective.origin().isBlank() ? "" : ". Origine Maven : " + effective.origin()),
                        module.evidence.get("effectivePom"), effective.versionLine()));
                Located raw = locate(module, effective, dependency);
                if (raw != null) {
                    linked = true;
                    var d = raw.declaration();
                    facts.add(new Fact(raw.managed() ? "MANAGED_SOURCE" : "DECLARED_SOURCE",
                            (raw.managed() ? "Version définie dans dependencyManagement" : "Version déclarée sur la dépendance")
                                    + " par " + raw.source().coordinate().gav() + " : " + d.coordinate().version()
                                    + (d.profile().isBlank() ? "" : " (profil " + d.profile() + ")"), raw.source().evidence(), d.versionLine()));
                    explainProperty(module, raw, effective, facts, limits);
                    if (raw.source().kind().contains("CACHE"))
                        limits.add("La copie du cache correspond aux coordonnées et à la ligne citées par Maven. Le dépôt distant d'origine et l'identité du contenu au moment de la résolution ne sont pas attestés.");
                } else limits.add("Le commentaire d'origine n'a pas pu être relié sans ambiguïté à une déclaration versionnée dans les sources copiées.");
            } else limits.add(direct ? "Déclaration effective correspondante indisponible ou ambiguë."
                    : "L'arbre prouve ce chemin transitif. Le modèle effectif du composant introducteur et les règles qui ont départagé les versions ne sont pas reconstruits.");
            if (!direct && effective != null) limits.add("La gestion effective correspond à la version sélectionnée ; les versions demandées sur les autres chemins ne sont pas recensées.");
            limits.add("Les chemins évincés, les exclusions et l'historique complet de médiation ne sont pas reconstruits. Aucune conclusion sur les archives ou le runtime.");
            results.add(new VersionExplanation(dependency, linked ? "SOURCE_LINKED" : "PARTIAL", facts, limits));
        }
        return results;
    }

    private static Located locate(Model.Module module, Declaration effective, ResolvedDependency dependency) {
        Origin location = origin(effective.origin());
        if (location == null) return null;
        var candidates = new ArrayList<Located>();
        for (var source : module.sources) if (source.coordinate().equals(location.coordinate())) {
            for (var d : source.model().dependencies()) if (matches(d, dependency) && d.versionLine() == location.line()) candidates.add(new Located(source, d, false));
            for (var d : source.model().managed()) if (matches(d, dependency) && d.versionLine() == location.line()) candidates.add(new Located(source, d, true));
        }
        return unique(candidates);
    }

    private static void explainProperty(Model.Module module, Located raw, Declaration effective, List<Fact> facts, List<String> limits) {
        String expression = raw.declaration().coordinate().version();
        var match = PROPERTY.matcher(expression);
        if (!match.matches()) {
            if (expression.contains("${")) limits.add("Expression composée conservée telle quelle ; sa chaîne de substitutions n'est pas reconstruite : " + expression);
            return;
        }
        String name = match.group(1);
        if (!inherited(raw.source())) {
            facts.add(new Fact("EXTERNAL_PROPERTY", "L'expression " + expression + " appartient au modèle externe " + raw.source().coordinate().gav()
                    + ". Les propriétés du projet consommateur ne lui sont pas appliquées par cet analyseur.", raw.source().evidence(), raw.declaration().versionLine()));
            for (var p : raw.source().model().properties()) if (p.name().equals(name)) facts.add(new Fact("PROPERTY_CANDIDATE",
                    "Déclaration dans ce modèle : " + name + " = " + p.value() + (p.profile().isBlank() ? "" : " (profil " + p.profile() + ")"), raw.source().evidence(), p.line()));
            limits.add("L'interpolation du modèle externe, ses parents et ses profils ne sont pas reconstruits ; seule la valeur effective fournie par Maven est retenue.");
            return;
        }
        Property property = unique(module.effective.properties().stream().filter(p -> p.profile().isBlank() && p.name().equals(name)).toList());
        if (property == null) { limits.add("Origine de " + expression + " indisponible (propriété de commande, settings, expression spéciale ou autre entrée Maven possible)."); return; }
        Origin location = origin(property.origin());
        SourcePom winner = location == null ? null : unique(module.sources.stream().filter(s -> s.coordinate().equals(location.coordinate())
                && inherited(s) && s.model().properties().stream().filter(p -> p.name().equals(name) && p.line() == location.line()).count() == 1).toList());
        facts.add(new Fact("EFFECTIVE_PROPERTY", "Propriété dans le modèle effectif : " + name + " = " + property.value()
                + (property.origin().isBlank() ? "" : ". Origine Maven : " + property.origin()),
                winner == null ? module.evidence.get("effectivePom") : winner.evidence(), winner == null ? property.line() : location.line()));
        if (!property.value().equals(effective.coordinate().version())) {
            limits.add("La propriété effective diffère de la version interpolée. Une entrée externe ou une autre substitution intervient ; sa priorité n'est pas déduite.");
            return;
        }
        if (winner != null) {
            int winnerIndex = module.sources.indexOf(winner);
            for (int i = winnerIndex + 1; i < module.sources.size(); i++) {
                SourcePom ancestor = module.sources.get(i);
                if (!ancestor.kind().startsWith("PARENT")) continue;
                for (var p : ancestor.model().properties()) if (p.profile().isBlank() && p.name().equals(name) && !p.value().equals(property.value()))
                    facts.add(new Fact("PROPERTY_OVERRIDE", "Redéfinition observée dans le modèle effectif : " + name + " = " + property.value()
                            + " (" + winner.coordinate().gav() + "). Déclaration différente dans l'ancêtre " + ancestor.coordinate().gav() + " : " + p.value(), ancestor.evidence(), p.line()));
            }
        }
        limits.add("Les origines désignent les déclarations du modèle. Les propriétés de commande, du wrapper et des settings ne font pas l'objet d'un historique de priorité complet.");
    }

    static boolean matches(Declaration d, ResolvedDependency resolved) {
        String type = d.type(), classifier = d.classifier(), resolvedType = resolved.type(), resolvedClassifier = resolved.classifier();
        if (type.equals("test-jar")) { type = "jar"; if (classifier.isBlank()) classifier = "tests"; }
        if (resolvedType.equals("test-jar")) { resolvedType = "jar"; if (resolvedClassifier.isBlank()) resolvedClassifier = "tests"; }
        if (Set.of("maven-plugin", "ejb", "bundle").contains(type)) type = "jar";
        if (Set.of("maven-plugin", "ejb", "bundle").contains(resolvedType)) resolvedType = "jar";
        return d.coordinate().ga().equals(resolved.coordinate().ga()) && type.equals(resolvedType) && classifier.equals(resolvedClassifier);
    }
    private static <T> T unique(List<T> values) { return values.size() == 1 ? values.getFirst() : null; }
    private static boolean inherited(SourcePom source) { return source.kind().equals("MODULE") || source.kind().startsWith("PARENT"); }
}
