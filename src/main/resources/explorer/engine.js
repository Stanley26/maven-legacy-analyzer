(function (root) {
  'use strict';
  const unique = values => [...new Set(values)];
  const gav = c => `${c.ga}:${c.v}`;
  const key = c => `${c.ga}|${c.type}|${c.classifier}`;
  const anomaly = m => m.status !== 'RESOLVED' || m.issues.length > 0;
  function prepare(data) {
    data.byModule = data.modules.map(() => []);
    data.uses.forEach(u => data.byModule[u[0]].push(u));
    return data;
  }
  function filter(data, f = {}) {
    const q = (f.q || '').toLowerCase();
    return data.uses.filter(u => {
      const c = data.coordinates[u[1]], m = data.modules[u[0]];
      return (!q || c.ga.toLowerCase().includes(q)) && (!f.family || c.ga.split(':')[0] === f.family)
        && (!f.version || c.v === f.version) && (!f.scope || u[2] === f.scope)
        && (!f.override || u[4]) && (!f.anomaly || anomaly(m));
    });
  }
  function components(data, uses) {
    const rows = new Map();
    for (const u of uses) {
      const c = data.coordinates[u[1]], p = data.modules[u[0]].project;
      if (!rows.has(c.ga)) rows.set(c.ga, {ga:c.ga, versions:new Set(), projects:new Set(), modules:new Set(), overrides:new Set(), internal:false, uses:[]});
      const row = rows.get(c.ga);
      row.versions.add(c.v); row.projects.add(p); row.modules.add(u[0]); row.uses.push(u);
      if (u[4]) row.overrides.add(p);
      row.internal ||= c.internal;
    }
    return [...rows.values()].sort((a,b) => b.projects.size - a.projects.size || a.ga.localeCompare(b.ga));
  }
  function projects(data, uses, f = {}) {
    const restrict = !!(f.q || f.family || f.version || f.scope || f.override);
    const selected = new Set(uses.map(u => u[0]));
    const rows = new Map();
    for (const m of data.modules) {
      if ((restrict && !selected.has(m.id)) || (f.anomaly && !anomaly(m))) continue;
      if (!rows.has(m.project)) rows.set(m.project, {id:m.project, name:data.projects[m.project].name, modules:[], components:new Set(), overrides:false, issues:0});
      const row = rows.get(m.project);
      row.modules.push(m); row.issues += m.issues.length + (m.status === 'RESOLVED' ? 0 : 1);
    }
    for (const u of uses) {
      const r = rows.get(data.modules[u[0]].project);
      if (r) { r.components.add(data.coordinates[u[1]].ga); r.overrides ||= u[4]; }
    }
    return [...rows.values()].sort((a,b) => a.name.localeCompare(b.name));
  }
  function impact(data, uses) {
    const direct = new Set(), indirect = new Set(), unknown = new Set(), all = new Set();
    const providers = new Map();
    data.modules.forEach(m => { if (!providers.has(m.gav)) providers.set(m.gav, new Set()); providers.get(m.gav).add(m.project); });
    for (const u of uses) {
      const c = data.coordinates[u[1]], p = data.modules[u[0]].project;
      if (!c.internal || providers.get(gav(c))?.has(p)) continue;
      all.add(p);
      if (u[3].length === 2) direct.add(p);
      else if (u[3].length > 2) indirect.add(p);
      else unknown.add(p);
    }
    direct.forEach(p => { indirect.delete(p); unknown.delete(p); });
    indirect.forEach(p => unknown.delete(p));
    return {direct, indirect, unknown, all};
  }
  function surface(data, selectedUses) {
    const targets = new Map();
    selectedUses.forEach(u => {
      if (!targets.has(u[0])) targets.set(u[0], new Set());
      targets.get(u[0]).add(gav(data.coordinates[u[1]]));
    });
    return data.uses.filter(u => {
      const sought = targets.get(u[0]);
      return sought && u[3].slice(0,-1).some(n => sought.has(data.nodes[n]));
    });
  }
  // Browser file imports use only observations present in a supported saved analysis.
  function fromArchive(a) {
    if (!['0.1','0.2'].includes(a.schemaVersion) || !Array.isArray(a.modules)) throw new Error('Format d’analyse non pris en charge.');
    const d = {date:a.generatedAt, mode:a.mode, root:a.root, projects:[], modules:[], coordinates:[], nodes:[], uses:[], issues:a.issues || []};
    const ps = new Map(), cs = new Map(), ns = new Map();
    const coord = c => `${c.groupId}:${c.artifactId}:${c.version}`;
    const known = new Set(a.modules.map(m => m.effective || m.declared).filter(Boolean).map(p => coord(p.coordinate)));
    a.modules.forEach((m,i) => {
      const repo = m.repository || m.id;
      if (!ps.has(repo)) { ps.set(repo,d.projects.length); d.projects.push({id:d.projects.length,name:m.projectName || repo.split(/[\\/]/).pop()}); }
      const project = ps.get(repo), p = m.effective || m.declared;
      const label = m.projectName && m.modulePath ? `${m.projectName}/${m.modulePath}` : m.id;
      d.modules.push({id:i,project,label,path:m.modulePath || m.id,gav:p ? coord(p.coordinate) : '',status:m.status,issues:m.issues || [],provenance:m.context?.provenanceCollection || 'UNKNOWN'});
      (m.dependencies || []).forEach((dep,j) => {
        const c = {ga:`${dep.coordinate.groupId}:${dep.coordinate.artifactId}`,v:dep.coordinate.version,type:dep.type,classifier:dep.classifier,internal:known.has(coord(dep.coordinate))};
        const k = `${key(c)}|${c.v}`;
        if (!cs.has(k)) { cs.set(k,d.coordinates.length); d.coordinates.push(c); }
        const path = (dep.path || []).map(n => { if (!ns.has(n)) { ns.set(n,d.nodes.length); d.nodes.push(n); } return ns.get(n); });
        const override = (m.explanations?.[j]?.facts || []).some(f => f.kind === 'PROPERTY_OVERRIDE');
        d.uses.push([i,cs.get(k),dep.scope,path,override,'unknown',j]);
      });
    });
    return prepare(d);
  }
  function compare(a,b) {
    const group = d => {
      const result = new Map();
      d.modules.forEach(m => { if (!result.has(m.label)) result.set(m.label,[]); result.get(m.label).push(m); });
      return result;
    };
    const am = group(a), bm = group(b), rows = [], unknown = [];
    let matched = 0;
    const deps = (d,m) => {
      const r = new Map();
      for (const u of d.byModule[m.id]) {
        const c = d.coordinates[u[1]], k = key(c);
        if (!r.has(k)) r.set(k,{ga:c.ga,type:c.type,classifier:c.classifier,values:new Set(),versions:new Set(),override:false});
        const x = r.get(k); x.values.add(`${c.v} · ${u[2]}`); x.versions.add(c.v); x.override ||= u[4];
      }
      return r;
    };
    for (const label of unique([...am.keys(),...bm.keys()]).sort()) {
      const x = am.get(label) || [], y = bm.get(label) || [];
      if (x.length !== 1 || y.length !== 1 || x[0].status !== 'RESOLVED' || y[0].status !== 'RESOLVED') {
        unknown.push({label,reason:!x.length || !y.length ? 'Module absent d’une analyse' : x.length > 1 || y.length > 1 ? 'Nom de module ambigu' : 'Résolution incomplète'}); continue;
      }
      matched++;
      const ad = deps(a,x[0]), bd = deps(b,y[0]);
      for (const k of unique([...ad.keys(),...bd.keys()])) {
        const av = ad.get(k), bv = bd.get(k), before = [...(av?.values || [])].sort(), after = [...(bv?.values || [])].sort();
        if (JSON.stringify(before) === JSON.stringify(after)) continue;
        const c = av || bv;
        rows.push({label,project:a.projects[x[0].project].name,ga:c.ga,type:c.type,classifier:c.classifier,before,after,override:av?.override || false,kind:!av ? 'Ajoutée' : !bv ? 'Retirée' : 'Modifiée'});
      }
    }
    return {rows,unknown,matched};
  }
  const api = {prepare,filter,components,projects,impact,surface,fromArchive,compare,unique,gav,key,anomaly};
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.MLA = api;
})(globalThis);
