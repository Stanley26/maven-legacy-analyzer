package io.github.mavenlegacy;

import java.nio.file.*;
import java.util.*;
import static io.github.mavenlegacy.Model.*;
import static io.github.mavenlegacy.ReportWriter.escape;

/** Static, portable pages: no fetch, server, network assets or access to the original checkout. */
public final class ProvenanceWriter {
    static final String STYLE = "body{font:16px system-ui,sans-serif;color:#172333;background:#f2f5f8;max-width:1100px;margin:auto;padding:30px 24px}"
            + "h1{font-size:30px;overflow-wrap:anywhere}p,li{line-height:1.65}a{color:#086456}section{background:#fff;border:1px solid #d5dfe5;border-radius:10px;padding:22px;margin:20px 0;scroll-margin-top:15px}"
            + ".muted{color:#566675}.badge{background:#e5eef1;padding:5px 9px;border-radius:4px;font-size:12px}.limit{border-left:3px solid #c07a18;padding-left:14px}"
            + "code{overflow-wrap:anywhere}li{margin:12px 0}table{border-collapse:collapse;width:100%;font-size:13px}td,th{padding:10px;text-align:left;border-bottom:1px solid #dce4e9;overflow-wrap:anywhere}"
            + "pre{white-space:pre-wrap;overflow-wrap:anywhere;line-height:1.6;font-size:13px}.line{display:block}.line:target{background:#fff0b8}.line a{display:inline-block;width:4em;color:#637483;text-decoration:none;user-select:none}";

    public void write(Model.Module module, Path report) throws Exception {
        String link = module.evidence.get("versionProvenance");
        if (link == null) return;
        Path page = safeResolve(report, link);
        Files.createDirectories(page.getParent());
        var body = new StringBuilder("<p><a href='").append(relative(page, report.resolve("index.html"))).append("'>← Tous les projets</a></p>")
                .append("<h1>Pourquoi cette version ?</h1><p>").append(escape(module.displayName())).append("</p>")
                .append("<p class='muted'>Résultats sauvegardés du scan. Une source reliée documente la version effective ; elle ne constitue pas un historique complet de résolution.</p>");
        body.append("<section><h2>Contexte Maven observé</h2>");
        context(body, module, "mavenRuntime", "Maven"); context(body, module, "javaRuntime", "JDK du processus Maven");
        context(body, module, "requestedProfiles", "Profils demandés dans la configuration de l'analyseur");
        context(body, module, "activeProfiles", "Profils actifs signalés par Maven");
        body.append("</section><nav><ul>");
        for (int i = 0; i < module.explanations.size(); i++) body.append("<li><a href='#dependency-").append(i + 1).append("'>")
                .append(escape(module.explanations.get(i).dependency().coordinate().gav())).append("</a></li>");
        body.append("</ul></nav>");
        for (int i = 0; i < module.explanations.size(); i++) {
            var explanation = module.explanations.get(i); var dep = explanation.dependency();
            body.append("<section id='dependency-").append(i + 1).append("'><span class='badge'>")
                    .append(explanation.coverage().equals("SOURCE_LINKED") ? "Source reliée" : "Explication partielle")
                    .append("</span><h2>").append(escape(dep.coordinate().ga())).append("</h2><p><strong>Version sélectionnée : ")
                    .append(escape(dep.coordinate().version())).append("</strong> · scope ").append(escape(dep.scope()))
                    .append(" · type ").append(escape(dep.type())).append(dep.classifier().isBlank() ? "" : " · classifier " + escape(dep.classifier())).append("</p><ol>");
            for (var fact : explanation.facts()) {
                body.append("<li>").append(escape(fact.text()));
                if (fact.source() != null) {
                    Path source = safeResolve(report, fact.source());
                    if (Files.isRegularFile(source)) {
                        Path target = fact.line() > 0 && source.getFileName().toString().endsWith(".xml") ? sourceView(source) : source;
                        body.append(" <a href='").append(escape(relative(page, target)))
                                .append(fact.line() > 0 && target.equals(sourceView(source)) ? "#L" + fact.line() : "")
                                .append("'>Voir la preuve").append(fact.line() > 0 ? " · ligne " + fact.line() : "").append("</a>");
                    }
                }
                body.append("</li>");
            }
            body.append("</ol><div class='limit'><h3>Ce qui reste à déterminer</h3><ul>");
            for (var limitation : explanation.limitations()) body.append("<li>").append(escape(limitation)).append("</li>");
            body.append("</ul></div></section>");
        }
        body.append("<section><h2>POM sources conservés</h2><p class='muted'>MODULE : POM analysé. PARENT : chemin fourni par Maven. PARENT_CACHE : parent déclaré retrouvé dans le cache, chemin physique non fourni par Maven. MAVEN_CACHE : modèle cité dans les origines Maven, retrouvé dans le cache. Les empreintes identifient les copies conservées, pas leur dépôt distant d'origine.</p><table><tr><th>Modèle</th><th>Source</th><th>SHA-256</th></tr>");
        for (var source : module.sources) body.append("<tr><td><a href='").append(escape(relative(page, sourceView(safeResolve(report, source.evidence())))))
                .append("'>").append(escape(source.coordinate().gav())).append("</a></td><td>").append(escape(source.kind()))
                .append("</td><td><code>").append(escape(source.sha256())).append("</code></td></tr>");
        body.append("</table><p class='muted'>ARTIFACT : POM d'une dépendance résolue. BOM : import déclaré. EXTERNAL_PARENT : parent d'un modèle externe. Le suffixe CACHE indique une copie du cache Maven ; MAVEN indique une récupération demandée au wrapper.</p></section>");
        body.append("<section><h2>Parents et imports de BOM</h2><p class='muted'>La collecte d'un import déclaré dans un profil ne prouve pas que ce profil est actif. Les imports sont parcourus récursivement, y compris quand leur BOM n'apporte aucune entrée au modèle effectif.</p><table><tr><th>Depuis</th><th>POM cible</th><th>Relation / profil</th><th>Collecte</th></tr>");
        for (var relation : module.pomRelations) {
            body.append("<tr><td>").append(escape(relation.from())).append("</td><td>");
            var source = module.sources.stream().filter(s -> s.coordinate().equals(relation.target())).findFirst();
            if (source.isPresent()) body.append("<a href='").append(escape(relative(page, sourceView(safeResolve(report, source.get().evidence()))))).append("'>");
            body.append(escape(relation.target().gav()));
            if (source.isPresent()) body.append("</a>");
            body.append("</td><td>").append(escape(relation.relation() + (relation.profile().isBlank() ? "" : " / " + relation.profile())))
                    .append("</td><td>").append(escape(relation.status())).append("<br>").append(escape(relation.detail())).append("</td></tr>");
        }
        body.append("</table></section>");
        Files.writeString(page, document("Pourquoi cette version ? — " + module.displayName(), body.toString()));
        var sources = new LinkedHashSet<String>();
        for (var source : module.sources) sources.add(source.evidence());
        if (module.evidence.containsKey("effectivePom")) sources.add(module.evidence.get("effectivePom"));
        for (String source : sources) {
            Path xml = safeResolve(report, source);
            if (Files.isRegularFile(xml)) writeSource(xml, page);
        }
    }

