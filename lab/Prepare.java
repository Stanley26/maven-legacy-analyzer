import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.jar.*;

/** Synthetic fixtures only. Run from the repository root: java lab/Prepare.java <output-directory>. */
public class Prepare {
    static Path root, repository;
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: java lab/Prepare.java <new-directory>");
        root = Path.of(args[0]).toAbsolutePath().normalize();
        if (Files.exists(root)) try (var paths = Files.list(root)) {
            if (paths.findAny().isPresent()) throw new IllegalArgumentException("Laboratory output must be new or empty");
        }
        repository = root.resolve("published-repository");
        Files.createDirectories(repository);
        publish("com.example.components", "component-core", "1.0.0", "<packaging>jar</packaging>");
        publish("com.example.components", "component-core", "2.0.0", "<packaging>jar</packaging>");
        publish("com.example.components", "component-context", "1.0.0", "<packaging>jar</packaging><dependencies>" + dep("com.example.components", "component-core", "1.0.0", "") + "</dependencies>");
        publish("com.example.components", "component-context", "2.0.0", "<packaging>jar</packaging><dependencies>" + dep("com.example.components", "component-core", "2.0.0", "") + "</dependencies>");
        publish("com.example.platform", "platform-core", "1.0.0", "<dependencies>" + dep("com.example.components", "component-context", "1.0.0", "") + "</dependencies>");
        publish("com.example.platform", "platform-core", "2.0.0", "<dependencies>" + dep("com.example.components", "component-context", "1.0.0", "") + "</dependencies>");
        publish("com.example.shared", "shared-utils", "5.4", "<dependencies>" + dep("com.example.platform", "platform-core", "1.0.0", "") + "</dependencies>");
        publish("com.example.server", "server-api", "1.0", "<packaging>jar</packaging>");
        publish("com.example.parents", "base-parent", "12", """
            <packaging>pom</packaging><properties><component.version>1.0.0</component.version></properties>
            <dependencyManagement><dependencies><dependency><groupId>com.example.components</groupId><artifactId>component-core</artifactId><version>${component.version}</version></dependency></dependencies></dependencyManagement>
            """);
        publish("com.example.parents", "application-parent", "7", parent("com.example.parents", "base-parent", "12") + "<packaging>pom</packaging>");
        publish("com.example.platform", "platform-bom", "3", "<packaging>pom</packaging><properties><server.version>1.0</server.version></properties><dependencyManagement><dependencies>"
                + dep("com.example.server", "server-api", "${server.version}", "provided") + "</dependencies></dependencyManagement>");
        publish("com.example.parents", "bom-parent", "2", "<packaging>pom</packaging><properties><platform-bom.group>com.example.platform</platform-bom.group><platform-bom.artifact>platform-bom</platform-bom.artifact><platform-bom.version>3</platform-bom.version></properties>");
        publish("com.example.platform", "nested-bom", "2", parent("com.example.parents", "bom-parent", "2")
                + "<packaging>pom</packaging><dependencyManagement><dependencies><dependency><groupId>${platform-bom.group}</groupId><artifactId>${platform-bom.artifact}</artifactId><version>${platform-bom.version}</version><type>pom</type><scope>import</scope></dependency></dependencies></dependencyManagement>");
        publish("com.example.platform", "catalog-bom", "1", "<packaging>pom</packaging>");

