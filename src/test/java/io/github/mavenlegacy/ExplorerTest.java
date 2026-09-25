package io.github.mavenlegacy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.mavenlegacy.Model.*;

class ExplorerTest {
    @TempDir Path root;
    private Report sample() {
        var a = new Model.Module(); a.id = "app/pom.xml"; a.projectName = "app"; a.modulePath = "pom.xml"; a.repository = "/missing/app"; a.status = "RESOLVED";
        var b = new Model.Module(); b.id = "app/web/pom.xml"; b.projectName = "app"; b.modulePath = "web/pom.xml"; b.repository = a.repository; b.status = "RESOLVED";
        var c = new Model.Module(); c.id = "lib/pom.xml"; c.projectName = "lib"; c.modulePath = "pom.xml"; c.repository = "/missing/lib";
        c.declared = new Pom(new Coordinate("g","lib","1"),"jar",null,List.of(),List.of(),List.of(),List.of(),List.of());
        var dep = new ResolvedDependency(new Coordinate("g","lib","1"),"jar","","provided",false,List.of("g:app:1","g:lib:1"));
        a.dependencies.add(dep); b.dependencies.add(dep);
        a.explanations.add(new VersionExplanation(dep,"SOURCE_LINKED",List.of(new Fact("PROPERTY_OVERRIDE","</script><b>text</b>",null,0)),List.of()));
        return new Report("0.2","2026-01-01T00:00:00Z","/missing","MAVEN",List.of(a,b,c),List.of(),List.of(),Map.of());
    }
    @Test void compactIndexPreservesProjectIdentityPathsScopesAndDocumentedOverrides() throws Exception {
        new ExplorerWriter().write(sample(),root);
        var json = SavedReports.MAPPER.readTree(root.resolve("explorer/data.json").toFile());
        assertEquals(2,json.path("projects").size());
        assertEquals(3,json.path("modules").size());
        assertEquals(1,json.path("coordinates").size());
        assertTrue(json.path("coordinates").get(0).path("internal").asBoolean());
        assertEquals("app/web/pom.xml",json.path("modules").get(1).path("label").asText());
        assertEquals("provided",json.path("uses").get(0).get(2).asText());
        assertEquals(2,json.path("uses").get(0).get(3).size());
        assertTrue(json.path("uses").get(0).get(4).asBoolean());
        assertFalse(json.path("uses").get(1).get(4).asBoolean());
        String lazy = Files.readString(root.resolve("explorer/module-0.js"));
        assertFalse(lazy.contains("</script>"));
        assertTrue(lazy.contains("\\u003c/script\\u003e"));
        assertFalse(Files.readString(root.resolve("explorer/data.js")).contains("PROPERTY_OVERRIDE"));
    }
    @Test void localServerRegeneratesWithoutMavenAndRestrictsReadsToReports() throws Exception {
        Path report = root.resolve("report"); Files.createDirectories(report);
        SavedReports.MAPPER.writeValue(report.resolve("analysis.json").toFile(),sample());
        byte[] original = Files.readAllBytes(report.resolve("analysis.json"));
        Files.writeString(root.resolve("outside.txt"),"outside");
        try (var server = new LocalServer(List.of(report),0); var client = HttpClient.newHttpClient()) {
            server.start();
            String base = "http://127.0.0.1:" + server.port();
            var home = client.send(HttpRequest.newBuilder(URI.create(base + "/")).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(302,home.statusCode());
            assertEquals("/reports/0/index.html",home.headers().firstValue("Location").orElseThrow());
            var page = client.send(HttpRequest.newBuilder(URI.create(base + "/reports/0/index.html")).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,page.statusCode()); assertTrue(page.body().contains("Vue du parc"));
            assertEquals("nosniff",page.headers().firstValue("X-Content-Type-Options").orElseThrow());
            var catalog = client.send(HttpRequest.newBuilder(URI.create(base + "/api/reports")).build(),HttpResponse.BodyHandlers.ofString());
            assertTrue(catalog.body().contains("explorer/data.json"));
            for (String path : List.of("/reports/0/../outside.txt","/reports/0/%2e%2e/outside.txt","/reports/99/analysis.json","/outside.txt"))
                assertEquals(404,client.send(HttpRequest.newBuilder(URI.create(base + path)).build(),HttpResponse.BodyHandlers.discarding()).statusCode());
            assertEquals(405,client.send(HttpRequest.newBuilder(URI.create(base + "/api/reports")).POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.discarding()).statusCode());
            try (var socket = new Socket("127.0.0.1",server.port())) {
                socket.getOutputStream().write("GET /api/reports HTTP/1.1\r\nHost: attacker.invalid\r\nConnection: close\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                assertTrue(new String(socket.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).startsWith("HTTP/1.1 403"));
            }
        }
        assertArrayEquals(original,Files.readAllBytes(report.resolve("analysis.json")));
    }
}
