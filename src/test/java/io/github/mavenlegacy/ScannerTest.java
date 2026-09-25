package io.github.mavenlegacy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ScannerTest {
    @TempDir Path root;
    @Test void discoversUnlistedDeepModulesAndContinuesAfterBadPom() throws Exception {
        Path repo = root.resolve("repo with spaces");
        Files.createDirectories(repo.resolve("a/b/c/d"));
        Files.writeString(repo.resolve("pom.xml"), "<project><artifactId>root</artifactId></project>");
        Files.writeString(repo.resolve("a/b/c/d/pom.xml"), "<project><artifactId>deep</artifactId></project>");
        Files.createDirectories(root.resolve("broken"));
        Files.writeString(root.resolve("broken/pom.xml"), "<invalid");
        String wrapperName = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "mvnw.cmd" : "mvnw";
        Files.writeString(repo.resolve(wrapperName), "");
        Files.createDirectories(root.resolve("report"));
        Files.writeString(root.resolve("report/pom.xml"), "<project/>");
        var result = new Scanner().scan(root, root.resolve("report"));
        assertEquals(3, result.modules().size());
        assertEquals(1, result.modules().stream().filter(m -> m.status.equals("FAILED")).count());
        var deep = result.modules().stream().filter(m -> m.id.contains("a/b/c/d")).findFirst().orElseThrow();
        assertEquals(repo.resolve(wrapperName).toString(), deep.wrapper);
        assertEquals(repo.toString(), deep.repository);
    }
    @Test void doesNotInventSystemMavenFallback() throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project><artifactId>root</artifactId></project>");
        var module = new Scanner().scan(root, root.resolve("report")).modules().getFirst();
        new Collector().collect(module, new Configuration(null, root).forRepository(root), root.resolve("report"), false);
        assertEquals("PARTIAL", module.status);
        assertEquals("NO_WRAPPER", module.issues.getFirst().code());
    }
}
