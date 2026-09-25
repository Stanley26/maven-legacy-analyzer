import java.nio.file.*;
public class WriteLabConfig {
    public static void main(String[] args) throws Exception {
        String path = args[0].replace("\\", "\\\\").replace("\"", "\\\"");
        Files.writeString(Path.of(args[1]), "{\"defaults\":{\"timeoutSeconds\":300,\"alignmentGroups\":[\"com.example.components\"],\"properties\":{\"maven.repo.local\":\"" + path + "\"}}}");
    }
}
