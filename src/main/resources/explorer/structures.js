/* Automatic grouping of observations. No Maven resolution, migration prediction or business classification. */
(function (root) {
  'use strict';
  const sorted = xs => [...xs].sort();
  const unique = xs => [...new Set(xs)];
  const signature = xs => JSON.stringify(xs);
  const ga = gav => gav.split(':').slice(0,2).join(':');
  const concrete = gav => typeof gav === 'string' && gav.split(':').length === 3 && gav.split(':').every(Boolean) && !gav.includes('${');
  const intersection = (a,b) => [...a].filter(x => b.has(x));
  const normPath = path => {
    const result = [];
    for (const part of path.replace(/\\/g,'/').split('/')) {
      if (!part || part === '.') continue;
      if (part === '..' && result.length && result.at(-1) !== '..') result.pop(); else if (part !== '.') result.push(part);
    }
    return result.join('/');
  };
  function add(rows, kind, label, value, ref) {
    const key = signature([kind,label]);
    if (!rows.has(key)) rows.set(key,{key,kind,label,values:new Map(),refs:[]});
    const row = rows.get(key);
    row.values.set(value,(row.values.get(value) || 0) + 1);
    if (ref) row.refs.push(ref);
  }
  const rowValues = row => row ? [...row.values].sort(([a],[b]) => a.localeCompare(b)).map(([v,n]) => n > 1 ? `${v} × ${n}` : v) : [];

  function describe(data, project, modules) {
    const problems = [], bomProblems = [], local = new Map(), paths = new Map(), rows = new Map(), projectModels = new Map();
    const parentGAs = new Set(), bomGAs = new Set(), directGAs = new Set();
    const moduleConfigs = new Map(modules.map(m => [m.id,{module:m.id,type:m.structure?.packaging || '?',deps:[],boms:[]} ]));
    const localModules = gav => local.get(gav) || [];
    const pack = m => m.structure?.packaging || '?';
    for (const m of modules) {
      const gav = m.structure?.root || m.gav;
      if (!local.has(gav)) local.set(gav,[]);
      local.get(gav).push(m);
      const path = normPath(m.path || '');
      if (!paths.has(path)) paths.set(path,[]);
      paths.get(path).push(m);
      for (const node of m.structure?.models || []) {
        if (!projectModels.has(node.gav)) projectModels.set(node.gav,[]);
        projectModels.get(node.gav).push(node);
      }
    }
    const token = gav => localModules(gav).length === 1 ? `module ${pack(localModules(gav)[0]).toUpperCase()}` : gav;
    const shapes = new Map(), children = new Map(), chains = [];
    let bomKnown = true;
    for (const m of modules) {
      const s = m.structure;
      if (!s?.available) { problems.push(`${m.label} : modèle POM indisponible`); continue; }
      if (localModules(s.root).length !== 1) problems.push(`${m.label} : coordonnées locales ambiguës (${s.root})`);
      const models = new Map((s.models || []).map(n => [n.gav,n]));
      const model = gav => {
        if (models.has(gav)) return models.get(gav);
        const candidates = projectModels.get(gav) || [];
        // Archived sibling evidence is usable only when it describes one unambiguous model.
        const distinct = new Set(candidates.map(n => signature([n.parent,n.imports,n.importsKnown])));
        return distinct.size === 1 ? candidates[0] : null;
      };
      const own = model(s.root);
      if (!own) { problems.push(`${m.label} : modèle racine indisponible`); continue; }
      const ancestry = [], seen = new Set([s.root]);
      let next = own.parent;
      while (next) {
        if (!concrete(next)) { problems.push(`${m.label} : parent non résolu (${next})`); break; }
        if (seen.has(next)) { problems.push(`${m.label} : cycle de parents (${next})`); break; }
        if (localModules(next).length > 1) { problems.push(`${m.label} : parent local ambigu (${next})`); break; }
        seen.add(next);
        if (!localModules(next).length) parentGAs.add(ga(next));
        const node = model(next);
        ancestry.push({gav:next,token:token(next),evidence:node?.evidence || '',module:m.id});
        if (!node) { problems.push(`${m.label} : source du parent absente (${next})`); break; }
        next = node.parent;
      }
      const chain = [...ancestry].reverse().map(n => n.token).concat(`module ${pack(m).toUpperCase()}`);
      chains.push({module:m.id,nodes:[...ancestry].reverse(),label:chain.join(' → ')});
      shapes.set(m.id,signature([pack(m),ancestry.map(n => n.token)]));
      add(rows,'parents',`Module ${pack(m).toUpperCase()}`,chain.join(' → '),{module:m.id,evidence:own.evidence});
      const childIds = [];
      for (const child of s.children || []) {
        const base = (m.path || '').replace(/\\/g,'/').replace(/[^/]+$/,'');
        const candidate = normPath(base + child);
        const matches = paths.get(candidate) || paths.get(candidate + '/pom.xml') || [];
        if (matches.length !== 1) problems.push(`${m.label} : module agrégé absent ou ambigu (${child})`);
        else childIds.push(matches[0].id);
      }
      children.set(m.id,childIds);
      // All imports are declarations. Profile labels are preserved and never treated as proof of activation.
      const visited = new Set();
      const walkImports = (gav, via = new Set(), conditional = '') => {
        if (via.has(gav)) { bomKnown = false; bomProblems.push(`${m.label} : cycle de BOM (${gav})`); return; }
        const visitKey = signature([gav,conditional]);
        if (visited.has(visitKey)) return;
        visited.add(visitKey);
        const node = model(gav);
        if (!node || !node.importsKnown) { bomKnown = false; bomProblems.push(`${m.label} : imports non documentés (${gav})`); return; }
        const branch = new Set(via); branch.add(gav);
        for (const [order, imported] of (node.imports || []).entries()) {
          const profile = [conditional,imported.profile ? `profil ${imported.profile} (activation non déduite)` : ''].filter(Boolean).join(' / ');
          const label = `${pack(m).toUpperCase()} · ${token(gav)} · import ${order + 1}`;
          add(rows,'bom',label,`${imported.gav}${profile ? ' · ' + profile : ''}`,{module:m.id,evidence:node.evidence,line:imported.line});
          moduleConfigs.get(m.id).boms.push([label,imported.gav,profile]);
          if (!concrete(imported.gav)) { bomKnown = false; bomProblems.push(`${m.label} : coordonnées de BOM non résolues`); continue; }
          if (!profile) bomGAs.add(ga(imported.gav));
          walkImports(imported.gav,branch,profile);
        }
        if (node.parent) {
          if (!seen.has(gav)) {
            add(rows,'bom',`${pack(m).toUpperCase()} · parent de ${token(gav)}`,node.parent,{module:m.id,evidence:node.evidence});
            moduleConfigs.get(m.id).boms.push(['parent de ' + token(gav),node.parent,conditional]);
          }
          walkImports(node.parent,branch,conditional);
        }
      };
      walkImports(s.root);
    }
    const treeCache = new Map();
    const tree = (id,seen = new Set()) => {
      if (seen.has(id)) { problems.push('Cycle de modules agrégés'); return 'cycle'; }
      if (treeCache.has(id)) return treeCache.get(id);
      const branch = new Set(seen); branch.add(id);
      const result = signature([shapes.get(id) || '?',sorted((children.get(id) || []).map(c => tree(c,branch)))]);
      treeCache.set(id,result); return result;
    };
    const hierarchy = signature(sorted(modules.map(m => tree(m.id))));
    for (const m of modules) add(rows,'modules',`Module ${pack(m).toUpperCase()}`,
      (children.get(m.id) || []).length ? 'Agrège : ' + sorted((children.get(m.id) || []).map(id => pack(data.modules[id]).toUpperCase())).join(', ') : 'Sans module agrégé', {module:m.id});
    const depsKnown = modules.length > 0 && modules.every(m => m.status === 'RESOLVED' && m.structure?.treeKnown);
    const origins = {local:'POM local','local-management':'gestion locale',parent:'parent',bom:'BOM','external-management':'gestion externe',unknown:'origine non établie'};
    for (const m of modules) for (const u of data.byModule[m.id] || []) {
      const c = data.coordinates[u[1]], gav = `${c.ga}:${c.v}`, isLocal = localModules(gav).length === 1;
      const path = u[3].map(n => token(data.nodes[n]));
      const direct = path.length === 2 ? 'directe' : path.length > 2 ? 'transitive' : 'chemin inconnu';
      const label = `${isLocal ? token(gav) : c.ga} [${c.type}${c.classifier ? ':' + c.classifier : ''}] · ${pack(m).toUpperCase()}`;
      const value = `${isLocal ? 'version du module' : c.v} · ${u[2]} · ${direct} · ${origins[u[5]] || origins.unknown}${u[4] ? ' · override documenté' : ''}`;
      const ref = {module:m.id,dependency:u[6]};
      add(rows,'dependencies',label,value,ref);
      add(rows,'paths',label,path.join(' → ') || 'Chemin indisponible',ref);
      moduleConfigs.get(m.id).deps.push([label,value,path]);
      if (direct === 'directe' && !isLocal) directGAs.add(c.ga);
    }
    const layout = signature(sorted(modules.map(pack)));
    const leafTypes = new Set(modules.map(pack).filter(p => p !== 'pom'));
    if (!leafTypes.size) leafTypes.add('pom');
    const name = project.name;
    const configs = [...moduleConfigs.values()];
    return {id:project.id,name,modules,rows,chains,parentGAs,bomGAs,directGAs,layout,leafTypes,hierarchy,
      complete:modules.length > 0 && problems.length === 0,problems:unique(problems),bomKnown,bomProblems:unique(bomProblems),depsKnown,
      configurations:configs.map(c => ({module:c.module,text:`${c.type.toUpperCase()} : ` + sorted([
        ...c.boms.map(b => 'BOM ' + b.join(' · ')),...c.deps.map(([label,value,path]) => `${label} : ${value} (${path.join(' → ')})`)
      ]).join(' ; ')})),
      variant:signature([bomKnown,depsKnown,sorted(configs.map(c => signature([tree(c.module),sorted(c.boms.map(signature)),sorted(c.deps.map(signature))]))),...(!bomKnown || !depsKnown ? [project.id] : [])]),
      search:[name,...modules.map(m => m.label),...[...rows.values()].flatMap(r => [r.label,...r.values.keys()])].join(' ').toLowerCase()};
  }

  function related(a,b,frequency,total) {
    if (!intersection(a.leafTypes,b.leafTypes).length) return null;
    const parents = intersection(a.parentGAs,b.parentGAs).sort();
    if (parents.length) return {score:3 + parents.length / Math.max(a.parentGAs.size,b.parentGAs.size),reason:'Parents communs (versions indépendantes) : ' + parents.join(', ')};
    const boms = intersection(a.bomGAs,b.bomGAs).sort();
    if (boms.length) return {score:2 + boms.length / Math.max(a.bomGAs.size,b.bomGAs.size),reason:'Imports de BOM communs hors profils : ' + boms.join(', ')};
    if (!a.depsKnown || !b.depsKnown || a.layout !== b.layout) return null;
    const common = intersection(a.directGAs,b.directGAs);
    if (common.length < 3) return null;
    const weight = ga => 1 + Math.log((total + 1) / ((frequency.get(ga) || 0) + 1));
    const union = unique([...a.directGAs,...b.directGAs]);
    const score = common.reduce((sum,c) => sum + weight(c),0) / union.reduce((sum,c) => sum + weight(c),0);
    return score >= .75 ? {score,reason:`Même composition de modules et ${common.length} dépendances directes communes (recouvrement pondéré ≥ 75 %).`} : null;
  }

  function build(data) {
    const byProject = new Map(data.projects.map(p => [p.id,[]]));
    data.modules.forEach(m => byProject.get(m.project)?.push(m));
    const profiles = data.projects.map(p => describe(data,p,byProject.get(p.id)));
    const groupMap = new Map(), incomplete = profiles.filter(p => !p.complete), frequency = new Map();
    profiles.forEach(p => p.directGAs.forEach(ga => frequency.set(ga,(frequency.get(ga) || 0) + 1)));
    for (const p of profiles.filter(p => p.complete)) {
      if (!groupMap.has(p.hierarchy)) groupMap.set(p.hierarchy,{key:p.hierarchy,projects:[],variants:[]});
      groupMap.get(p.hierarchy).projects.push(p);
    }
    const groups = [...groupMap.values()].sort((a,b) => b.projects.length - a.projects.length || a.key.localeCompare(b.key));
    const variants = [], families = [];
    groups.forEach((g,id) => {
      g.id = id; g.projects.sort((a,b) => a.name.localeCompare(b.name) || a.id - b.id);
      const vm = new Map();
      g.projects.forEach(p => { if (!vm.has(p.variant)) vm.set(p.variant,{key:p.variant,projects:[],group:id}); vm.get(p.variant).projects.push(p); });
      const coverage = v => Number(v.projects[0].bomKnown) + Number(v.projects[0].depsKnown);
      g.variants = [...vm.values()].sort((a,b) => b.projects.length - a.projects.length || coverage(b) - coverage(a) || a.key.localeCompare(b.key));
      g.variants.forEach(v => { v.id = variants.length; v.sample = v.projects[0]; variants.push(v); });
      g.sample = g.variants[0].sample;
      let closest = null;
      for (const f of families) {
        const match = related(g.sample,f.sample,frequency,profiles.length);
        if (match && (!closest || match.score > closest.match.score)) closest = {f,match};
      }
      if (!closest) {
        const parent = [...g.sample.parentGAs][0], bom = [...g.sample.bomGAs][0];
        const f = {id:families.length,sample:g.sample,groups:[],variants:[],projects:[],label:parent || bom || 'Structure ' + [...g.sample.leafTypes].map(p => p.toUpperCase()).join(' / ')};
        families.push(f); closest = {f,match:{reason:'Groupe de référence de cette famille',score:0}};
      }
      const f = closest.f;
      g.family = f.id; g.reason = closest.match.reason;
      f.groups.push(g); f.variants.push(...g.variants); f.projects.push(...g.projects);
      g.variants.forEach(v => { v.family = f.id; });
    });
    families.forEach(f => { f.baseline = f.variants[0]; f.search = f.projects.map(p => p.search).join(' '); });
    return {profiles,groups,variants,families,incomplete};
  }

  function differences(a,b) {
    const unknown = [], kinds = ['parents','modules'];
    if (a.bomKnown && b.bomKnown) kinds.push('bom'); else unknown.push('Imports de BOM incomplets : les absences ne sont pas comparables.');
    if (a.depsKnown && b.depsKnown) kinds.push('dependencies','paths'); else unknown.push('Résolution incomplète : les absences de dépendances ne sont pas comparables.');
    const rows = [];
    for (const key of unique([...a.rows.keys(),...b.rows.keys()]).sort()) {
      const x = a.rows.get(key), y = b.rows.get(key), r = x || y;
      if (!kinds.includes(r.kind)) continue;
      const before = rowValues(x), after = rowValues(y);
      if (signature(before) !== signature(after)) rows.push({kind:r.kind,label:r.label,before,after,beforeRefs:x?.refs || [],afterRefs:y?.refs || []});
    }
    if (!rows.length && !unknown.length && a.variant !== b.variant) rows.push({kind:'modules',label:'Répartition des observations entre modules',
      before:sorted(a.configurations.map(c => c.text)),after:sorted(b.configurations.map(c => c.text)),
      beforeRefs:a.configurations.map(c => ({module:c.module})),afterRefs:b.configurations.map(c => ({module:c.module}))});
    return {rows,unknown};
  }
  const api = {build,differences,rowValues};
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.MLA_STRUCTURES = api;
})(globalThis);
