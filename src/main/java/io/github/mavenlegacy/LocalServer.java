package io.github.mavenlegacy;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Serves registered report directories only, on IPv4 loopback, with no scan or mutation endpoint. */
public final class LocalServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final List<Path> directories;
    private final List<Map<String, Object>> catalog = new ArrayList<>();

    public LocalServer(List<Path> reports, int port) throws Exception {
        directories = new ArrayList<>();
        for (Path path : reports) {
            try {
                Path dir = path.toRealPath();
                var report = SavedReports.read(dir);
                new SavedReports().render(dir, null);
                int id = directories.size(); directories.add(dir);
                catalog.add(Map.of("id", id, "name", dir.getFileName().toString(), "date", report.generatedAt(), "url", "/reports/" + id + "/index.html", "data", "/reports/" + id + "/explorer/data.json"));
            } catch (Exception ex) { System.err.println("Rapport indisponible : " + path + " : " + ex.getMessage()); }
        }
        if (directories.isEmpty()) throw new IllegalArgumentException("Aucune analyse disponible. Utiliser scan ou report pour en enregistrer une.");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            try {
                String host = exchange.getRequestHeaders().getFirst("Host");
                if (!Set.of("127.0.0.1:" + port(), "localhost:" + port()).contains(Objects.toString(host, ""))) { exchange.sendResponseHeaders(403, -1); return; }
                if (!Set.of("GET", "HEAD").contains(exchange.getRequestMethod())) { exchange.sendResponseHeaders(405, -1); return; }
                var headers = exchange.getResponseHeaders();
                headers.set("X-Content-Type-Options", "nosniff"); headers.set("Referrer-Policy", "no-referrer");
                headers.set("Cache-Control", "no-store");
                String path = exchange.getRequestURI().getPath();
                if (path.equals("/")) {
                    var latest = catalog.stream().max(Comparator.comparing(c -> c.get("date").toString())).orElseThrow();
                    headers.set("Location", latest.get("url").toString()); exchange.sendResponseHeaders(302, -1); return;
                }
                byte[] bytes;
                if (path.equals("/api/reports")) {
                    headers.set("Content-Type", "application/json; charset=utf-8");
                    bytes = ExplorerWriter.safeJson(catalog).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                } else {
                    String[] parts = path.split("/", 4);
                    if (parts.length != 4 || !parts[1].equals("reports")) { exchange.sendResponseHeaders(404, -1); return; }
                    int id = Integer.parseInt(parts[2]);
                    if (id < 0 || id >= directories.size()) { exchange.sendResponseHeaders(404, -1); return; }
                    Path dir = directories.get(id), file = ProvenanceWriter.safeResolve(dir, parts[3]);
                    if (!Files.isRegularFile(file) || !file.toRealPath().startsWith(dir)) { exchange.sendResponseHeaders(404, -1); return; }
                    String name = file.getFileName().toString();
                    String type = name.endsWith(".html") ? "text/html" : name.endsWith(".js") ? "text/javascript" : name.endsWith(".css") ? "text/css" : name.endsWith(".json") ? "application/json" : "text/plain";
                    headers.set("Content-Type", type + "; charset=utf-8"); bytes = Files.readAllBytes(file);
                }
                if (exchange.getRequestMethod().equals("HEAD")) { headers.set("Content-Length", Integer.toString(bytes.length)); exchange.sendResponseHeaders(200, -1); }
                else { exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); }
            } catch (Exception ex) { exchange.sendResponseHeaders(404, -1); }
            finally { exchange.close(); }
        });
    }
    public int port() { return server.getAddress().getPort(); }
    public void start() { server.start(); }
    public void close() { server.stop(0); executor.close(); }
}
