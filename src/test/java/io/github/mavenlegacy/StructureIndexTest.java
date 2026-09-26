package io.github.mavenlegacy;

import org.junit.jupiter.api.Test;
import java.util.*;
import static io.github.mavenlegacy.Model.*;
import static org.junit.jupiter.api.Assertions.*;

class StructureIndexTest {
    private Pom pom(String artifact, String version, Parent parent, List<Declaration> imports) {
        return new Pom(new Coordinate("g",artifact,version),"war",parent,List.of(),List.of(),List.of(),imports,List.of());
    }
    private SourcePom source(Pom p, String kind) {
        return new SourcePom(p.coordinate(),kind,"/unavailable", "evidence/" + p.coordinate().artifactId() + ".xml","hash",p);
    }
    private Declaration imported(String artifact, String version, String profile) {
        return new Declaration(new Coordinate("g",artifact,version),"pom","","import",false,List.of(),profile,12,"",12,"");
    }
    @Test void projectsOnlyReachableAncestryAndBomDeclarationsUsingResolvedCoordinatesAndSourceOrder() {
        var m = new Model.Module(); m.status = "RESOLVED"; m.evidence.put("dependencyTree","tree.json");
        var rawParent = new Parent(new Coordinate("g","parent","${parent.version}"),"");
        var resolvedParent = new Parent(new Coordinate("g","parent","7"),"");
        m.declared = pom("app","1",rawParent,List.of(imported("bom","${bom.version}","optional")));
        m.effective = pom("app","1",resolvedParent,List.of());
        m.sources.add(source(m.declared,"MODULE"));
        m.sources.add(source(pom("parent","7",null,List.of()),"PARENT_CACHE"));
        m.sources.add(source(pom("bom","3",new Parent(new Coordinate("g","bom-parent","2"),""),List.of()),"BOM_CACHE"));
        m.sources.add(source(pom("bom-parent","2",null,List.of()),"EXTERNAL_PARENT_CACHE"));
        m.sources.add(source(pom("unrelated-dependency","9",null,List.of(imported("unrelated-bom","1",""))),"ARTIFACT_CACHE"));
        m.pomRelations.add(new PomRelation("g:app:1",new Coordinate("g","bom","3"),"BOM_IMPORT_DECLARATION","optional","COLLECTED",""));
        var json = SavedReports.MAPPER.valueToTree(StructureIndex.module(m));
        assertTrue(json.path("treeKnown").asBoolean());
        assertEquals(4,json.path("models").size());
        var root = json.path("models").get(0);
        assertEquals("g:parent:7",root.path("parent").asText());
        assertEquals("g:bom:3",root.path("imports").get(0).path("gav").asText());
        assertEquals("optional",root.path("imports").get(0).path("profile").asText());
        assertEquals(12,root.path("imports").get(0).path("line").asInt());
        assertFalse(json.toString().contains("unrelated"));
    }
    @Test void missingArchivesAndEffectiveOnlyModelsDoNotClaimKnownImportsOrDependencyTrees() {
        var m = new Model.Module(); m.status = "RESOLVED";
        assertEquals(false,StructureIndex.module(m).get("available"));
        m.effective = pom("app","1",new Parent(new Coordinate("g","missing","1"),""),List.of());
        var json = SavedReports.MAPPER.valueToTree(StructureIndex.module(m));
        assertFalse(json.path("treeKnown").asBoolean());
        assertEquals(1,json.path("models").size());
        assertFalse(json.path("models").get(0).path("importsKnown").asBoolean());
        assertEquals("g:missing:1",json.path("models").get(0).path("parent").asText());
    }
    @Test void incompleteCoordinateRelationsPreserveRawDeclarationsWithoutGuessing() {
        var m = new Model.Module();
        m.declared = pom("app","1",null,List.of(imported("a","${a}",""),imported("b","${b}","")));
        m.pomRelations.add(new PomRelation("g:app:1",new Coordinate("g","a","5"),"BOM_IMPORT_DECLARATION","","COLLECTED",""));
        var json = SavedReports.MAPPER.valueToTree(StructureIndex.module(m));
        assertEquals("g:a:${a}",json.path("models").get(0).path("imports").get(0).path("gav").asText());
        assertEquals("g:b:${b}",json.path("models").get(0).path("imports").get(1).path("gav").asText());
    }
}
