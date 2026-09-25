package io.github.mavenlegacy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static io.github.mavenlegacy.Model.*;
import static org.junit.jupiter.api.Assertions.*;

class ProvenanceTest {
    @TempDir Path root;
    private Pom read(String name, String xml) throws Exception {
        Path file = root.resolve(name); Files.writeString(file, xml); return new PomReader().read(file);
    }
    private Model.Module module(String effective, String raw, String parent, String parentKind) throws Exception {
        var module = new Model.Module();
        module.effective = read("effective.xml", effective);
        module.declared = read("app.xml", raw);
        module.evidence.put("effectivePom", "effective.xml");
        module.evidence.put("dependencyTree", "tree.json");
        module.sources.add(new SourcePom(new Coordinate("g", "app", "1"), "MODULE", "app.xml", "app.xml", "", module.declared));
        if (parent != null) module.sources.add(new SourcePom(new Coordinate("g", "parent", "1"), parentKind, "parent.xml", "parent.xml", "", read("parent.xml", parent)));
        module.dependencies.add(new ResolvedDependency(new Coordinate("g", "lib", "2"), "jar", "", "compile", false, List.of("g:app:1", "g:lib:2")));
        return module;
    }
    private static final String RAW = """
        <project><groupId>g</groupId><artifactId>app</artifactId><version>1</version>
        <properties><v>2</v></properties>
        <dependencies><dependency><groupId>g</groupId><artifactId>lib</artifactId></dependency></dependencies></project>
        """;
    private static final String PARENT = """
        <project><groupId>g</groupId><artifactId>parent</artifactId><version>1</version>
        <properties><v>1</v></properties>
        <dependencyManagement><dependencies><dependency><groupId>g</groupId><artifactId>lib</artifactId><version>${v}</version></dependency></dependencies></dependencyManagement></project>
        """;
    private static final String EFFECTIVE = """
        <project><groupId>g</groupId><artifactId>app</artifactId><version>1</version>
        <properties><v>2</v><!-- g:app:1, line 2 --></properties>
        <dependencies><dependency><groupId>g</groupId><artifactId>lib</artifactId><version>2</version><!-- g:parent:1, line 3 --></dependency></dependencies></project>
        """;
    @Test void linksInheritedExpressionAndLocalPropertyOverrideSeparately() throws Exception {
        var explanation = new Provenance().explain(module(EFFECTIVE, RAW, PARENT, "PARENT")).getFirst();
        assertEquals("SOURCE_LINKED", explanation.coverage());
        Fact declaration = explanation.facts().stream().filter(f -> f.kind().equals("MANAGED_SOURCE")).findFirst().orElseThrow();
        assertEquals("parent.xml", declaration.source()); assertEquals(3, declaration.line());
        Fact property = explanation.facts().stream().filter(f -> f.kind().equals("EFFECTIVE_PROPERTY")).findFirst().orElseThrow();
        assertEquals("app.xml", property.source()); assertEquals(2, property.line());
        assertTrue(explanation.facts().stream().anyMatch(f -> f.kind().equals("PROPERTY_OVERRIDE")));
    }
    @Test void neverAppliesConsumerPropertyToImportedModel() throws Exception {
        var explanation = new Provenance().explain(module(EFFECTIVE, RAW, PARENT, "MAVEN_CACHE")).getFirst();
        assertTrue(explanation.facts().stream().anyMatch(f -> f.kind().equals("EXTERNAL_PROPERTY")));
        assertFalse(explanation.facts().stream().anyMatch(f -> f.kind().equals("EFFECTIVE_PROPERTY") || f.kind().equals("PROPERTY_OVERRIDE")));
    }
    @Test void doesNotInferVersionSourceFromArtifactOriginOrInactiveProfile() throws Exception {
        String xml = EFFECTIVE.replace("<artifactId>lib</artifactId>", "<artifactId>lib</artifactId><!-- g:parent:1, line 3 -->")
                .replace("<version>2</version><!-- g:parent:1, line 3 -->", "<version>2</version>");
        var explanation = new Provenance().explain(module(xml, RAW, PARENT, "PARENT")).getFirst();
        assertEquals("PARTIAL", explanation.coverage());
        assertFalse(explanation.facts().stream().anyMatch(f -> f.kind().equals("MANAGED_SOURCE")));
        var inactive = module(EFFECTIVE.replace("<dependencies>", "<profiles><profile><id>inactive</id><dependencies>")
                .replace("</dependencies>", "</dependencies></profile></profiles>"), RAW, PARENT, "PARENT");
        assertEquals("PARTIAL", new Provenance().explain(inactive).getFirst().coverage());
    }
    @Test void doesNotClaimPropertyPriorityWhenInterpolationDisagrees() throws Exception {
        var explanation = new Provenance().explain(module(EFFECTIVE.replace("<v>2</v>", "<v>1</v>"), RAW, PARENT, "PARENT")).getFirst();
        assertTrue(explanation.limitations().stream().anyMatch(s -> s.contains("diffère")));
        assertFalse(explanation.facts().stream().anyMatch(f -> f.kind().equals("PROPERTY_OVERRIDE")));
    }
    @Test void keepsClassifierIdentityAndRejectsUnsafeCacheCoordinates() throws Exception {
        Pom model = read("types.xml", "<project><dependencies><dependency><groupId>g</groupId><artifactId>lib</artifactId><version>1</version><type>test-jar</type></dependency></dependencies></project>");
        var d = model.dependencies().getFirst();
        assertTrue(Provenance.matches(d, new ResolvedDependency(new Coordinate("g", "lib", "1"), "jar", "tests", "test", false, List.of())));
        assertFalse(Provenance.matches(d, new ResolvedDependency(new Coordinate("g", "lib", "1"), "jar", "", "test", false, List.of())));
        assertNull(ProvenanceCollector.cachedPom(root, new Coordinate("../g", "lib", "1")));
        assertNull(ProvenanceCollector.cachedPom(root, new Coordinate("g", "../../lib", "1")));
    }
    @Test void transitivePathAloneNeverClaimsConflictMediation() throws Exception {
        var module = module(EFFECTIVE, RAW, PARENT, "PARENT");
        module.dependencies = List.of(new ResolvedDependency(new Coordinate("g", "lib", "2"), "jar", "", "provided", false, List.of("g:app:1", "g:bridge:1", "g:lib:2")));
        var explanation = new Provenance().explain(module).getFirst();
        assertEquals("PARTIAL", explanation.coverage());
        assertEquals(1, explanation.facts().size());
        assertTrue(explanation.facts().getFirst().text().contains("transitive"));
    }
}