    private static void context(StringBuilder body, Model.Module module, String key, String label) {
        body.append("<p><strong>").append(escape(label)).append("</strong></p><pre>")
                .append(escape(Objects.toString(module.context.get(key), "Non collecté dans ce scan."))).append("</pre>");
    }
    static Path safeResolve(Path report, String link) {
        Path root = report.toAbsolutePath().normalize(), file = root.resolve(link).normalize();
        if (!file.startsWith(root) || file.equals(root)) throw new IllegalArgumentException("Report link must stay within its directory: " + link);
        for (Path path = file; path != null && path.startsWith(root); path = path.getParent())
            if (Files.isSymbolicLink(path)) throw new IllegalArgumentException("Symbolic report links are not supported: " + link);
        return file;
    }
    static Path sourceView(Path xml) { return xml.resolveSibling(xml.getFileName() + ".html"); }
    private static String relative(Path page, Path target) { return page.getParent().relativize(target).toString().replace('\\', '/'); }
    private static void writeSource(Path xml, Path page) throws Exception {
        var body = new StringBuilder("<p><a href='").append(escape(relative(sourceView(xml), page))).append("'>← Explications</a></p><h1>")
                .append(escape(xml.getFileName().toString())).append("</h1><p><a href='").append(escape(xml.getFileName().toString())).append("'>POM XML conservé</a></p><pre>");
        // XML may declare a non-UTF-8 encoding. Let the XML declaration select the source-view decoder.
        byte[] bytes = Files.readAllBytes(xml);
        String header = new String(bytes, 0, Math.min(bytes.length, 256), java.nio.charset.StandardCharsets.ISO_8859_1);
        var encoding = java.util.regex.Pattern.compile("encoding\\s*=\\s*['\"]([^'\"]+)['\"]").matcher(header);
        var charset = encoding.find() ? java.nio.charset.Charset.forName(encoding.group(1)) : java.nio.charset.StandardCharsets.UTF_8;
        if (bytes.length >= 2 && ((bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xfe) || (bytes[0] == (byte) 0xfe && bytes[1] == (byte) 0xff)))
            charset = java.nio.charset.StandardCharsets.UTF_16;
        String[] lines = new String(bytes, charset).split("\\R", -1);
        for (int i = 0; i < lines.length; i++) body.append("<span class='line' id='L").append(i + 1).append("'><a href='#L").append(i + 1).append("'>")
                .append(i + 1).append("</a>").append(escape(lines[i])).append("</span>");
        body.append("</pre>");
        Files.writeString(sourceView(xml), document(xml.getFileName().toString(), body.toString()));
    }
    static String document(String title, String body) {
        return "<!doctype html><html lang='fr'><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<meta http-equiv='Content-Security-Policy' content=\"default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'\">"
                + "<title>" + escape(title) + "</title><style>" + STYLE + "</style></head><body>" + body + "</body></html>";
    }
}