        String settings = """
            <settings xmlns="http://maven.apache.org/SETTINGS/1.2.0">
              <profiles><profile><id>synthetic-lab</id><repositories><repository><id>synthetic</id><url>%s</url><releases><checksumPolicy>fail</checksumPolicy></releases></repository></repositories></profile></profiles>
              <activeProfiles><activeProfile>synthetic-lab</activeProfile></activeProfiles>
            </settings>
            """.formatted(repository.toUri());
        for (String name : List.of("application-a", "application-b", "shared-utils")) {
            Path project = root.resolve("projects").resolve(name);
            Files.createDirectories(project.resolve(".mvn/wrapper"));
            for (String file : List.of("mvnw", "mvnw.cmd", ".mvn/wrapper/maven-wrapper.properties"))
                Files.copy(Path.of(file), project.resolve(file));
            Files.writeString(project.resolve("settings.xml"), settings);
            Files.writeString(project.resolve(".mvn/maven.config"), "--settings\nsettings.xml\n");
        }
        write("projects/application-a/pom.xml", project("com.example.apps", "application-a", "1.0", "<packaging>pom</packaging><modules><module>app/deep/webapp</module></modules>"));
        write("projects/application-a/app/deep/webapp/pom.xml", project("com.example.apps", "webapp-a", "1.0",
                parent("com.example.parents", "application-parent", "7") + """
                <properties><component.version>2.0.0</component.version></properties>
                <profiles><profile><id>default-marker</id><activation><activeByDefault>true</activeByDefault></activation><properties><lab.marker>active</lab.marker></properties></profile>
                <profile><id>inactive-marker</id><properties><component.version>3.0.0</component.version></properties></profile></profiles>
                <dependencies>
                """ + dep("com.example.shared", "shared-utils", "5.4", "") + dep("com.example.components", "component-core", "", "") + "</dependencies>"));
        write("projects/application-a/unlisted/very/deep/module/pom.xml", project("com.example.apps", "unlisted", "1.0",
                "<properties><server.version>9.0</server.version></properties><dependencyManagement><dependencies><dependency><groupId>com.example.platform</groupId><artifactId>nested-bom</artifactId><version>2</version><type>pom</type><scope>import</scope></dependency></dependencies></dependencyManagement><dependencies>"
                        + dep("com.example.server", "server-api", "", "provided") + "</dependencies>"
                        + "<profiles><profile><id>catalog-only</id><dependencyManagement><dependencies><dependency><groupId>com.example.platform</groupId><artifactId>catalog-bom</artifactId><version>1</version><type>pom</type><scope>import</scope></dependency></dependencies></dependencyManagement></profile></profiles>"));
        write("projects/application-b/pom.xml", project("com.example.apps", "application-b", "1.0", parent("com.example.parents", "base-parent", "12")
                + "<dependencies>" + dep("com.example.shared", "shared-utils", "5.4", "") + "</dependencies>"));
        write("projects/shared-utils/pom.xml", project("com.example.shared", "shared-utils", "5.4",
                "<dependencies>" + dep("com.example.platform", "platform-core", "1.0.0", "") + "</dependencies>"));
        System.out.println("Synthetic projects: " + root.resolve("projects"));
        System.out.println("All published JARs are EMPTY test doubles; they are not the real component binaries.");
    }
    static String project(String group, String artifact, String version, String content) {
        return "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"><modelVersion>4.0.0</modelVersion><groupId>" + group
                + "</groupId><artifactId>" + artifact + "</artifactId><version>" + version + "</version>" + content + "</project>";
    }
    static String parent(String group, String artifact, String version) {
        return "<parent><groupId>" + group + "</groupId><artifactId>" + artifact + "</artifactId><version>" + version + "</version><relativePath/></parent>";
    }
    static String dep(String group, String artifact, String version, String scope) {
        return "<dependency><groupId>" + group + "</groupId><artifactId>" + artifact + "</artifactId>"
                + (version.isEmpty() ? "" : "<version>" + version + "</version>") + (scope.isEmpty() ? "" : "<scope>" + scope + "</scope>") + "</dependency>";
    }
    static void write(String relative, String text) throws Exception {
        Path file = root.resolve(relative); Files.createDirectories(file.getParent()); Files.writeString(file, text);
    }
    static void publish(String group, String artifact, String version, String content) throws Exception {
        Path dir = repository.resolve(group.replace('.', '/')).resolve(artifact).resolve(version);
        Files.createDirectories(dir);
        Path pom = dir.resolve(artifact + "-" + version + ".pom");
        Files.writeString(pom, project(group, artifact, version, content)); checksum(pom);
        if (!content.contains("<packaging>pom</packaging>")) {
            Path jar = dir.resolve(artifact + "-" + version + ".jar");
            try (var stream = new JarOutputStream(Files.newOutputStream(jar))) {
                stream.putNextEntry(new JarEntry("SYNTHETIC-FIXTURE.txt"));
                stream.write("Empty synthetic fixture. Not a real library.".getBytes(StandardCharsets.UTF_8));
                stream.closeEntry();
            }
            checksum(jar);
        }
    }
    static void checksum(Path file) throws Exception {
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(file)));
        Files.writeString(Path.of(file + ".sha1"), hash);
    }
}
