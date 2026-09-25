package io.github.mavenlegacy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import java.nio.file.*;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

class ReportNamingTest {
    @TempDir Path root;

    @Test void singleProjectNameIsVisibleBeforeExpandingModules() throws Exception {
        Path project = root.resolve("demo-project");
        for (String name : new String[]{"pom.xml", "common/pom.xml", "ear/pom.xml"}) {
            Path pom = project.resolve(name);
            Files.createDirectories(pom.getParent());
            Files.writeString(pom, "<project><groupId>com.example</groupId><artifactId>sample</artifactId><version>1.0</version></project>");
        }
        Path output = root.resolve("report");
        assertEquals(0, new CommandLine(new Main()).execute("scan", project.toString(), "--inventory-only", "--output", output.toString()));
        String html = Files.readString(output.resolve("index.html"));
        var summaries = Pattern.compile("<summary>(.*?)</summary>", Pattern.DOTALL).matcher(html)
                .results().map(m -> m.group(1)).toList();
        assertEquals(3, summaries.size());
        assertTrue(summaries.stream().allMatch(s -> s.contains("demo-project/") && s.contains("com.example:sample:1.0")));
        assertTrue(summaries.stream().anyMatch(s -> s.contains("demo-project/common/pom.xml")));
        assertTrue(summaries.stream().anyMatch(s -> s.contains("demo-project/ear/pom.xml")));
        var json = new ObjectMapper().readTree(output.resolve("analysis.json").toFile());
        assertEquals("demo-project", json.path("modules").get(0).path("projectName").asText());
        assertEquals("common/pom.xml", json.path("modules").get(0).path("modulePath").asText());
        assertTrue(Files.readString(project.resolve("common/pom.xml")).contains("<artifactId>sample</artifactId>"));
    }
}
