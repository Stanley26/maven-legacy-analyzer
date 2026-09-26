const test = require('node:test');
const assert = require('node:assert/strict');
const E = require('../../main/resources/explorer/engine.js');
const S = require('../../main/resources/explorer/structures.js');

function fixture(specs) {
  const d = {projects:[],modules:[],coordinates:[],nodes:[],uses:[],issues:[]};
  for (const [id,spec] of specs.entries()) {
    const name = spec.name || `app-${id}`, parent = spec.parent ?? 'g:web-parent:1';
    d.projects.push({id,name});
    const root = `apps:${name}:${id + 1}`, mid = d.modules.length;
    const node = (gav,parent='',imports=[]) => ({gav,parent,imports,importsKnown:true,evidence:`evidence/${name}/source.xml`});
    const models = [node(root,parent,spec.imports || [])];
    if (parent && !spec.missingParent) models.push(node(parent,spec.ancestor || ''));
    if (spec.ancestor) models.push(node(spec.ancestor));
    for (const imported of spec.imports || []) models.push(node(imported.gav));
    models.push(...(spec.models || []));
    const m = {id:mid,project:id,label:`${name}/pom.xml`,path:'pom.xml',gav:root,status:spec.partial ? 'PARTIAL' : 'RESOLVED',issues:[],
      structure:{available:true,packaging:spec.packaging || 'war',children:[],root,models,treeKnown:!spec.partial}};
    d.modules.push(m);
    const deps = spec.deps || [{ga:'g:security',v:'1'},{ga:'g:web',v:'2'},{ga:'g:data',v:'3'}];
    for (const [index,dep] of deps.entries()) {
      const cid = d.coordinates.length;
      d.coordinates.push({ga:dep.ga,v:dep.v || '1',type:dep.type || 'jar',classifier:dep.classifier || '',internal:false});
      const path = [root,...(dep.via || []),`${dep.ga}:${dep.v || '1'}`].map(gav => { d.nodes.push(gav); return d.nodes.length - 1; });
      d.uses.push([mid,cid,dep.scope || 'compile',path,!!dep.override,dep.origin || 'parent',index]);
    }
  }
  return E.prepare(d);
}

test('automatic grouping ignores application names and root versions, counts projects once',() => {
  const result = S.build(fixture([{name:'alpha'},{name:'beta'},{name:'gamma'}]));
  assert.equal(result.families.length,1); assert.equal(result.groups.length,1); assert.equal(result.variants.length,1);
  assert.equal(result.families[0].projects.length,3); assert.equal(result.incomplete.length,0);
});

test('different parent versions are neighboring hierarchies and scopes/overrides remain variants',() => {
  const d = fixture([{}, {}, {parent:'g:web-parent:2'}, {deps:[{ga:'g:security',v:'2',scope:'provided',override:true}]}]);
  const r = S.build(d);
  assert.equal(r.families.length,1); assert.equal(r.groups.length,2); assert.equal(r.variants.length,3);
  assert.equal(r.families[0].baseline.projects.length,2);
  const parentDelta = S.differences(r.profiles[0],r.profiles[2]);
  assert.ok(parentDelta.rows.some(row => row.kind === 'parents' && row.after.some(value => value.includes('g:web-parent:2'))));
  const deps = S.differences(r.profiles[0],r.profiles[3]);
  assert.ok(deps.rows.some(row => row.kind === 'dependencies' && row.after.some(value => value.includes('provided') && value.includes('override'))));
});

test('types, classifiers, dependency paths and introduction modes produce explicit differences',() => {
  const r = S.build(fixture([{deps:[{ga:'g:lib'}]},{deps:[{ga:'g:lib',via:['g:bridge:1']}]},{deps:[{ga:'g:lib',classifier:'tests'}]}]));
  assert.equal(r.groups.length,1); assert.equal(r.variants.length,3);
  assert.ok(S.differences(r.profiles[0],r.profiles[1]).rows.some(row => row.kind === 'paths'));
  const typed = S.differences(r.profiles[0],r.profiles[2]).rows.filter(row => row.kind === 'dependencies');
  assert.equal(typed.length,2); assert.ok(typed.some(row => row.label.includes('jar:tests')));
});

test('unavailable, symbolic and cyclic parents stay outside complete hierarchy groups',() => {
  const d = fixture([{}, {missingParent:true}, {parent:'g:web-parent:${v}'}, {ancestor:'g:web-parent:1'}]);
  d.modules[3].structure.models = d.modules[3].structure.models.filter((n,i) => i < 2);
  const r = S.build(d);
  assert.equal(r.groups[0].projects.length,1); assert.equal(r.incomplete.length,3);
  assert.ok(r.incomplete.some(p => p.problems.some(x => x.includes('cycle'))));
  const old = fixture([{}]); delete old.modules[0].structure;
  assert.equal(S.build(old).incomplete.length,1);
});

test('missing dependency resolution is not interpreted as removal or identical observations',() => {
  const r = S.build(fixture([{}, {partial:true,deps:[]}, {partial:true,deps:[]}]));
  assert.equal(r.groups.length,1); assert.equal(r.variants.length,3);
  const diff = S.differences(r.profiles[0],r.profiles[1]);
  assert.ok(diff.unknown.length); assert.ok(!diff.rows.some(row => ['dependencies','paths'].includes(row.kind)));
});

