package com.procpilot.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MainTest {

    @TempDir
    Path dir;

    private final ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errBuf = new ByteArrayOutputStream();

    private int run(String... args) {
        return new Main().run(args, new PrintStream(outBuf, true, StandardCharsets.UTF_8),
                new PrintStream(errBuf, true, StandardCharsets.UTF_8));
    }

    private String out() {
        return outBuf.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return errBuf.toString(StandardCharsets.UTF_8);
    }

    @Test
    void helpListsCommands() {
        assertEquals(ExitCodes.OK, run("help"));
        assertTrue(out().contains("validate-config"));
        assertTrue(out().contains("Exit codes"));
    }

    @Test
    void noArgumentsIsUsageError() {
        assertEquals(ExitCodes.USAGE, run());
        assertTrue(err().contains("Usage: procpilot"));
    }

    @Test
    void unknownCommandIsUsageError() {
        assertEquals(ExitCodes.USAGE, run("frobnicate"));
        assertTrue(err().contains("unknown command 'frobnicate'"));
    }

    @Test
    void versionPrintsVersion() {
        assertEquals(ExitCodes.OK, run("version"));
        assertTrue(out().startsWith("procpilot "));
    }

    @Test
    void validateConfigReportsErrorsWithConfigExitCode() throws Exception {
        Path f = dir.resolve("bad.yaml");
        Files.writeString(f, "services:\n  a:\n    command: [\"/does/not/exist\"]\n");
        assertEquals(ExitCodes.CONFIG, run("--config", f.toString(), "validate-config"));
        assertTrue(err().contains("/does/not/exist does not exist"), err());
    }

    @Test
    void validateConfigSucceedsAndHidesEnvValues() throws Exception {
        Path f = dir.resolve("ok.yaml");
        Files.writeString(f, """
                settings: {logDir: logs, runtimeDir: run}
                services:
                  a:
                    command: ["/bin/sleep", "5"]
                    env: {API_TOKEN: topsecret, PORT: 1}
                """);
        assertEquals(ExitCodes.OK, run("validate-config", "--config", f.toString()));
        assertTrue(out().contains("Configuration OK"));
        assertFalse(out().contains("topsecret"));
        outBuf.reset();
        assertEquals(ExitCodes.OK, run("validate-config", "--verbose", "--config=" + f));
        assertFalse(out().contains("topsecret"), "secrets must be masked even in verbose mode");
        assertTrue(out().contains("PORT=1"));
    }

    @Test
    void parserHandlesOptionsAndPositionals() throws Exception {
        CommandLine cl = CommandLine.parse(new String[]{"logs", "web", "-n", "20", "--stderr", "--", "--odd"},
                Set.of("n"));
        assertEquals("logs", cl.command());
        assertEquals(20, cl.intOption("n", 50, 1, 100));
        assertTrue(cl.flag("stderr"));
        assertEquals(java.util.List.of("web", "--odd"), cl.positionals());
        assertThrows(CommandLine.UsageException.class,
                () -> CommandLine.parse(new String[]{"logs", "-n"}, Set.of("n")));
        CommandLine bad = CommandLine.parse(new String[]{"logs", "-n", "x"}, Set.of("n"));
        assertThrows(CommandLine.UsageException.class, () -> bad.intOption("n", 1, 1, 10));
    }
}
