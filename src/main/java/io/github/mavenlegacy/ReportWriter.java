package io.github.mavenlegacy;

import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static io.github.mavenlegacy.Model.*;

public final class ReportWriter {
    public void write(Report report, Path output) throws Exception {
        var mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        mapper.writeValue(output.resolve("analysis.json").toFile(), report);
        render(report, output);
    }
    public void render(Report report, Path output) throws Exception {
        for (var module : report.modules()) new ProvenanceWriter().write(module, output);
        Files.writeString(ProvenanceWriter.safeResolve(output, "details.html"), html(report), StandardCharsets.UTF_8);
        new ExplorerWriter().write(report, output);
    }
    String html(Report report) {
        var body = new StringBuilder();
        var labels = new HashMap<String, String>();
        int moduleIndex = 0;
        for (var module : report.modules()) {
            labels.put(module.id, module.displayName());
            var pom = module.effective != null ? module.effective : module.declared;
            body.append("<details class='module' id='module-").append(moduleIndex++).append("'><summary><span class='badge'>").append(escape(module.status)).append("</span> ")
                    .append("<strong>").append(escape(module.displayName())).append("</strong>");
            if (pom != null) body.append("<span class='coordinates'>").append(escape(pom.coordinate().gav())).append("</span>");
            body.append("</summary><div class='content'>");
            if (pom != null) {
                body.append("<p><strong>").append(escape(pom.coordinate().gav())).append("</strong> · ").append(escape(pom.packaging())).append("</p>");
                if (pom.parent() != null) body.append("<p>Parent : ").append(escape(pom.parent().coordinate().gav())).append("</p>");
            }
            body.append("<p>Wrapper : ").append(escape(module.wrapper == null ? "absent" : module.wrapper)).append("</p>");
            for (var issue : module.issues) body.append("<p class='issue'><strong>").append(escape(issue.code())).append("</strong> — ").append(escape(issue.message())).append("</p>");
            for (var entry : module.evidence.entrySet()) body.append("<a class='evidence' href='").append(escape(entry.getValue())).append("'>")
                    .append(escape(entry.getKey().equals("versionProvenance") ? "Origines des versions et POM collectés" : entry.getKey())).append("</a> ");
            if (!module.dependencies.isEmpty()) {
                body.append("<h3>Dépendances résolues par Maven</h3><table><thead><tr><th>Composant</th><th>Version</th><th>Scope</th><th>Chemin d’introduction</th></tr></thead><tbody>");
                int dependencyIndex = 0;
                for (var dep : module.dependencies) {
                    dependencyIndex++;
                    body.append("<tr><td>").append(escape(dep.coordinate().ga())).append("</td><td>")
                        .append(escape(dep.coordinate().version())).append("</td><td>").append(escape(dep.scope())).append("</td><td>")
                        .append(escape(String.join(" → ", dep.path())));
                    if (module.evidence.containsKey("versionProvenance")) body.append("<br><a href='")
                            .append(escape(module.evidence.get("versionProvenance"))).append("#dependency-").append(dependencyIndex)
                            .append("'>Pourquoi cette version ?</a>");
                    body.append("</td></tr>");
                }
                body.append("</tbody></table>");
            }
            if (pom != null) {
                body.append("<h3>Déclarations ").append(module.effective == null ? "brutes" : "effectives").append(" et versions gérées</h3><table><thead><tr><th>Composant</th><th>Version</th><th>Rôle / profil</th><th>Origine Maven</th></tr></thead><tbody>");
                for (var dep : pom.dependencies()) declaration(body, dep, "dépendance");
                for (var dep : pom.managed()) declaration(body, dep, "gestion seulement");
                body.append("</tbody></table>");
            }
            body.append("</div></details>");
        }
        var globalIssues = new StringBuilder();
        for (var issue : report.issues()) globalIssues.append("<p class='issue'>").append(escape(issue.code() + ": " + issue.message())).append("</p>");
        var consumers = new StringBuilder();
        for (var consumer : report.internalConsumers()) consumers.append("<tr><td>").append(escape(consumer.dependency())).append("</td><td>")
                .append(escape(labels.getOrDefault(consumer.module(), consumer.module()))).append("</td><td>").append(consumer.direct() ? "direct" : "transitif").append("</td></tr>");
        return """
            <!doctype html><html lang="fr"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; base-uri 'none'; form-action 'none'">
            <title>Maven Legacy Analyzer</title><style>
            :root{font-family:system-ui,sans-serif;color:#172333;background:#f2f5f8}body{max-width:1280px;margin:auto;padding:36px 24px}h1{margin:8px 0;font-size:32px}.eyebrow{letter-spacing:.15em;color:#32655a;font-weight:700}p{line-height:1.6}.muted{color:#566675}input{width:100%;box-sizing:border-box;padding:14px;border:1px solid #b6c5cc;border-radius:8px;margin:16px 0}details{background:white;border:1px solid #d5dfe5;border-radius:8px;margin:12px 0}summary{cursor:pointer;padding:18px;overflow-wrap:anywhere}.content{padding:0 18px 18px;overflow:auto}.badge{font-size:11px;border-radius:4px;background:#e5eef1;padding:5px;font-weight:bold}table{border-collapse:collapse;width:100%;font-size:13px;margin:18px 0}td,th{padding:10px;border-bottom:1px solid #dce4e9;text-align:left;overflow-wrap:anywhere}th{background:#eaf0f4}.issue{border-left:3px solid #c07a18;padding-left:12px}.evidence{color:#086456;font-size:13px;display:inline-block;margin:8px 12px 8px 0}a{color:#086456}.note{background:#e6edef;border-radius:8px;padding:16px}h2{margin-top:32px}#empty{display:none}
            .coordinates{display:block;margin:8px 0 0 26px;font-size:13px;color:#566675;overflow-wrap:anywhere}
            </style></head><body><p><a href="index.html">← Explorer le parc</a></p><div class="eyebrow">INVENTAIRE · RÉSOLUTION · PREUVES</div><h1>Maven Legacy Analyzer</h1>
            <p class="muted">Analyse locale — {{date}}</p><p><strong>{{total}} POM</strong> · {{counts}}</p>
            {{presentation}}
            <p class="note">Ce rapport décrit les déclarations et les résultats Maven collectés. Les archives produites et le runtime ne sont pas vérifiés. Les origines de l’effective POM constituent des preuves partielles ; l’historique complet des overrides reste à reconstruire.</p>
            {{issues}}<p><a href="analysis.json">Ouvrir les données JSON</a></p><label for="filter">Rechercher un projet, une dépendance ou une version</label>
            <input id="filter" type="search" placeholder="component-core, application-a, 1.0.0…"><p id="empty">Aucun résultat.</p>
            <section>{{modules}}</section><h2>Consommateurs de composants internes</h2><p class="muted">Correspondances sur coordonnées et version exactes ; une correspondance ne prouve pas l’identité du binaire publié avec les sources locales.</p>
            <table><thead><tr><th>Composant</th><th>Module consommateur</th><th>Relation</th></tr></thead><tbody>{{consumers}}</tbody></table>
            <script>const target=document.getElementById(location.hash.slice(1));if(target&&target.matches('details'))target.open=true;document.getElementById('filter').addEventListener('input',e=>{const q=e.target.value.toLowerCase();let n=0;document.querySelectorAll('.module').forEach(el=>{el.hidden=!el.textContent.toLowerCase().includes(q);if(!el.hidden)n++;});document.getElementById('empty').style.display=n?'none':'block';});</script></body></html>
            """.replace("{{date}}", escape(report.generatedAt())).replace("{{total}}", Integer.toString(report.modules().size()))
                .replace("{{presentation}}", report.modules().stream().anyMatch(m -> m.context.containsKey("presentationMode"))
                        ? "<p class='note'>Présentation régénérée depuis une analyse sauvegardée, sans appel Maven. La date et les résultats restent ceux du scan d'origine. Les informations absentes de ce scan restent signalées comme non collectées.</p>" : "")
                .replace("{{counts}}", escape(report.counts().toString())).replace("{{issues}}", globalIssues)
                .replace("{{modules}}", body).replace("{{consumers}}", consumers);
    }
    private static void declaration(StringBuilder body, Declaration dep, String role) {
        body.append("<tr><td>").append(escape(dep.coordinate().ga())).append("</td><td>").append(escape(dep.coordinate().version()))
                .append("</td><td>").append(escape(role + (dep.profile().isEmpty() ? "" : " / " + dep.profile()))).append("</td><td>")
                .append(escape(dep.origin().isBlank() ? "non documentée" : dep.origin())).append("</td></tr>");
    }
    static String escape(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
}
