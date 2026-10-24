package com.procpilot.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigLoaderTest {

    @TempDir
    Path dir;

    private ConfigLoader loader;

    @BeforeEach
    void setUp() {
        loader = new ConfigLoader(new ConfigValidator(Map.of("PATH", "/usr/bin:/bin")));
    }

    private Path write(String yaml) throws IOException {
        Path f = dir.resolve("procpilot.yaml");
        Files.writeString(f, yaml);
        return f;
    }

    private String settings() {
        return "settings:\n  logDir: logs\n  runtimeDir: run\n";
    }

    private List<String> errorsOf(String yaml) throws IOException {
        Path f = write(yaml);
        ConfigException e = assertThrows(ConfigException.class, () -> loader.load(f));
        return e.errors();
    }

    private static boolean anyContains(List<String> errors, String fragment) {
        return errors.stream().anyMatch(s -> s.contains(fragment));
    }

    @Test
    void loadsFullServiceDefinition() throws Exception {
        Path f = write(settings() + """
                services:
                  web:
                    command: ["sleep", "100"]
                    workingDir: .
                    env:
                      PORT: 8080
                      DEBUG: true
                      API_TOKEN: s3cret
                    secretEnv: [DB_URL]
                    restart:
                      policy: on-failure
                      maxAttempts: 3
                      initialDelay: 200ms
                      maxDelay: 5s
                      multiplier: 1.5
                      resetAfter: 30s
                    stopTimeout: 3s
                    healthCheck:
                      http: http://127.0.0.1:8080/health
                      interval: 5s
                      timeout: 1s
                      failureThreshold: 2
                    sampleInterval: 1s
                    logRotation:
                      maxSize: 1MB
                      keep: 2
                """);
        ProcPilotConfig cfg = loader.load(f);
        ServiceConfig web = cfg.service("web");
        assertEquals("/usr/bin/sleep", web.executable());
        assertEquals(List.of("/usr/bin/sleep", "100"), web.command());
        assertEquals(dir, web.workingDir());
        assertEquals("8080", web.env().get("PORT"));
        assertEquals("true", web.env().get("DEBUG"));
        assertTrue(web.secretEnv().contains("DB_URL"));
        assertEquals(RestartPolicyType.ON_FAILURE, web.restart().policy());
        assertEquals(3, web.restart().maxAttempts());
        assertEquals(Duration.ofMillis(200), web.restart().initialDelay());
        assertEquals(1.5, web.restart().multiplier());
        assertEquals(Duration.ofSeconds(3), web.stopTimeout());
        assertTrue(web.healthCheck().isHttp());
        assertEquals(2, web.healthCheck().failureThreshold());
        assertEquals(Duration.ofSeconds(1), web.sampleInterval());
        assertEquals(1024 * 1024, web.logRotation().maxBytes());
        assertEquals(dir.resolve("logs/web.out.log"), web.stdoutLog());
        assertEquals(dir.resolve("logs/web.err.log"), web.stderrLog());
        assertEquals(dir.resolve("run/procpilot.sock"), cfg.socketPath());
    }

    @Test
    void appliesDefaults() throws Exception {
        ProcPilotConfig cfg = loader.load(write(settings() + "services:\n  a:\n    command: [\"/bin/true\"]\n"));
        ServiceConfig a = cfg.service("a");
        assertEquals(RestartConfig.defaults(), a.restart());
        assertEquals(Duration.ofSeconds(10), a.stopTimeout());
        assertNull(a.healthCheck());
        assertNull(a.sampleInterval());
        assertEquals(RotationConfig.defaults(), a.logRotation());
    }

    @Test
    void preservesServiceOrder() throws Exception {
        ProcPilotConfig cfg = loader.load(write(settings() + """
                services:
                  zeta: {command: ["/bin/true"]}
                  alpha: {command: ["/bin/true"]}
                  mid: {command: ["/bin/true"]}
                """));
        assertEquals(List.of("zeta", "alpha", "mid"), List.copyOf(cfg.services().keySet()));
    }

    @Test
    void rejectsDuplicateServiceNames() throws IOException {
        List<String> errors = errorsOf(settings() + """
                services:
                  a: {command: ["/bin/true"]}
                  a: {command: ["/bin/false"]}
                """);
        assertTrue(anyContains(errors, "duplicate key"), errors.toString());
    }

    @Test
    void rejectsCommandAsShellString() throws IOException {
        List<String> errors = errorsOf(settings() + "services:\n  a:\n    command: \"echo hi; rm -rf /\"\n");
        assertTrue(anyContains(errors, "must be a list"), errors.toString());
    }

    @Test
    void collectsMultipleErrorsAtOnce() throws IOException {
        List<String> errors = errorsOf(settings() + """
                services:
                  Bad_Name:
                    command: ["/bin/true"]
                  b:
                    command: ["/no/such/binary"]
                    restart:
                      policy: sometimes
                      maxAttempts: -1
                      initialDelay: 10s
                      maxDelay: 1s
                    stopTimeout: forever
                    unknownThing: 1
                    env:
                      "1BAD": x
                """);
        assertTrue(anyContains(errors, "invalid service name"), errors.toString());
        assertTrue(anyContains(errors, "does not exist"), errors.toString());
        assertTrue(anyContains(errors, "unknown restart policy"), errors.toString());
        assertTrue(anyContains(errors, "maxAttempts: -1 is out of range"), errors.toString());
        assertTrue(anyContains(errors, "maxDelay: must be >= initialDelay"), errors.toString());
        assertTrue(anyContains(errors, "invalid duration 'forever'"), errors.toString());
        assertTrue(anyContains(errors, "unknown key 'unknownThing'"), errors.toString());
        assertTrue(anyContains(errors, "invalid variable name '1BAD'"), errors.toString());
    }

    @Test
    void rejectsNonExecutableAndWorldWritableFiles() throws IOException {
        Path notExec = dir.resolve("plain.sh");
        Files.writeString(notExec, "#!/bin/sh\n");
        Path ww = dir.resolve("ww.sh");
        Files.writeString(ww, "#!/bin/sh\n");
        Files.setPosixFilePermissions(ww, PosixFilePermissions.fromString("rwxrwxrwx"));
        List<String> errors = errorsOf(settings() + """
                services:
                  a: {command: ["./plain.sh"]}
                  b: {command: ["./ww.sh"]}
                """);
        assertTrue(anyContains(errors, "is not executable"), errors.toString());
        assertTrue(anyContains(errors, "world-writable"), errors.toString());
    }

    @Test
    void resolvesRelativeExecutableAgainstConfigDir() throws Exception {
        Path script = dir.resolve("bin/run.sh");
        Files.createDirectories(script.getParent());
        Files.writeString(script, "#!/bin/sh\nexit 0\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-x---"));
        ProcPilotConfig cfg = loader.load(write(settings() + "services:\n  a: {command: [\"bin/run.sh\"]}\n"));
        assertEquals(script.toString(), cfg.service("a").executable());
    }

    @Test
    void warnsAboutShellDashC() throws Exception {
        ProcPilotConfig cfg = loader.load(write(settings()
                + "services:\n  a: {command: [\"/bin/sh\", \"-c\", \"echo hi\"]}\n"));
        assertTrue(anyContains(cfg.warnings(), "runs a shell with -c"), cfg.warnings().toString());
    }

    @Test
    void rejectsAmbiguousHealthCheck() throws IOException {
        List<String> errors = errorsOf(settings() + """
                services:
                  a:
                    command: ["/bin/true"]
                    healthCheck:
                      http: http://localhost/
                      command: ["/bin/true"]
                  b:
                    command: ["/bin/true"]
                    healthCheck:
                      http: ftp://localhost/
                  c:
                    command: ["/bin/true"]
                    healthCheck:
                      command: ["/bin/true"]
                      interval: 1s
                      timeout: 5s
                """);
        assertTrue(anyContains(errors, "exactly one of 'http' or 'command'"), errors.toString());
        assertTrue(anyContains(errors, "http:// or https://"), errors.toString());
        assertTrue(anyContains(errors, "timeout: must not exceed interval"), errors.toString());
    }

    @Test
    void rejectsSharedLogFiles() throws IOException {
        List<String> errors = errorsOf(settings() + """
                services:
                  a: {command: ["/bin/true"], stdout: shared.log}
                  b: {command: ["/bin/true"], stdout: shared.log}
                """);
        assertTrue(anyContains(errors, "already used by service 'a'"), errors.toString());
    }

    @Test
    void allowsCombinedStdoutStderrForOneService() throws Exception {
        ProcPilotConfig cfg = loader.load(write(settings()
                + "services:\n  a: {command: [\"/bin/true\"], stdout: a.log, stderr: a.log}\n"));
        assertEquals(cfg.service("a").stdoutLog(), cfg.service("a").stderrLog());
    }

    @Test
    void reportsMalformedYaml() throws IOException {
        List<String> errors = errorsOf("services:\n  a: [unclosed\n");
        assertTrue(anyContains(errors, "malformed YAML"), errors.toString());
    }

    @Test
    void rejectsArbitraryJavaTypeTags() throws IOException {
        List<String> errors = errorsOf("services: !!javax.script.ScriptEngineManager [x]\n");
        assertTrue(anyContains(errors, "malformed YAML"), errors.toString());
    }

    @Test
    void reportsMissingAndEmptyFiles() throws IOException {
        ConfigException missing = assertThrows(ConfigException.class, () -> loader.load(dir.resolve("nope.yaml")));
        assertTrue(missing.getMessage().contains("not found"));
        List<String> errors = errorsOf("");
        assertTrue(anyContains(errors, "is empty"), errors.toString());
        errors = errorsOf("settings: {}\n");
        assertTrue(anyContains(errors, "at least one service"), errors.toString());
    }

    @Test
    void resolvesBareNamesOnlyFromAbsolutePathEntries() throws Exception {
        Path local = dir.resolve("mytool");
        Files.writeString(local, "#!/bin/sh\n");
        Files.setPosixFilePermissions(local, PosixFilePermissions.fromString("rwx------"));
        ConfigLoader relPath = new ConfigLoader(new ConfigValidator(Map.of("PATH", ".:" + "relative/dir")));
        Path f = write(settings() + "services:\n  a: {command: [\"mytool\"]}\n");
        ConfigException e = assertThrows(ConfigException.class, () -> relPath.load(f));
        assertTrue(anyContains(e.errors(), "not found on PATH"), e.errors().toString());
    }
}