test('BOM order, profile labels and missing BOM models remain visible without claiming activation',() => {
  const a = {gav:'g:bom-a:1',profile:'',line:4}, b = {gav:'g:bom-b:1',profile:'optional',line:8};
  const d = fixture([{imports:[a,b]}, {imports:[b,a]}, {imports:[a]}]);
  d.modules[2].structure.models = d.modules[2].structure.models.filter(n => n.gav !== a.gav);
  const r = S.build(d);
  assert.equal(r.variants.length,3); assert.equal(r.profiles[2].bomKnown,false);
  assert.ok(S.differences(r.profiles[0],r.profiles[1]).rows.some(row => row.kind === 'bom' && row.after.some(value => value.includes('activation non déduite'))));
  assert.deepEqual([...r.profiles[0].bomGAs],['g:bom-a']);
  assert.ok(!S.differences(r.profiles[0],r.profiles[2]).rows.some(row => row.kind === 'bom'));
});

function multiModule(changeAggregation=false) {
  const d = fixture([{name:'first',packaging:'pom'}, {name:'second',packaging:'pom'}]);
  d.uses = []; d.coordinates = []; d.nodes = [];
  for (const project of d.projects) {
    const parent = d.modules[project.id], suffix = project.id ? 'different-name' : 'web';
    const mid = d.modules.length, root = `apps:${suffix}:${project.id + 1}`;
    parent.structure.children = [suffix];
    d.modules.push({id:mid,project:project.id,label:`${project.name}/${suffix}/pom.xml`,path:`${suffix}/pom.xml`,gav:root,status:'RESOLVED',issues:[],
      structure:{available:true,packaging:'war',children:[],root,treeKnown:true,models:[{gav:root,parent:parent.gav,imports:[],importsKnown:true}]}});
    if (changeAggregation && project.id) parent.structure.children = [];
  }
  return E.prepare(d);
}

test('local inheritance and module names normalize but aggregation relationships remain significant',() => {
  const r = S.build(multiModule());
  assert.equal(r.groups.length,1); assert.equal(r.variants.length,1); assert.equal(r.groups[0].projects.length,2);
  assert.equal(S.build(multiModule(true)).groups.length,2);
  const missing = multiModule(); missing.modules[0].structure.children.push('absent');
  assert.equal(S.build(missing).incomplete.length,1);
});

test('dependency-only neighbors require substantial overlap, not a ubiquitous library alone',() => {
  const r = S.build(fixture([{parent:'g:parent-a:1'}, {parent:'g:parent-b:1'}, {parent:'g:parent-c:1',deps:[{ga:'g:security'},{ga:'other:x'},{ga:'other:y'}]}]));
  assert.equal(r.families.length,2);
  assert.ok(r.families.some(f => f.projects.length === 2 && f.groups.some(g => g.reason.includes('dépendances directes communes'))));
});

test('neighbors are anchored to the reference instead of transitively merging unrelated hierarchies',() => {
  const r = S.build(fixture([{parent:'g:a:1',deps:[]},{parent:'g:b:1',ancestor:'g:a:1',deps:[]},{parent:'g:b:2',deps:[]}]));
  assert.equal(r.families.length,2); assert.equal(r.families[0].projects.length,2);
});

test('400 applications / 100000 dependency observations group without losing scopes or projects',() => {
  const deps = Array.from({length:250},(_,i) => ({ga:`g:lib-${i}`,v:'1',scope:i % 2 ? 'provided' : 'compile'}));
  const r = S.build(fixture(Array.from({length:400},(_,i) => ({parent:i < 300 ? 'g:web-parent:1' : 'g:web-parent:2',deps}))));
  assert.equal(r.families.length,1); assert.equal(r.groups.length,2); assert.equal(r.variants.length,2);
  assert.equal(r.families[0].projects.length,400); assert.equal(r.families[0].baseline.projects.length,300);
});

test('equal dependency totals cannot hide different distributions between sibling modules of the same type',() => {
  const d = fixture([{deps:[]},{deps:[]}]);
  for (let p=0;p<2;p++) {
    const m = structuredClone(d.modules[p]);
    m.id = d.modules.length; m.path='extra/pom.xml'; m.gav=`apps:extra-${p}:1`;
    m.structure.root=m.gav; m.structure.models[0].gav=m.gav;
    d.modules.push(m);
  }
  function dep(mid,artifact) {
    const cid=d.coordinates.length, first=d.nodes.length;
    d.coordinates.push({ga:`g:${artifact}`,v:'1',type:'jar',classifier:'',internal:false});
    d.nodes.push(d.modules[mid].gav,`g:${artifact}:1`);
    d.uses.push([mid,cid,'compile',[first,first+1],false,'parent',0]);
  }
  dep(0,'a'); dep(0,'b'); dep(1,'a'); dep(3,'b');
  const r=S.build(E.prepare(d));
  assert.equal(r.groups.length,1); assert.equal(r.variants.length,2);
  const delta=S.differences(r.profiles[0],r.profiles[1]);
  assert.equal(delta.rows.length,1); assert.equal(delta.rows[0].label,'Répartition des observations entre modules');
});

test('duplicate local coordinates are ambiguous and equal-sized complete variants are preferred as reference',() => {
  const d=fixture([{}]); const duplicate=structuredClone(d.modules[0]);
  duplicate.id=1; duplicate.path='duplicate/pom.xml'; d.modules.push(duplicate);
  assert.equal(S.build(E.prepare(d)).incomplete.length,1);
  const r=S.build(fixture([{partial:true},{name:'complete'}]));
  assert.equal(r.families[0].baseline.sample.name,'complete');
});
