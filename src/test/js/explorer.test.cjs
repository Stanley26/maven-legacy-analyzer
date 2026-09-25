const test = require('node:test');
const assert = require('node:assert/strict');
const E = require('../../main/resources/explorer/engine.js');
function fixture() {
  return E.prepare({projects:[{id:0,name:'consumer'},{id:1,name:'indirect'},{id:2,name:'provider'}],modules:[
    {id:0,project:0,label:'consumer/root',gav:'g:app:1',status:'RESOLVED',issues:[]},
    {id:1,project:0,label:'consumer/web',gav:'g:web:1',status:'RESOLVED',issues:[]},
    {id:2,project:1,label:'indirect/root',gav:'g:other:1',status:'RESOLVED',issues:[]},
    {id:3,project:2,label:'provider/root',gav:'g:shared:1',status:'RESOLVED',issues:[]}],
    coordinates:[{ga:'g:shared',v:'1',type:'jar',classifier:'',internal:true},{ga:'g:leaf',v:'2',type:'jar',classifier:'',internal:false}],
    nodes:['g:app:1','g:shared:1','g:leaf:2','g:web:1','g:other:1','g:bridge:1'],
    uses:[[0,0,'compile',[0,1],true,'parent',0],[1,0,'provided',[3,5,1],false,'bom',0],[2,0,'compile',[4,5,1],false,'unknown',0],[3,0,'test',[1,1],false,'local',0],[0,1,'compile',[0,1,2],false,'unknown',1]],issues:[]});
}
test('distinct project counts and disjoint impact do not count modules or provider self-usage twice',() => {
  const d = fixture(), rows = E.components(d,d.uses);
  assert.equal(rows[0].projects.size,3);
  const impact = E.impact(d,d.uses);
  assert.deepEqual([...impact.direct],[0]); assert.deepEqual([...impact.indirect],[1]); assert.equal(impact.all.size,2);
  assert.equal(E.projects(d,d.uses)[0].modules.length,2);
});
test('scope, family, version and proven override filters compose; inventory stays visible without dependency filters',() => {
  const d = fixture(); d.modules.push({id:4,project:1,label:'indirect/extra',status:'INVENTORIED',issues:[]}); d.byModule.push([]);
  const f = {q:'SHARED',family:'g',version:'1',scope:'compile',override:true};
  assert.equal(E.filter(d,f).length,1); assert.equal(E.projects(d,E.filter(d,f),f).length,1);
  assert.equal(E.projects(d,d.uses).flatMap(p => p.modules).length,5);
  assert.equal(E.projects(d,E.filter(d,{anomaly:true}),{anomaly:true})[0].modules[0].id,4);
});
test('downstream surface uses observed paths, not unrelated dependencies in the same project',() => {
  const d = fixture(); const result = E.surface(d,[d.uses[0]]);
  assert.equal(result.length,1); assert.equal(d.coordinates[result[0][1]].ga,'g:leaf');
});
test('comparison finds version and scope changes but treats absent, partial and ambiguous modules as unknown',() => {
  const a = fixture(), b = fixture(); b.coordinates[1].v = '3'; b.uses[0][2] = 'provided';
  b.modules[1].status = 'PARTIAL'; b.modules[2].label = 'renamed/root';
  let result = E.compare(a,b);
  assert.equal(result.matched,2); assert.equal(result.rows.length,2); assert.equal(result.unknown.length,3);
  assert.equal(result.rows.find(r => r.ga === 'g:shared').override,true);
  assert.ok(result.rows.every(r => r.kind === 'Modifiée'));
  b.modules[3].label = b.modules[0].label; result = E.compare(a,b);
  assert.ok(result.unknown.some(r => r.reason === 'Nom de module ambigu'));
});
test('classifier changes are separate additions/removals; import supports old schemas without inventing provenance',() => {
  const a = fixture(), b = fixture(); b.coordinates[1].classifier = 'tests';
  assert.deepEqual(E.compare(a,b).rows.map(r => r.kind).sort(),['Ajoutée','Retirée']);
  const d = E.fromArchive({schemaVersion:'0.1',modules:[{id:'a/pom.xml',repository:'/missing/a',status:'RESOLVED',dependencies:[{coordinate:{groupId:'g',artifactId:'x',version:'1'},type:'jar',classifier:'',scope:'provided',path:['g:a:1','g:x:1']}]}]});
  assert.equal(d.modules[0].label,'a/pom.xml'); assert.equal(d.uses[0][4],false); assert.equal(d.uses[0][5],'unknown');
  assert.throws(() => E.fromArchive({schemaVersion:'99',modules:[]}));
});
test('400 projects / 100000 observations retain distinct counts and filterable scopes',() => {
  const d = {projects:[],modules:[],coordinates:[],nodes:['g:root:1','g:leaf:1'],uses:[],issues:[]};
  for(let c=0;c<250;c++) d.coordinates.push({ga:`g:lib-${c}`,v:'1',type:'jar',classifier:'',internal:false});
  for(let p=0;p<400;p++) { d.projects.push({id:p,name:`project-${p}`}); d.modules.push({id:p,project:p,label:`project-${p}/pom.xml`,gav:`g:project-${p}:1`,status:'RESOLVED',issues:[]}); for(let c=0;c<250;c++) d.uses.push([p,c,c%2 ? 'provided' : 'compile',[0,1],false,'unknown',c]); }
  E.prepare(d); const selected = E.filter(d,{scope:'provided'}), rows = E.components(d,selected);
  assert.equal(selected.length,50000); assert.equal(rows.length,125); assert.equal(rows[0].projects.size,400); assert.equal(E.projects(d,selected,{scope:'provided'}).length,400);
});
