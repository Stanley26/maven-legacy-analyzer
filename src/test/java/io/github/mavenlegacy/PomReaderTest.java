package io.github.mavenlegacy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class PomReaderTest {
    @TempDir Path directory;
    @Test void preservesRawVersionsProfilesAndManagementWithoutInventingResolution() throws Exception {
        Path pom = directory.resolve("pom.xml");
        Files.writeString(pom, """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <parent><groupId>org.demo</groupId><artifactId>parent</artifactId><version>7</version><relativePath/></parent>
              <artifactId>app</artifactId>
              <properties><component.version>1.0.0</component.version></properties>
              <dependencyManagement><dependencies><dependency><groupId>org.demo</groupId><artifactId>bom</artifactId><version>1</version><type>pom</type><scope>import</scope></dependency></dependencies></dependencyManagement>
              <dependencies><dependency><groupId>com.example.components</groupId><artifactId>component-core</artifactId><scope>provided</scope><exclusions><exclusion><groupId>org.demo</groupId><artifactId>excluded</artifactId></exclusion></exclusions></dependency></dependencies>
              <profiles><profile><id>alternative-version</id><properties><component.version>2.0.0</component.version></properties><dependencies><dependency><groupId>org.demo</groupId><artifactId>optional</artifactId><version>${optional.version}</version><optional>true</optional></dependency></dependencies></profile></profiles>
            </project>
            """);
        var model = new PomReader().read(pom);
        assertEquals("", model.coordinate().groupId());
        assertEquals("", model.dependencies().getFirst().coordinate().version());
        assertEquals("provided", model.dependencies().getFirst().scope());
        assertEquals("org.demo:excluded", model.dependencies().getFirst().exclusions().getFirst());
        assertEquals("", model.parent().relativePath());
        assertEquals("import", model.managed().getFirst().scope());
        assertEquals("alternative-version", model.dependencies().get(1).profile());
        assertEquals("${optional.version}", model.dependencies().get(1).coordinate().version());
        assertTrue(model.dependencies().get(1).optional());
        assertTrue(model.dependencies().getFirst().line() > 0);
    }
    @Test void retainsMavenVersionOriginComments() throws Exception {
        Path pom = directory.resolve("effective.xml");
        Files.writeString(pom, """
            <project><groupId>g</groupId><artifactId>a</artifactId><version>1</version>
              <dependencies><dependency><groupId>g</groupId><artifactId>lib</artifactId><version>2</version><!-- g:parent:9, line 42 --></dependency></dependencies>
            </project>
            """);
        assertEquals("g:parent:9, line 42", new PomReader().read(pom).dependencies().getFirst().origin());
    }
    @Test void blocksExternalEntities() throws Exception {
        Path pom = directory.resolve("pom.xml");
        Files.writeString(pom, "<!DOCTYPE project [<!ENTITY secret SYSTEM 'file:///private'>]><project><artifactId>&secret;</artifactId></project>");
        assertThrows(Exception.class, () -> new PomReader().read(pom));
    }
}
