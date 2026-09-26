/* All report content is rendered as text; source links stay inside the report. */
(() => {
  'use strict';
  const E = window.MLA, D = E.prepare(window.MLA_DATA), $ = s => document.querySelector(s);
  const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const num = n => n.toLocaleString('fr-CA');
  const date = s => { const d = new Date(s); return Number.isNaN(d.getTime()) ? s : d.toLocaleString('fr-CA',{dateStyle:'medium',timeStyle:'short'}); };
  const link = (href,text,cls='') => `<a class="${cls}" href="${esc(href)}">${esc(text)}</a>`;
  const route = (kind,id) => `#${kind}${id === undefined ? '' : '/' + encodeURIComponent(id)}`;
  const badge = (text,kind='') => `<span class="badge ${kind}">${esc(text)}</span>`;
  const origin = {'local':'POM local','local-management':'Gestion locale','parent':'Parent','bom':'BOM','external-management':'POM externe','unknown':'À établir'};
  const factNames = {INTRODUCTION:'Chemin observé',INTRODUCER_DECLARATION:'Déclaration d’introduction',EFFECTIVE_DECLARATION:'Déclaration effective',EFFECTIVE_MANAGEMENT:'Gestion effective',DECLARED_SOURCE:'Déclaration source',MANAGED_SOURCE:'Gestion de version source',EFFECTIVE_PROPERTY:'Propriété effective',PROPERTY_OVERRIDE:'Redéfinition documentée',EXTERNAL_PROPERTY:'Propriété du modèle externe',PROPERTY_CANDIDATE:'Propriété à confirmer'};
  let filters = {}, pages = {}, componentVersion = '', catalog = [], targetName = '', targetId = '', diff = null, compareQuery = '', compareOverrides = false;
  let renderToken = 0, searchTimer;
  let structureData, structureQuery = '', structureVariantsOnly = false;
  const structures = () => structureData ||= window.MLA_STRUCTURES.build(D);
  const structureKinds = {parents:'Hiérarchie des parents',modules:'Modules agrégés',bom:'Imports / parents de BOM',dependencies:'Dépendances résolues',paths:'Chemins de dépendances'};
  const sourceUrl = (path,line) => {
    if (typeof path !== 'string' || !path || path.startsWith('/') || /[\\:?#]/.test(path) || path.split('/').some(p => p === '..' || p === '.')) return null;
    return path.split('/').map(encodeURIComponent).join('/') + (line > 0 ? `.html#L${Number(line)}` : '');
  };
  const evidenceLink = (path,text,line=0) => { const href = sourceUrl(path,line); return href ? link(href,text) : ''; };
  const head = (title,description,kicker='EXPLORATEUR MAVEN',extra='') => `<div class="pagehead"><div><span class="eyebrow">${esc(kicker)}</span><h1>${esc(title)}</h1><p class="subtitle">${esc(description)}</p></div>${extra}</div>`;
  const stat = (value,label,caption) => `<div class="stat"><div class="label">${esc(label)}</div><div class="number">${num(value)}</div><small>${esc(caption)}</small></div>`;
  const panel = (title,sub,body,extra='') => `<section class="panel"><div class="panelhead"><div><h2>${esc(title)}</h2><p class="muted">${esc(sub)}</p></div>${extra}</div>${body}</section>`;
  const empty = (text='Aucun résultat pour ces filtres.') => `<div class="empty">${esc(text)}</div>`;
  const table = (headers,rows) => rows.length ? `<div class="table-wrap"><table><thead><tr>${headers.map(h => `<th scope="col">${esc(h)}</th>`).join('')}</tr></thead><tbody>${rows.join('')}</tbody></table></div>` : empty();
  function paged(id,headers,rows,makeRow) {
    const size = 40, page = Math.max(0,Math.min(pages[id] || 0,Math.ceil(rows.length / size) - 1));
    pages[id] = page;
    const body = table(headers,rows.slice(page * size,(page + 1) * size).map(makeRow));
    return body + (rows.length > size ? `<div class="pager"><span>${num(page * size + 1)}–${num(Math.min((page + 1) * size,rows.length))} sur ${num(rows.length)}</span><div><button data-page="${id}" data-step="-1" ${page === 0 ? 'disabled' : ''}>← Précédent</button><button data-page="${id}" data-step="1" ${(page + 1) * size >= rows.length ? 'disabled' : ''}>Suivant →</button></div></div>` : '');
  }
  function componentRow(c) {
    return `<tr><td>${link(route('components',c.ga),c.ga,'table-link')}${c.internal ? '<small>Coordonnées présentes dans le parc</small>' : ''}</td><td class="count">${num(c.projects.size)}</td><td>${esc([...c.versions].sort().join(', '))}</td><td>${c.overrides.size ? badge(`${c.overrides.size} projet(s)`,'amber') : '<span class="muted">—</span>'}</td></tr>`;
  }
  function projectRow(p) {
    return `<tr><td>${link(route('projects',p.id),p.name,'table-link')}</td><td class="count">${p.modules.length}</td><td>${num(p.components.size)}</td><td>${p.overrides ? badge('Override','amber') : ''} ${p.issues ? badge('À examiner','amber') : badge('Résolu','teal')}</td></tr>`;
  }
  function consumerRow(u) {
    const c = D.coordinates[u[1]], m = D.modules[u[0]];
    return `<tr><td>${link(route('projects',m.project),D.projects[m.project].name,'table-link')}<small>${esc(m.label)}</small></td><td class="mono">${esc(c.v)}<small>${esc(c.type)}${c.classifier ? ':' + esc(c.classifier) : ''}</small></td><td>${badge(u[2])}</td><td>${esc(origin[u[5]])}${u[4] ? '<small>' + badge('Override documenté','amber') + '</small>' : ''}</td><td class="nowrap"><button data-why="${u[0]}:${u[6]}">Pourquoi ?</button></td></tr>`;
  }
  function overview(uses,cs,ps) {
    const selectedModules = ps.flatMap(p => p.modules), selectedIds = new Set(selectedModules.map(m => m.id));
    const failures = selectedModules.filter(m => m.status !== 'RESOLVED').length;
    const divergence = cs.filter(c => c.versions.size > 1).length;
    const overrides = new Set(uses.filter(u => u[4]).map(u => D.modules[u[0]].project)).size;
    const partial = selectedModules.filter(m => m.provenance !== 'COLLECTED').length;
    const families = new Map();
    for (const u of uses) { const name = D.coordinates[u[1]].ga.split(':')[0]; if (!families.has(name)) families.set(name,new Set()); families.get(name).add(D.modules[u[0]].project); }
    const top = [...families].sort((a,b) => b[1].size - a[1].size).slice(0,6);
    const familyRows = top.map(([name,projects]) => `<tr><td><button class="text-button" data-family="${esc(name)}">${esc(name)}</button></td><td><div class="bar"><meter min="0" max="${Math.max(1,ps.length)}" value="${projects.size}" aria-label="${esc(name)} : ${projects.size} projets"></meter><span class="count">${projects.size}</span></div></td></tr>`);
    const issueRows = selectedModules.filter(m => m.issues.length).slice(0,40).map(m => `<p><strong>${esc(m.label)}</strong> — ${esc(m.issues.map(i => i.code).join(', '))}</p>`).join('');
    return head('Vue du parc','Repérer les versions dispersées, les composants partagés et les projets à examiner.', 'ANALYSE / SYNTHÈSE',badge(D.mode === 'MAVEN' ? 'Résolution Maven' : 'Inventaire'))
      + `<div class="stats">${stat(ps.length,'Projets','Dépôts distincts dans la sélection')}${stat(selectedIds.size,'POM / modules','Chaque POM compte une fois')}${stat(cs.length,'Composants','groupId:artifactId distincts')}${stat(cs.filter(c => c.internal).length,'Composants internes','Correspondance de GAV avec le parc')}</div>`
      + (D.mode !== 'MAVEN' ? '<div class="notice info">Cet inventaire ne contient pas de résolution Maven. Les projets et POM restent consultables ; les versions résolues et les consommateurs nécessitent une collecte Maven.</div>' : '')
      + `<div class="grid">${panel('Familles les plus présentes','Nombre de projets consommateurs · cliquer pour filtrer',table(['Famille / groupId','Projets'],familyRows))}${panel('Points à examiner','Indicateurs calculés sur la sélection',`<div class="panelbody rows"><div class="insight"><div>Composants multiversions<small>Dispersion entre projets, pas nécessairement un conflit</small></div><strong>${divergence}</strong></div><div class="insight"><div>Projets avec overrides<small>Redéfinitions de propriétés documentées</small></div><strong>${overrides}</strong></div><div class="insight"><div>Modules non résolus<small>Inventaire, échec ou résolution partielle</small></div><strong>${failures}</strong></div><div class="insight"><div>Provenance à compléter<small>Collecte partielle ou non attestée</small></div><strong>${partial}</strong></div></div>`)}</div>`
      + panel('Projets dans la sélection','Les filtres s’appliquent aux dépendances observées. Sans filtre de dépendance, les projets sans arbre restent visibles.',paged('overview-projects',['Projet','POM','Composants','État'],ps,projectRow))
      + ((issueRows || D.issues.length) ? `<details><summary>Consulter les diagnostics de collecte</summary><div class="issue-list">${D.issues.map(i => `<p><strong>${esc(i.code)}</strong> — ${esc(i.message)}</p>`).join('')}${issueRows}</div><p class="muted">Les 40 premiers modules concernés sont affichés ici. Tous les diagnostics sont dans le rapport détaillé.</p></details>` : '');
  }
  function componentDetail(ga,uses) {
    const all = uses.filter(u => D.coordinates[u[1]].ga === ga), versions = E.unique(all.map(u => D.coordinates[u[1]].v)).sort();
    if (componentVersion && !versions.includes(componentVersion)) componentVersion = '';
    const selected = all.filter(u => !componentVersion || D.coordinates[u[1]].v === componentVersion);
    const projects = new Set(selected.map(u => D.modules[u[0]].project));
    const downstream = E.components(D,E.surface(D,selected));
    const internal = selected.some(u => D.coordinates[u[1]].internal), impact = E.impact(D,selected);
    return link('#components','← Tous les composants','back')
      + head(ga,'Choisir une version pour voir ses projets consommateurs, son scope et les sources de la résolution.','COMPOSANT / EXPLORATION')
      + `<div class="stats">${stat(projects.size,'Projets consommateurs','Un projet peut utiliser plusieurs versions')}${stat(new Set(selected.map(u => u[0])).size,'Modules consommateurs','POM où la dépendance est observée')}${stat(versions.length,'Versions trouvées','Dans les filtres du parc')}${stat(new Set(selected.filter(u => u[4]).map(u => D.modules[u[0]].project)).size,'Projets avec overrides','Redéfinitions documentées')}</div>`
      + panel('Versions observées','Les compteurs de versions peuvent concerner les mêmes projets.',`<div class="panelbody chips"><button data-version="" class="${componentVersion ? '' : 'selected'}">Toutes</button>${versions.map(v => `<button data-version="${esc(v)}" class="${v === componentVersion ? 'selected' : ''}">${esc(v)}<span>${new Set(all.filter(u => D.coordinates[u[1]].v === v).map(u => D.modules[u[0]].project)).size} projets</span></button>`).join('')}</div>`)
      + (internal ? panel('Impact dans le parc','Projets externes au dépôt fournisseur · dépendances sélectionnées par Maven',`<div class="panelbody"><div class="chips">${badge(`${impact.direct.size} directs`,'teal')}${badge(`${impact.indirect.size} indirects uniquement`)}${badge(`${impact.all.size} au total`)}${impact.unknown.size ? badge(`${impact.unknown.size} chemins inconnus`,'amber') : ''}</div><p class="muted">Les ensembles directs et indirects uniquement sont disjoints. L’identité Maven ne prouve pas que les binaires publiés proviennent de ce checkout.</p></div>`) : '')
      + panel('Consommateurs','Une ligne par usage résolu dans un module.',paged('consumers',['Projet / module','Version','Scope','Origine de déclaration','Preuves'],selected,consumerRow))
      + panel('Dépendances en aval observées','Composants dont le chemin sélectionné passe par ce composant. Cette vue ne constitue pas son catalogue complet de dépendances.',paged('surface',['Composant','Projets','Versions','Overrides'],downstream,componentRow));
  }
  function projectDetail(id,uses) {
    const p = D.projects[id]; if (!p) return empty('Projet introuvable.');
    const modules = D.modules.filter(m => m.project === id), selected = uses.filter(u => D.modules[u[0]].project === id);
    const moduleRows = modules.map(m => `<tr><td><strong>${esc(m.label)}</strong><small class="mono">${esc(m.gav)}</small></td><td>${badge(m.status,m.status === 'RESOLVED' ? 'teal' : 'amber')}</td><td>${esc(m.issues.map(i => i.code).join(', ') || '—')}</td><td>${link(`details.html#module-${m.id}`,'POM et contexte ↗')}</td></tr>`);
    return link('#projects','← Tous les projets','back') + head(p.name,'Les modules, versions et scopes observés dans ce dépôt.','PROJET / EXPLORATION')
      + panel('Modules Maven','Tous les POM du projet, y compris ceux sans résolution.',paged('project-modules',['Module / coordonnées','État','Diagnostics','Détails'],moduleRows,row => row))
      + panel('Dépendances du projet','La sélection tient compte des filtres du parc.',paged('project-uses',['Composant','Module','Scope','Version','Preuves'],selected,u => {
        const c = D.coordinates[u[1]], m = D.modules[u[0]];
        return `<tr><td>${link(route('components',c.ga),c.ga,'table-link')}<small>${esc(c.type)}${c.classifier ? ':' + esc(c.classifier) : ''}</small></td><td>${esc(m.label)}</td><td>${badge(u[2])}</td><td>${esc(c.v)} ${u[4] ? badge('Override','amber') : ''}</td><td><button data-why="${u[0]}:${u[6]}">Pourquoi ?</button></td></tr>`;
      }));
  }
  function shared(uses) {
    const rows = E.components(D,uses.filter(u => D.coordinates[u[1]].internal)).map(c => ({...c,impact:E.impact(D,c.uses)})).sort((a,b) => b.impact.all.size - a.impact.all.size);
    return head('Bibliothèques partagées','Identifier les composants du parc qui relient plusieurs projets, et mesurer leur portée.','PARC / IMPACT')
      + '<div class="notice info">Un consommateur est un projet distinct du dépôt fournisseur. Un projet présent dans plusieurs chemins compte une seule fois dans le total. Les correspondances reposent sur groupId:artifactId:version.</div>'
      + panel('Consommateurs des composants internes','Cliquer sur un composant pour consulter les projets, les versions et les chemins.',paged('shared',['Composant','Directs','Indirects uniquement','Total','Versions'],rows,c => `<tr><td>${link(route('components',c.ga),c.ga,'table-link')}</td><td class="count">${c.impact.direct.size}</td><td>${c.impact.indirect.size}${c.impact.unknown.size ? `<small>${c.impact.unknown.size} chemins inconnus</small>` : ''}</td><td class="count">${c.impact.all.size}</td><td>${esc([...c.versions].join(', '))}</td></tr>`));
  }
  function comparison() {
    const options = catalog.filter(c => !location.pathname.startsWith(`/reports/${c.id}/`));
    let html = head('Comparer deux analyses','Observer les changements de versions et de scopes, puis repérer les overrides à examiner.','ÉVOLUTION / COMPARAISON')
      + `<div class="notice info">Base : ${esc(date(D.date))}. La cible doit être une autre analyse sauvegardée. Les écarts décrivent les résolutions observées ; ils ne prouvent pas la compatibilité d’une migration.</div>`
      + panel('Choisir la cible','Les fichiers restent dans votre navigateur. Aucun téléversement.',`<div class="panelbody compare-controls">${options.length ? `<label>Analyse enregistrée<select id="compare-report"><option value="">Sélectionner une analyse…</option>${options.map(c => `<option value="${c.id}" ${String(c.id) === targetId ? 'selected' : ''}>${esc(c.name)} · ${esc(date(c.date))}</option>`).join('')}</select></label>` : ''}<label class="filelabel">Ouvrir un analysis.json local<input id="compare-file" type="file" accept=".json,application/json"></label><span class="muted" id="compare-state">${esc(targetName || 'Aucune cible sélectionnée')}</span></div>`);
    if (!diff) return html;
    const q = compareQuery.toLowerCase();
    const rows = diff.rows.filter(r => (!q || `${r.project} ${r.label} ${r.ga}`.toLowerCase().includes(q)) && (!compareOverrides || r.override));
    html += `<div class="stats">${stat(diff.matched,'Modules comparables','Même nom projet/module, résolus des deux côtés')}${stat(new Set(rows.map(r => r.project)).size,'Projets avec écarts','Dans la sélection ci-dessous')}${stat(rows.length,'Écarts observés','Par composant, type, classifier et module')}${stat(diff.unknown.length,'Modules non comparables','Absents, ambigus ou incomplets')}</div>`
      + panel('Changements observés',`Cible : ${targetName}`,`<div class="panelbody compare-filter"><input id="compare-query" type="search" aria-label="Filtrer les écarts par projet ou dépendance" placeholder="Projet ou dépendance…" value="${esc(compareQuery)}"><label><input type="checkbox" id="compare-overrides" ${compareOverrides ? 'checked' : ''}> Overrides documentés à examiner</label></div>`
        + paged('diff',['Projet / module','Composant','Base · version / scope','Cible · version / scope','Observation'],rows,r => `<tr><td><strong>${esc(r.project)}</strong><small>${esc(r.label)}</small></td><td>${esc(r.ga)}<small>${esc(r.type)}${r.classifier ? ':' + esc(r.classifier) : ''}</small></td><td>${r.before.map(esc).join('<br>') || '—'}</td><td>${r.after.map(esc).join('<br>') || '—'}</td><td>${badge(r.kind,r.kind === 'Retirée' ? 'amber' : '')}${r.override ? '<small>' + badge('Override à examiner','amber') + '</small>' : ''}</td></tr>`))
      + (diff.unknown.length ? panel('Couverture de comparaison','Un module absent ou incomplet ne permet pas de conclure à un retrait de dépendance.',paged('unknown',['Module','Motif'],diff.unknown,r => `<tr><td>${esc(r.label)}</td><td>${esc(r.reason)}</td></tr>`)) : '');
    return html;
  }
  function cardsPage(id,items,draw,size=8) {
    const page = Math.max(0,Math.min(pages[id] || 0,Math.ceil(items.length / size) - 1));
    pages[id] = page;
    return items.slice(page * size,(page + 1) * size).map(draw).join('') + (items.length > size ? `<div class="pager"><span>${page * size + 1}–${Math.min((page + 1) * size,items.length)} sur ${items.length}</span><div><button data-page="${id}" data-step="-1" ${page ? '' : 'disabled'}>← Précédent</button><button data-page="${id}" data-step="1" ${(page + 1) * size >= items.length ? 'disabled' : ''}>Suivant →</button></div></div>` : '');
  }
  function structureCard(v,f) {
    const S = window.MLA_STRUCTURES, g = structures().groups[v.group], baseline = f.baseline.id === v.id;
    const delta = S.differences(f.baseline.sample,v.sample);
    const chains = E.unique(v.sample.chains.map(c => c.label));
    const previews = delta.rows.filter(r => r.kind !== 'paths').slice(0,3);
    return `<article class="structure-card ${baseline ? 'reference' : ''}"><div class="structure-card-head">${badge(baseline ? 'Référence descriptive' : `Structure ${g.id + 1}` ,baseline ? 'teal' : '')}<strong>${num(v.projects.length)} <small>projet${v.projects.length > 1 ? 's' : ''}</small></strong></div>
      <h3>${esc(baseline ? `Structure ${g.id + 1}` : `Variante ${g.variants.indexOf(v) + 1}`)}</h3>
      <div class="structure-chain">${chains.slice(0,3).map(c => `<p>${esc(c)}</p>`).join('')}${chains.length > 3 ? `<small>+ ${chains.length - 3} chaînes dans le détail</small>` : ''}</div>
      <p class="muted">${num(v.sample.modules.length)} POM / modules par projet.<br>${g.projects.length} projet(s) partagent cette hiérarchie · ${g.variants.length} variante(s).</p>
      ${!v.sample.depsKnown || !v.sample.bomKnown ? badge('Dépendances / BOM à compléter','amber') : ''}
      ${baseline ? '<p class="structure-common">La variante la plus fréquente de la hiérarchie principale sert de point de comparaison.</p>' : `<div class="structure-changes">${previews.map(r => `<p><span class="eyebrow">${esc(structureKinds[r.kind])}</span><strong>${esc(r.label)}</strong><span class="structure-before">Réf. : ${esc(r.before.join(' ; ') || 'Non observé')}</span><span>Variante : ${esc(r.after.join(' ; ') || 'Non observé')}</span></p>`).join('') || '<p>Mêmes observations comparables.</p>'}<small>${delta.rows.length} écart(s) détaillé(s)${delta.unknown.length ? ' · couverture partielle' : ''}</small></div>`}
      <div class="structure-projects">${v.projects.slice(0,3).map(p => link(route('projects',p.id),p.name)).join(' · ')}${v.projects.length > 3 ? ` · + ${v.projects.length - 3}` : ''}</div>
      ${link(route('structure-variant',v.id),`Voir ${v.projects.length > 1 ? 'les ' + num(v.projects.length) + ' projets' : 'le projet'} et les preuves →`,'structure-open')}</article>`;
  }
  function structureFamily(f,expanded=false) {
    // Show different hierarchies first; when there is only one, its dependency variants are already side by side.
    const first = f.groups.map(g => g.variants[0]);
    const ordered = [...first,...f.variants.filter(v => !first.includes(v))];
    const cards = expanded ? cardsPage('structure-cards',ordered,v => structureCard(v,f),12) : ordered.slice(0,3).map(v => structureCard(v,f)).join('');
    return panel(f.label,`${f.projects.length} projets · ${f.groups.length} hiérarchie(s) · ${f.variants.length} variante(s)`,
      `<div class="panelbody"><div class="structure-grid">${cards}</div>${!expanded && ordered.length > 3 ? `<p class="muted">${ordered.length - 3} autres variantes dans cette famille.</p>` : ''}</div>`,
      expanded ? '' : link(route('structures',f.id),'Explorer cette famille →','table-link'));
  }
  function structuresView(id) {
    const s = structures();
    if (id !== undefined) {
      const f = s.families[Number(id)]; if (!f) return empty('Famille introuvable.');
      return link('#structures','← Toutes les structures','back')
        + head(f.label,'Les hiérarchies et variantes de cette famille, regroupées automatiquement.','STRUCTURES / FAMILLE')
        + `<div class="notice info">Le groupe de référence décrit la structure la plus fréquente de cette famille. Il ne constitue pas une règle de conformité ni une recette de migration validée.</div>`
        + structureFamily(f,true)
        + panel('Pourquoi ces groupes sont rapprochés','Chaque hiérarchie est comparée directement au groupe de référence ; les rapprochements ne se propagent pas de voisin en voisin.',
          table(['Hiérarchie','Projets','Raison du rapprochement'],f.groups.map(g => `<tr><td>Structure ${g.id + 1}</td><td>${g.projects.length}</td><td>${esc(g.reason)}</td></tr>`)));
    }
    const q = structureQuery.toLowerCase().trim();
    const families = s.families.filter(f => (!q || f.search.includes(q)) && (!structureVariantsOnly || f.variants.length > 1));
    const incomplete = s.incomplete.filter(p => !q || p.search.includes(q));
    return head('Structures du parc','Les projets de même structure sont réunis automatiquement. Leurs variantes de parents, de BOM et de dépendances apparaissent côte à côte.','PARC / REGROUPEMENT AUTOMATIQUE')
      + `<div class="stats">${stat(D.projects.length,'Projets du parc','Chaque dépôt compte une fois')}${stat(s.families.length,'Familles de structures','Rapprochements expliqués')}${stat(s.groups.length,'Hiérarchies distinctes','Parents, versions et modules agrégés')}${stat(s.incomplete.length,'Hiérarchies à compléter','Sources absentes ou ambiguës')}</div>`
      + `<div class="structure-toolbar"><label>Rechercher dans les familles<input id="structure-query" type="search" placeholder="Projet, parent, BOM ou dépendance…" value="${esc(structureQuery)}"></label><label class="structure-check"><input id="structure-variants" type="checkbox" ${structureVariantsOnly ? 'checked' : ''}> Familles avec variantes</label><span>${families.length} famille(s) affichée(s)</span></div>
      <p class="muted">La recherche conserve la famille entière pour garder les variantes visibles. Les regroupements utilisent toute l’analyse, indépendamment des filtres des autres vues.</p>`
      + (families.length ? cardsPage('structure-families',families,f => structureFamily(f)) : empty('Aucune famille pour cette recherche.'))
      + (incomplete.length ? panel('Hiérarchies à compléter','Ces projets restent visibles, sans assimiler une source manquante à une absence de parent.',
        paged('structure-incomplete',['Projet','Informations manquantes','POM'],incomplete,p => `<tr><td>${link(route('projects',p.id),p.name,'table-link')}</td><td>${p.problems.map(esc).join('<br>')}</td><td>${p.modules.map(m => link(`details.html#module-${m.id}`,m.label)).join('<br>')}</td></tr>`)) : '')
      + `<details class="structure-method"><summary>Comprendre les regroupements</summary><p>Une hiérarchie conserve les versions des parents externes, le nombre et le type des modules et les relations d’agrégation. Les noms et versions propres aux modules locaux ne séparent pas deux structures identiques.</p><p>Les hiérarchies voisines partagent un parent ou un BOM et un type de module. À défaut, un rapprochement exige la même composition de modules, au moins trois dépendances directes communes et un recouvrement pondéré d’au moins 75 %. Les dépendances les moins répandues dans le parc pèsent davantage.</p><p>Les variantes distinguent les imports de BOM dans leur ordre de déclaration, les versions, scopes, types, classifiers, chemins sélectionnés et origines documentées des dépendances. Les imports de profils sont des déclarations : leur collecte ne prouve pas leur activation. Les configurations Maven et JDK restent consultables dans les preuves de chaque module.</p></details>`;
  }
  function structureRefs(refs) {
    return refs.slice(0,3).map(ref => ref.dependency !== undefined
      ? `<button data-why="${ref.module}:${ref.dependency}">Pourquoi ? · ${esc(D.modules[ref.module].label)}</button>`
      : evidenceLink(ref.evidence,D.modules[ref.module].label,ref.line) || link(`details.html#module-${ref.module}`,D.modules[ref.module].label)).join('<br>') + (refs.length > 3 ? '<small>Autres modules consultables dans les projets.</small>' : '');
  }
  function structureVariant(id) {
    const s = structures(), v = s.variants[Number(id)]; if (!v) return empty('Variante introuvable.');
    const f = s.families[v.family], reference = f.baseline, same = reference.id === v.id, p = v.sample;
    const delta = window.MLA_STRUCTURES.differences(reference.sample,p);
    const rows = same ? [...p.rows.values()].map(r => ({...r,before:[],after:window.MLA_STRUCTURES.rowValues(r),beforeRefs:[],afterRefs:r.refs})) : delta.rows;
    const diffSection = panel(same ? 'Observations de la référence' : 'Différences observées',`Preuves illustrées par ${reference.sample.name}${same ? '' : ' et ' + p.name}. Une différence n’est pas automatiquement une erreur.`,
      rows.length ? paged('structure-differences',same ? ['Critère','Observation','Preuves'] : ['Critère',`Référence · ${reference.projects.length} projet(s)`,`Cette variante · ${v.projects.length} projet(s)`],rows,r => {
        const criterion = `<td>${badge(structureKinds[r.kind])}<strong class="structure-row-label">${esc(r.label)}</strong></td>`;
        const before = `<td>${r.before.map(esc).join('<br>') || 'Non observé'}<div class="structure-row-proofs">${structureRefs(r.beforeRefs)}</div></td>`;
        const after = `<td>${r.after.map(esc).join('<br>') || 'Non observé'}${same ? '' : `<div class="structure-row-proofs">${structureRefs(r.afterRefs)}</div>`}</td>`;
        return `<tr>${criterion}${same ? '' : before}${after}${same ? `<td>${structureRefs(r.afterRefs)}</td>` : ''}</tr>`;
      }) : empty('Aucun écart démontré sur les observations comparables.'));
    return link(route('structures',f.id),'← Famille : ' + f.label,'back')
      + head(`Structure ${v.group + 1} · ${v.projects.length} projet(s)`,same ? 'Variante de référence descriptive de cette famille.' : 'Écarts par rapport à la variante de référence de cette famille.','STRUCTURES / VARIANTE')
      + (delta.unknown.length ? `<div class="notice">${delta.unknown.map(esc).join('<br>')}${p.bomProblems.length ? '<br>' + p.bomProblems.map(esc).join('<br>') : ''}</div>` : '')
      + diffSection
      + panel('Projets de cette variante','Les liens ouvrent les modules et leurs preuves dans l’analyse sauvegardée.',paged('variant-projects',['Projet','Modules','Couverture'],v.projects,item => `<tr><td>${link(route('projects',item.id),item.name,'table-link')}</td><td>${item.modules.map(m => link(`details.html#module-${m.id}`,m.label)).join('<br>')}</td><td>${badge(item.depsKnown && item.bomKnown ? 'Observations disponibles' : 'À compléter',item.depsKnown && item.bomKnown ? 'teal' : 'amber')}</td></tr>`))
      + panel('Hiérarchies observées',`Exemple représentatif : ${p.name}. Les noms complets restent visibles dans les preuves.`,
        `<div class="panelbody">${p.chains.map(c => `<div class="structure-proof-chain">${c.nodes.map(n => evidenceLink(n.evidence,n.gav) || esc(n.gav)).concat(link(`details.html#module-${c.module}`,D.modules[c.module].label)).join('<span aria-hidden="true"> → </span>')}</div>`).join('')}</div>`);
  }
  function render() {
    const [kind,encoded] = (location.hash.slice(1) || 'structures').split('/');
    let id; try { id = encoded === undefined ? undefined : decodeURIComponent(encoded); } catch { id = undefined; }
    const nav = kind === 'structure-variant' ? 'structures' : kind;
    document.querySelectorAll('[data-nav]').forEach(a => { a.classList.toggle('active',a.dataset.nav === nav); if (a.dataset.nav === nav) a.setAttribute('aria-current','page'); else a.removeAttribute('aria-current'); });
    $('.filters').hidden = ['compare','structures','structure-variant'].includes(kind);
    const uses = E.filter(D,filters);
    let html;
    if (kind === 'components') html = id !== undefined ? componentDetail(id,uses) : head('Composants','Rechercher une dépendance, choisir une version et retrouver ses consommateurs.','PARC / DÉPENDANCES') + panel('Catalogue des composants','Une entrée par groupId:artifactId.',paged('components',['Composant','Projets','Versions','Overrides'],E.components(D,uses),componentRow));
    else if (kind === 'projects') html = id !== undefined ? projectDetail(Number(id),uses) : head('Projets','Explorer les modules et les dépendances de chaque dépôt.','PARC / PROJETS') + panel('Projets dans la sélection','Les compteurs regroupent les modules d’un même dépôt.',paged('projects',['Projet','POM','Composants','État'],E.projects(D,uses,filters),projectRow));
    else if (kind === 'shared') html = shared(uses);
    else if (kind === 'compare') html = comparison();
    else if (kind === 'structures') html = structuresView(id);
    else if (kind === 'structure-variant') html = structureVariant(id);
    else html = overview(uses,E.components(D,uses),E.projects(D,uses,filters));
    $('#main').innerHTML = html;
  }
  const loads = new Map();
  function details(id) {
    if (window.MLA_DETAILS?.[id]) return Promise.resolve(window.MLA_DETAILS[id]);
    if (!loads.has(id)) loads.set(id,new Promise((resolve,reject) => {
      const script = document.createElement('script'); script.src = `explorer/module-${id}.js`;
      script.onload = () => { script.remove(); const d = window.MLA_DETAILS?.[id]; d ? resolve(d) : reject(new Error('Détails absents.')); };
      script.onerror = () => { script.remove(); loads.delete(id); reject(new Error('Détails indisponibles. Régénérer le rapport avec la commande report.')); };
      document.head.append(script);
    }));
    return loads.get(id);
  }
  async function why(moduleId,index) {
    const token = ++renderToken, dialog = $('#why-dialog'), m = D.modules[moduleId];
    $('#why-body').innerHTML = '<h1 id="why-title">Chargement des preuves…</h1>'; dialog.showModal();
    try {
      const data = await details(moduleId); if (token !== renderToken) return;
      const d = data.dependencies[index], ex = data.explanations[index], c = d.coordinate;
      const sources = new Map(data.sources.map(s => [s.evidence,s]));
      const fact = f => `<div class="fact ${f.kind === 'PROPERTY_OVERRIDE' ? 'warning' : ''}"><span class="eyebrow">${esc(factNames[f.kind] || f.kind)}</span><p>${esc(f.text)}</p>${f.source ? evidenceLink(f.source,`${sources.get(f.source)?.gav || f.source}${f.line > 0 ? ' · ligne ' + f.line : ''}`,f.line) : ''}</div>`;
      const evidence = Object.entries(data.evidence).filter(([k]) => ['rawPom','effectivePom','dependencyTree','activeProfiles','versionProvenance'].includes(k));
      $('#why-body').innerHTML = `<span class="eyebrow">${esc(m.label)}</span><h1 id="why-title">${esc(c.groupId)}:${esc(c.artifactId)}</h1><p>${badge(`Version ${c.version}`,'teal')} ${badge(d.scope)} ${badge(d.type + (d.classifier ? ':' + d.classifier : ''))} ${badge(ex?.coverage === 'SOURCE_LINKED' ? 'Source reliée' : 'Explication partielle',ex?.coverage === 'SOURCE_LINKED' ? 'teal' : 'amber')}</p>`
        + '<h3>Chemin d’introduction sélectionné</h3>' + (d.path.length ? `<div class="path" aria-label="Chemin de dépendance">${d.path.map((n,i) => `${i ? '<span class="arrow" aria-hidden="true">→</span>' : ''}<span class="node">${esc(n)}</span>`).join('')}</div>` : '<p class="muted">Chemin non collecté dans cette analyse.</p>')
        + '<h3>Déclarations et propriétés</h3><p class="muted">Ces faits sont présentés séparément du chemin transitif. Ils ne reconstituent pas une chaîne complète de priorité entre tous les candidats.</p>'
        + (ex?.facts.length ? ex.facts.filter(f => f.kind !== 'INTRODUCTION').map(fact).join('') : '<div class="notice">Aucune preuve de provenance disponible dans cette archive.</div>')
        + (ex?.limitations.length ? `<div class="notice">${ex.limitations.map(esc).join('<br>')}</div>` : '')
        + '<h3>Preuves sauvegardées</h3>' + `<div class="evidence">${evidence.map(([k,v]) => evidenceLink(v,k)).join('')}</div>`
        + `<details><summary>POM sources collectés (${data.sources.length})</summary><div class="issue-list">${data.sources.map(s => `<p>${evidenceLink(s.evidence,s.gav)} ${badge(s.kind)}</p>`).join('')}</div></details>`;
    } catch (err) { $('#why-body').innerHTML = `<h1 id="why-title">Preuves indisponibles</h1><p class="error">${esc(err.message)}</p>${link(`details.html#module-${moduleId}`,'Consulter le rapport détaillé')}`; }
  }
  async function loadTarget(loader,name,id='') {
    $('#compare-state').textContent = 'Lecture de la cible…';
    const token = ++renderToken;
    try {
      const data = await loader(); if (token !== renderToken) return;
      const target = data.uses ? E.prepare(data) : E.fromArchive(data);
      targetId = String(id);
      targetName = `${name} · ${date(target.date)}`;
      diff = E.compare(D,target); pages = {}; render();
    } catch (err) { if ($('#compare-state')) $('#compare-state').textContent = 'Impossible de lire la cible : ' + err.message; }
  }
  $('#main').addEventListener('click',event => {
    const btn = event.target.closest('button'); if (!btn) return;
    if (btn.dataset.page) { pages[btn.dataset.page] += Number(btn.dataset.step); render(); }
    else if (btn.dataset.version !== undefined) { componentVersion = btn.dataset.version; pages = {}; render(); }
    else if (btn.dataset.family !== undefined) { $('#family').value = btn.dataset.family; updateFilters(); }
    else if (btn.dataset.why) { const [m,i] = btn.dataset.why.split(':').map(Number); why(m,i); }
  });
  $('#main').addEventListener('change',event => {
    if (event.target.id === 'compare-report' && event.target.value) {
      const item = catalog.find(c => c.id === Number(event.target.value));
      if (item) loadTarget(async () => { const r = await fetch(item.data); if (!r.ok) throw new Error('Analyse inaccessible.'); return r.json(); },item.name,item.id);
    }
    if (event.target.id === 'compare-file' && event.target.files[0]) {
      const file = event.target.files[0]; loadTarget(async () => JSON.parse(await file.text()),file.name);
    }
    if (event.target.id === 'compare-overrides') { compareOverrides = event.target.checked; pages.diff = 0; render(); }
    if (event.target.id === 'structure-variants') { structureVariantsOnly = event.target.checked; pages['structure-families'] = 0; render(); }
  });
  $('#main').addEventListener('input',event => {
    if (event.target.id === 'structure-query') {
      structureQuery = event.target.value; clearTimeout(searchTimer);
      searchTimer = setTimeout(() => { pages['structure-families'] = 0; pages['structure-incomplete'] = 0; render(); $('#structure-query')?.focus(); },180);
      return;
    }
    if (event.target.id !== 'compare-query') return;
    compareQuery = event.target.value; clearTimeout(searchTimer);
    searchTimer = setTimeout(() => { pages.diff = 0; render(); const input = $('#compare-query'); input?.focus(); },180);
  });
  $('#close-why').addEventListener('click',() => $('#why-dialog').close());
  $('#why-dialog').addEventListener('close',() => { renderToken++; });
  function updateFilters() {
    filters = {q:$('#search').value,family:$('#family').value,version:$('#version').value,scope:$('#scope').value,override:$('#override').checked,anomaly:$('#anomaly').checked};
    pages = {}; render();
  }
  $('#search').addEventListener('input',() => { clearTimeout(searchTimer); searchTimer = setTimeout(updateFilters,180); });
  ['family','version','scope','override','anomaly'].forEach(id => $('#' + id).addEventListener('change',updateFilters));
  $('#reset').addEventListener('click',() => { ['search','family','version','scope'].forEach(id => $('#' + id).value = ''); ['override','anomaly'].forEach(id => $('#' + id).checked = false); componentVersion = ''; updateFilters(); });
  const fill = (id,values) => { $('#' + id).insertAdjacentHTML('beforeend',E.unique(values).sort().map(v => `<option value="${esc(v)}">${esc(v)}</option>`).join('')); };
  fill('family',D.coordinates.map(c => c.ga.split(':')[0])); fill('version',D.coordinates.map(c => c.v)); fill('scope',D.uses.map(u => u[2]));
  $('#scan-date').textContent = date(D.date);
  window.addEventListener('hashchange',() => { componentVersion = ''; pages = {}; render(); $('#main').focus({preventScroll:true}); window.scrollTo(0,0); });
  render();
  if (location.protocol === 'http:' && ['127.0.0.1','localhost'].includes(location.hostname)) {
    fetch('/api/reports').then(r => r.ok ? r.json() : []).then(items => {
      catalog = items;
      if (items.length) {
        $('#report-picker').innerHTML = `<select aria-label="Changer d’analyse" id="report-select">${items.map(c => `<option value="${c.id}" ${location.pathname.startsWith(`/reports/${c.id}/`) ? 'selected' : ''}>${esc(c.name)} · ${esc(date(c.date))}</option>`).join('')}</select>`;
        $('#report-select').addEventListener('change',event => { const item = items.find(c => c.id === Number(event.target.value)); if (item) location.href = item.url; });
        if (location.hash === '#compare') render();
      }
    }).catch(() => {});
  }
})();
