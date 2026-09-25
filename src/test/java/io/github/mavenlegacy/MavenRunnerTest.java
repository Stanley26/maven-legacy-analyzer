package io.github.mavenlegacy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MavenRunnerTest {
    @TempDir Path temp;
    @Test void executesWrapperFromItsOwnDirectoryWithSpaceContainingArguments() throws Exception {
        Path repo = temp.resolve("repo with spaces"); Files.createDirectories(repo.resolve("module"));
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        Path wrapper = repo.resolve(windows ? "mvnw.cmd" : "mvnw");
        Files.writeString(wrapper, windows ? "@echo off\r\necho WRAPPER_OK\r\necho %CD%\r\necho %*\r\nexit /b 0\r\n" : "#!/bin/sh\necho WRAPPER_OK\npwd\nprintf '%s\\n' \"$@\"\n");
        Path log = temp.resolve("output.log");
        var options = new Configuration(null, temp).forRepository(repo);
        var result = new MavenRunner().run(wrapper, repo.resolve("module/pom.xml"), repo, options, "help:effective-pom",
                List.of("-Doutput=" + temp.resolve("evidence with spaces/result.xml")), log);
        assertEquals(0, result.exitCode(), Files.readString(log));
        String text = Files.readString(log);
        assertTrue(text.contains("WRAPPER_OK"));
        assertTrue(text.contains("repo with spaces"));
        assertTrue(text.contains("evidence with spaces"));
    }
    @Test void failsClosedForCmdExpansionCharacters() {
        assertThrows(IllegalArgumentException.class, () -> MavenRunner.quoteWindows("C:\\repo%PATH%"));
        assertThrows(IllegalArgumentException.class, () -> MavenRunner.quoteWindows("C:\\repo&echo"));
    }
}
