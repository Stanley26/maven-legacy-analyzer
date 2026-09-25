package io.github.mavenlegacy;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static io.github.mavenlegacy.Model.*;

public final class MavenRunner {
    public Invocation run(Path wrapper, Path pom, Path repository, Configuration.Options options,
                          String goal, List<String> parameters, Path log) throws Exception {
        var args = new ArrayList<String>(List.of("-B", "-N", "-f", pom.toString()));
        addSettings(args, "--settings", options.settings(), repository);
        addSettings(args, "--global-settings", options.globalSettings(), repository);
        if (!options.profiles().isEmpty()) args.add("-P" + String.join(",", options.profiles()));
        options.properties().forEach((k, v) -> {
            if (!k.matches("[A-Za-z0-9_.-]+")) throw new IllegalArgumentException("Invalid Maven property name");
            args.add("-D" + k + "=" + v);
        });
        args.add(goal);
        args.addAll(parameters);
        var command = new ArrayList<String>();
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        if (windows) {
            command.addAll(List.of("cmd.exe", "/d", "/v:off", "/s", "/c"));
            var tokens = new ArrayList<String>();
            tokens.add(quoteWindows(wrapper.toString()));
            args.forEach(a -> tokens.add(quoteWindows(a)));
            command.add("\"" + String.join(" ", tokens) + "\"");
        } else {
            command.add("/bin/sh");
            command.add(wrapper.toString());
            command.addAll(args);
        }
        var builder = new ProcessBuilder(command).directory(wrapper.getParent().toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile());
        if (!options.javaHome().isBlank()) {
            Path javaHome = resolve(repository, options.javaHome());
            if (!Files.isDirectory(javaHome.resolve("bin"))) throw new IllegalArgumentException("Invalid javaHome directory");
            builder.environment().put("JAVA_HOME", javaHome.toString());
            String pathKey = builder.environment().keySet().stream().filter(k -> k.equalsIgnoreCase("PATH")).findFirst().orElse("PATH");
            builder.environment().put(pathKey, javaHome.resolve("bin") + java.io.File.pathSeparator + builder.environment().getOrDefault(pathKey, ""));
        }
        var start = Instant.now();
        var process = builder.start();
        boolean completed;
        try { completed = process.waitFor(options.timeoutSeconds(), TimeUnit.SECONDS); }
        catch (InterruptedException ex) { stop(process); Thread.currentThread().interrupt(); throw ex; }
        if (!completed) stop(process);
        return new Invocation(goal, completed ? process.exitValue() : -1, !completed,
                log.getFileName().toString(), Duration.between(start, Instant.now()).toMillis());
    }
    private static void stop(Process process) {
        var children = process.descendants().toList();
        children.reversed().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try { process.waitFor(5, TimeUnit.SECONDS); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
    }
    static String quoteWindows(String value) {
        // Reject cmd expansion/control syntax instead of interpreting user-controlled POM paths or options.
        if (value.chars().anyMatch(c -> "\"%\r\n&|<>^!".indexOf(c) >= 0))
            throw new IllegalArgumentException("Unsupported Windows command character in argument: use a path/value without shell metacharacters");
        return "\"" + value + "\"";
    }
    private static void addSettings(List<String> args, String option, String value, Path repository) {
        if (value.isBlank()) return;
        Path path = resolve(repository, value);
        if (!Files.isRegularFile(path)) throw new IllegalArgumentException("Settings file does not exist: " + path);
        args.add(option); args.add(path.toString());
    }
    private static Path resolve(Path repository, String value) {
        Path path = Path.of(value);
        return (path.isAbsolute() ? path : repository.resolve(path)).normalize();
    }
}
