package io.github.mavenlegacy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static io.github.mavenlegacy.Model.*;
import static org.junit.jupiter.api.Assertions.*;

class CachePreferenceTest {
    @TempDir Path root;
    private Path wrapper(boolean localAvailable, boolean onlineAvailable) throws Exception {
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        Path file = root.resolve(windows ? "mvnw.cmd" : "mvnw");
        String script = windows ? """
            @echo off
            setlocal
            set "LOCAL_MODE="
            for %%a in (%*) do if "%%~a"=="-o" set "LOCAL_MODE=1"
            if defined LOCAL_MODE (
              echo CACHE_ATTEMPT
              exit /b LOCAL_EXIT
            )
            echo ONLINE_ATTEMPT
            exit /b ONLINE_EXIT
            """.replace("LOCAL_EXIT", localAvailable ? "0" : "13").replace("ONLINE_EXIT", onlineAvailable ? "0" : "17").replace("\n","\r\n") : """
            #!/bin/sh
            for arg in "$@"; do
              if [ "$arg" = '-o' ]; then
                echo CACHE_ATTEMPT
                exit %d
              fi
            done
            echo ONLINE_ATTEMPT
            exit %d
            """.formatted(localAvailable ? 0 : 13, onlineAvailable ? 0 : 17);
        Files.writeString(file,script); return file;
    }
    private Model.Module collect(boolean localAvailable, boolean onlineAvailable, boolean offline) throws Exception {
        Path wrapper = wrapper(localAvailable,onlineAvailable);
        var m = new Model.Module();
        Collector.collectOne(m,new MavenRunner(),wrapper,root.resolve("pom.xml"),root,new Configuration(null,root).forRepository(root),
                "help:effective-pom",offline ? List.of("-o") : List.of(),root.resolve("logs/effective-pom.log"),root,() -> {});
        return m;
    }
    @Test void completesFromLocalMavenWithoutAttemptingOnlineResolution() throws Exception {
        var m = collect(true,false,false);
        assertEquals(1,m.invocations.size()); assertEquals(0,m.invocations.getFirst().exitCode()); assertTrue(m.issues.isEmpty());
        assertEquals("logs/effective-pom-cache.log",m.evidence.get("effective-pom-cache.log"));
        assertTrue(Files.readString(root.resolve(m.evidence.get("effective-pom-cache.log"))).contains("CACHE_ATTEMPT"));
        assertFalse(Files.exists(root.resolve("logs/effective-pom.log")));
    }
    @Test void retriesOnlineOnlyWhenLocalResolutionFailedAndRetainsBothLogs() throws Exception {
        var m = collect(false,true,false);
        assertEquals(2,m.invocations.size()); assertEquals(13,m.invocations.get(0).exitCode()); assertEquals(0,m.invocations.get(1).exitCode());
        assertTrue(m.issues.isEmpty(),"A recovered cache miss is not a collection failure");
        assertTrue(Files.readString(root.resolve("logs/effective-pom.log")).contains("ONLINE_ATTEMPT"));
        assertTrue(Files.isRegularFile(root.resolve("logs/effective-pom-cache.log")));
    }
    @Test void explicitOfflineNeverRetriesOnlineAndTerminalFailuresStayVisible() throws Exception {
        var offline = collect(false,true,true);
        assertEquals(1,offline.invocations.size()); assertFalse(offline.issues.isEmpty());
        var failed = collect(false,false,false);
        assertEquals(2,failed.invocations.size()); assertEquals(1,failed.issues.size());
    }
    @Test void ignoresOtherCachedVersionsAndRequestsOnlyExactCoordinates() throws Exception {
        Path cache = root.resolve("configured-user-cache"), report = root.resolve("report"), evidence = report.resolve("evidence");
        Files.createDirectories(cache.resolve("g/library/1")); Files.createDirectories(evidence);
        Files.writeString(cache.resolve("g/library/1/library-1.pom"),"<project><groupId>g</groupId><artifactId>library</artifactId><version>1</version></project>");
        Path pom = root.resolve("pom.xml"); Files.writeString(pom,"<project><groupId>g</groupId><artifactId>app</artifactId><version>1</version></project>");
        var m = new Model.Module(); m.wrapper = wrapper(false,false).toString(); m.pomPath = pom.toString(); m.repository = root.toString(); m.effective = new PomReader().read(pom);
        m.dependencies.add(new ResolvedDependency(new Coordinate("g","library","2"),"jar","","compile",false,List.of("g:app:1","g:library:2")));
        new PomGraphCollector().collect(m,new Configuration(null,root).forRepository(root),evidence,report,cache,false);
        assertEquals("g:library:2",m.pomRelations.getFirst().target().gav()); assertEquals("UNAVAILABLE",m.pomRelations.getFirst().status());
        assertTrue(m.sources.isEmpty()); assertEquals(1,m.invocations.size());
        assertEquals("g/library/2/library-2.pom",cache.relativize(ProvenanceCollector.cachedPom(cache,new Coordinate("g","library","2"))).toString().replace('\\','/'));
    }
    @Test void copiesTheExactPomFromACustomCacheWithoutInvokingMaven() throws Exception {
        Path cache = root.resolve("custom user repository"), report = root.resolve("report"), evidence = report.resolve("evidence");
        Files.createDirectories(evidence);
        for (String version : List.of("1","2","3")) {
            Path pom = cache.resolve("g/library/" + version + "/library-" + version + ".pom"); Files.createDirectories(pom.getParent());
            Files.writeString(pom,"<project><groupId>g</groupId><artifactId>library</artifactId><version>" + version + "</version></project>");
        }
        var m = new Model.Module(); m.effective = new Pom(new Coordinate("g","app","1"),"jar",null,List.of(),List.of(),List.of(),List.of(),List.of());
        m.dependencies.add(new ResolvedDependency(new Coordinate("g","library","2"),"jar","","compile",false,List.of("g:app:1","g:library:2")));
        new PomGraphCollector().collect(m,new Configuration(null,root).forRepository(root),evidence,report,cache,false);
        assertTrue(m.invocations.isEmpty()); assertTrue(m.issues.isEmpty());
        assertEquals(1,m.sources.size()); assertEquals("g:library:2",m.sources.getFirst().coordinate().gav());
        assertEquals("ARTIFACT_CACHE",m.sources.getFirst().kind());
        assertArrayEquals(Files.readAllBytes(cache.resolve("g/library/2/library-2.pom")),Files.readAllBytes(report.resolve(m.sources.getFirst().evidence())));
    }
}
