package com.procpilot.config;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import com.procpilot.util.AppPaths;
import com.procpilot.util.Durations;
import com.procpilot.util.Sizes;

/**
 * Converts the raw YAML tree into validated {@link ProcPilotConfig} records.
 *
 * <p>All problems are collected and reported together so the user can fix a file in one pass.
 * Relative paths are resolved against the directory containing the configuration file.
 * Unknown keys are errors: a typo such as {@code restart: {polcy: always}} must not silently
 * fall back to a default.
 */
public final class ConfigValidator {

    static final Pattern SERVICE_NAME = Pattern.compile("^[a-z0-9][a-z0-9_-]{0,62}$");
    static final Pattern ENV_NAME = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");
    private static final Set<String> SHELLS = Set.of("sh", "bash", "dash", "zsh", "ksh", "fish");
    private static final Set<String> RISKY_ENV = Set.of("LD_PRELOAD", "LD_LIBRARY_PATH", "LD_AUDIT");

    private static final Set<String> TOP_KEYS = Set.of("settings", "services");
    private static final Set<String> SETTINGS_KEYS = Set.of("logDir", "runtimeDir", "sampleInterval", "eventsLog");
    private static final Set<String> SERVICE_KEYS = Set.of("command", "workingDir", "env", "secretEnv", "stdout",
            "stderr", "restart", "stopTimeout", "healthCheck", "sampleInterval", "logRotation");
    private static final Set<String> RESTART_KEYS = Set.of("policy", "maxAttempts", "initialDelay", "maxDelay",
            "multiplier", "resetAfter");
    private static final Set<String> HEALTH_KEYS = Set.of("http", "command", "interval", "timeout",
            "failureThreshold", "startPeriod");
    private static final Set<String> ROTATION_KEYS = Set.of("maxSize", "keep");

    private final Map<String, String> environment;

    public ConfigValidator() {
        this(System.getenv());
    }

    /** @param environment used only to look up {@code PATH} when resolving bare executable names */
    public ConfigValidator(Map<String, String> environment) {
        this.environment = Map.copyOf(environment);
    }

    public ProcPilotConfig validate(Map<String, Object> raw, Path configFile) throws ConfigException {
        Ctx ctx = new Ctx(configFile.getParent() == null ? Path.of("/") : configFile.getParent());
        ctx.unknownKeys(raw, TOP_KEYS, "top level");

        // ---- settings ----
        Map<String, Object> settings = ctx.map(raw.get("settings"), "settings");
        ctx.unknownKeys(settings, SETTINGS_KEYS, "settings");
        Path logDir = ctx.path(settings.get("logDir"), "settings.logDir")
                .orElse(AppPaths.defaultStateDir().resolve("logs"));
        Path runtimeDir = ctx.path(settings.get("runtimeDir"), "settings.runtimeDir")
                .orElse(AppPaths.defaultRuntimeDir());
        Duration sampleInterval = ctx.duration(settings.get("sampleInterval"), "settings.sampleInterval",
                Duration.ofSeconds(2), Duration.ofMillis(200), Duration.ofHours(1));
        RotationConfig eventsRotation = ctx.rotation(settings.get("eventsLog"), "settings.eventsLog");
        ctx.writableLocation(logDir, "settings.logDir");
        ctx.writableLocation(runtimeDir, "settings.runtimeDir");
        if (runtimeDir.toString().length() > 90) {
            // sun_path is 108 bytes on Linux; leave room for the socket file name.
            ctx.error("settings.runtimeDir: path is too long for a Unix domain socket (" + runtimeDir + ")");
        }

        // ---- services ----
        Object servicesRaw = raw.get("services");
        Map<String, ServiceConfig> services = new LinkedHashMap<>();
        if (servicesRaw == null) {
            ctx.error("services: at least one service must be defined");
        } else {
            Map<String, Object> servicesMap = ctx.map(servicesRaw, "services");
            if (servicesMap.isEmpty() && servicesRaw instanceof Map) {
                ctx.error("services: at least one service must be defined");
            }
            Map<Path, String> logOwners = new HashMap<>();
            for (Map.Entry<String, Object> e : servicesMap.entrySet()) {
                ServiceConfig sc = service(ctx, String.valueOf(e.getKey()), e.getValue(), logDir, logOwners);
                if (sc != null) {
                    services.put(sc.name(), sc);
                }
            }
        }

        if (!ctx.errors.isEmpty()) {
            throw new ConfigException(ctx.errors);
        }
        return new ProcPilotConfig(configFile, logDir, runtimeDir, eventsRotation, sampleInterval, services,
                ctx.warnings);
    }

    private ServiceConfig service(Ctx ctx, String name, Object rawSvc, Path logDir, Map<Path, String> logOwners) {
        String where = "services." + name;
        int errorsBefore = ctx.errors.size();
        if (!SERVICE_NAME.matcher(name).matches()) {
            ctx.error(where + ": invalid service name (use lowercase letters, digits, '-' or '_', max 63 chars,"
                    + " starting with a letter or digit)");
        }
        if (!(rawSvc instanceof Map)) {
            ctx.error(where + ": expected a mapping with at least a 'command' list");
            return null;
        }
        Map<String, Object> svc = ctx.map(rawSvc, where);
        ctx.unknownKeys(svc, SERVICE_KEYS, where);

        Path workingDir = ctx.path(svc.get("workingDir"), where + ".workingDir").orElse(ctx.baseDir);
        if (!Files.isDirectory(workingDir)) {
            ctx.error(where + ".workingDir: " + workingDir + " does not exist or is not a directory");
        }

        List<String> command = commandList(ctx, svc.get("command"), where + ".command", true);

        Map<String, String> env = new LinkedHashMap<>();
        Map<String, Object> envRaw = ctx.map(svc.get("env"), where + ".env");
        for (Map.Entry<String, Object> e : envRaw.entrySet()) {
            String key = String.valueOf(e.getKey());
            if (!ENV_NAME.matcher(key).matches()) {
                ctx.error(where + ".env: invalid variable name '" + key + "'");
                continue;
            }
            Object v = e.getValue();
            if (v instanceof Map || v instanceof List) {
                ctx.error(where + ".env." + key + ": value must be a scalar");
                continue;
            }
            String value = v == null ? "" : String.valueOf(v);
            if (value.indexOf('\0') >= 0) {
                ctx.error(where + ".env." + key + ": value contains a NUL character");
                continue;
            }
            if (RISKY_ENV.contains(key)) {
                ctx.warning(where + ".env." + key + ": overriding the dynamic loader is risky; make sure this is intended");
            }
            env.put(key, value);
        }

        Set<String> secretEnv = new LinkedHashSet<>();
        for (String s : ctx.stringList(svc.get("secretEnv"), where + ".secretEnv")) {
            if (!ENV_NAME.matcher(s).matches()) {
                ctx.error(where + ".secretEnv: invalid variable name '" + s + "'");
            } else {
                secretEnv.add(s);
            }
        }

        Path stdout = ctx.path(svc.get("stdout"), where + ".stdout").orElse(logDir.resolve(name + ".out.log"));
        Path stderr = ctx.path(svc.get("stderr"), where + ".stderr").orElse(logDir.resolve(name + ".err.log"));
        for (Path p : stdout.equals(stderr) ? List.of(stdout) : List.of(stdout, stderr)) {
            if (Files.isDirectory(p)) {
                ctx.error(where + ": log destination " + p + " is a directory");
            }
            ctx.writableLocation(p.getParent(), where + " log directory");
            String owner = logOwners.putIfAbsent(p, name);
            if (owner != null && !owner.equals(name)) {
                ctx.error(where + ": log file " + p + " is already used by service '" + owner + "'");
            }
        }

        RestartConfig restart = restart(ctx, svc.get("restart"), where + ".restart");
        Duration stopTimeout = ctx.duration(svc.get("stopTimeout"), where + ".stopTimeout",
                Duration.ofSeconds(10), Duration.ofMillis(100), Duration.ofMinutes(10));
        HealthCheckConfig health = health(ctx, svc.get("healthCheck"), where + ".healthCheck");
        Duration sampleInterval = svc.containsKey("sampleInterval")
                ? ctx.duration(svc.get("sampleInterval"), where + ".sampleInterval", null, Duration.ofMillis(200),
                Duration.ofHours(1))
                : null;
        RotationConfig rotation = ctx.rotation(svc.get("logRotation"), where + ".logRotation");

        if (ctx.errors.size() > errorsBefore) {
            return null;
        }
        return new ServiceConfig(name, command, workingDir, env, secretEnv, stdout, stderr, restart, stopTimeout,
                health, sampleInterval, rotation);
    }

    private List<String> commandList(Ctx ctx, Object raw, String where, boolean required) {
        if (raw == null) {
            if (required) {
                ctx.error(where + ": required (a list such as [\"/usr/bin/python3\", \"app.py\"])");
            }
            return List.of();
        }
        if (raw instanceof String) {
            ctx.error(where + ": must be a list of arguments, not a single string; ProcPilot never passes commands"
                    + " through a shell (write [\"/bin/echo\", \"hello\"] instead of \"echo hello\")");
            return List.of();
        }
        List<String> argv = ctx.stringList(raw, where);
        if (argv.isEmpty()) {
            ctx.error(where + ": must contain at least the executable");
            return List.of();
        }
        for (String a : argv) {
            if (a.indexOf('\0') >= 0) {
                ctx.error(where + ": argument contains a NUL character");
                return List.of();
            }
        }
        Optional<Path> exe = resolveExecutable(ctx, argv.get(0), where);
        if (exe.isEmpty()) {
            return List.of();
        }
        String base = exe.get().getFileName().toString();
        if (SHELLS.contains(base) && argv.subList(1, argv.size()).stream().anyMatch(a -> a.equals("-c"))) {
            ctx.warning(where + ": runs a shell with -c; the shell will interpret its script string. Prefer invoking"
                    + " the target program directly");
        }
        List<String> resolved = new ArrayList<>(argv);
        resolved.set(0, exe.get().toString());
        return resolved;
    }

    Optional<Path> resolveExecutable(Ctx ctx, String exe, String where) {
        if (exe.isBlank()) {
            ctx.error(where + ": executable is empty");
            return Optional.empty();
        }
        Path candidate;
        if (exe.contains("/")) {
            candidate = AppPaths.resolve(exe, ctx.baseDir);
        } else {
            candidate = searchPath(exe);
            if (candidate == null) {
                ctx.error(where + ": executable '" + exe + "' not found on PATH");
                return Optional.empty();
            }
        }
        if (!Files.exists(candidate)) {
            ctx.error(where + ": executable " + candidate + " does not exist");
            return Optional.empty();
        }
        if (!Files.isRegularFile(candidate)) {
            ctx.error(where + ": executable " + candidate + " is not a regular file");
            return Optional.empty();
        }
        if (!Files.isExecutable(candidate)) {
            ctx.error(where + ": " + candidate + " is not executable (try chmod u+x)");
            return Optional.empty();
        }
        try {
            Path real = candidate.toRealPath();
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(real);
            if (perms.contains(PosixFilePermission.OTHERS_WRITE)) {
                ctx.error(where + ": executable " + real + " is world-writable; any local user could replace it");
                return Optional.empty();
            }
        } catch (UnsupportedOperationException | IOException e) {
            ctx.warning(where + ": could not check permissions of " + candidate + ": " + e.getMessage());
        }
        return Optional.of(candidate);
    }

    private Path searchPath(String name) {
        String path = environment.getOrDefault("PATH", "/usr/local/bin:/usr/bin:/bin");
        for (String dir : path.split(":")) {
            // Empty or relative PATH entries would resolve against the current directory: skip them.
            if (dir.isEmpty() || !dir.startsWith("/")) {
                continue;
            }
            Path p = Path.of(dir, name);
            if (Files.isRegularFile(p) && Files.isExecutable(p)) {
                return p;
            }
        }
        return null;
    }

    private RestartConfig restart(Ctx ctx, Object raw, String where) {
        RestartConfig d = RestartConfig.defaults();
        Map<String, Object> m = ctx.map(raw, where);
        ctx.unknownKeys(m, RESTART_KEYS, where);
        RestartPolicyType policy = d.policy();
        if (m.get("policy") != null) {
            try {
                policy = RestartPolicyType.fromConfig(String.valueOf(m.get("policy")));
            } catch (IllegalArgumentException e) {
                ctx.error(where + ".policy: " + e.getMessage());
            }
        }
        int maxAttempts = ctx.integer(m.get("maxAttempts"), where + ".maxAttempts", d.maxAttempts(), 0, 1000);
        Duration initial = ctx.duration(m.get("initialDelay"), where + ".initialDelay", d.initialDelay(),
                Duration.ofMillis(10), Duration.ofHours(1));
        Duration max = ctx.duration(m.get("maxDelay"), where + ".maxDelay", d.maxDelay(), Duration.ofMillis(10),
                Duration.ofHours(24));
        if (initial != null && max != null && max.compareTo(initial) < 0) {
            ctx.error(where + ".maxDelay: must be >= initialDelay");
        }
        double multiplier = ctx.decimal(m.get("multiplier"), where + ".multiplier", d.multiplier(), 1.0, 10.0);
        Duration resetAfter = ctx.duration(m.get("resetAfter"), where + ".resetAfter", d.resetAfter(),
                Duration.ofMillis(100), Duration.ofDays(1));
        return new RestartConfig(policy, maxAttempts, initial, max, multiplier, resetAfter);
    }

    private HealthCheckConfig health(Ctx ctx, Object raw, String where) {
        if (raw == null) {
            return null;
        }
        Map<String, Object> m = ctx.map(raw, where);
        ctx.unknownKeys(m, HEALTH_KEYS, where);
        boolean hasHttp = m.get("http") != null;
        boolean hasCmd = m.get("command") != null;
        if (hasHttp == hasCmd) {
            ctx.error(where + ": specify exactly one of 'http' or 'command'");
            return null;
        }
        URI uri = null;
        List<String> cmd = null;
        if (hasHttp) {
            String s = String.valueOf(m.get("http"));
            try {
                uri = new URI(s);
                if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null) {
                    ctx.error(where + ".http: must be an absolute http:// or https:// URL with a host");
                }
            } catch (URISyntaxException e) {
                ctx.error(where + ".http: invalid URL '" + s + "'");
            }
        } else {
            cmd = commandList(ctx, m.get("command"), where + ".command", true);
        }
        Duration interval = ctx.duration(m.get("interval"), where + ".interval", Duration.ofSeconds(10),
                Duration.ofMillis(200), Duration.ofHours(1));
        Duration timeout = ctx.duration(m.get("timeout"), where + ".timeout", Duration.ofSeconds(2),
                Duration.ofMillis(50), Duration.ofMinutes(5));
        if (interval != null && timeout != null && timeout.compareTo(interval) > 0) {
            ctx.error(where + ".timeout: must not exceed interval");
        }
        int threshold = ctx.integer(m.get("failureThreshold"), where + ".failureThreshold", 3, 1, 100);
        Duration startPeriod = ctx.duration(m.get("startPeriod"), where + ".startPeriod", Duration.ofSeconds(5),
                Duration.ZERO, Duration.ofHours(1));
        return new HealthCheckConfig(uri, cmd, interval, timeout, threshold, startPeriod);
    }

    /** Validation context: error/warning collection and typed accessors. */
    static final class Ctx {
        final Path baseDir;
        final List<String> errors = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();

        Ctx(Path baseDir) {
            this.baseDir = baseDir;
        }

        void error(String msg) {
            errors.add(msg);
        }

        void warning(String msg) {
            warnings.add(msg);
        }

        void unknownKeys(Map<String, Object> m, Set<String> allowed, String where) {
            for (Object k : m.keySet()) {
                if (!allowed.contains(String.valueOf(k))) {
                    error(where + ": unknown key '" + k + "' (allowed: " + String.join(", ", allowed.stream().sorted().toList()) + ")");
                }
            }
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> map(Object raw, String where) {
            if (raw == null) {
                return Map.of();
            }
            if (!(raw instanceof Map)) {
                error(where + ": expected a mapping");
                return Map.of();
            }
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<Object, Object> e : ((Map<Object, Object>) raw).entrySet()) {
                out.put(String.valueOf(e.getKey()), e.getValue());
            }
            return out;
        }

        List<String> stringList(Object raw, String where) {
            if (raw == null) {
                return List.of();
            }
            if (!(raw instanceof List<?> list)) {
                error(where + ": expected a list");
                return List.of();
            }
            List<String> out = new ArrayList<>();
            for (Object o : list) {
                if (o == null || o instanceof Map || o instanceof List) {
                    error(where + ": list elements must be non-null scalars");
                    return List.of();
                }
                out.add(String.valueOf(o));
            }
            return out;
        }

        Optional<Path> path(Object raw, String where) {
            if (raw == null) {
                return Optional.empty();
            }
            if (raw instanceof Map || raw instanceof List) {
                error(where + ": expected a path string");
                return Optional.empty();
            }
            String s = String.valueOf(raw);
            if (s.isBlank() || s.indexOf('\0') >= 0) {
                error(where + ": invalid path");
                return Optional.empty();
            }
            return Optional.of(AppPaths.resolve(s, baseDir));
        }

        Duration duration(Object raw, String where, Duration def, Duration min, Duration max) {
            if (raw == null) {
                return def;
            }
            Duration d;
            try {
                d = Durations.parse(String.valueOf(raw));
            } catch (IllegalArgumentException e) {
                error(where + ": " + e.getMessage());
                return def;
            }
            if (d.compareTo(min) < 0 || d.compareTo(max) > 0) {
                error(where + ": " + Durations.format(d) + " is out of range [" + Durations.format(min) + ", "
                        + Durations.format(max) + "]");
                return def;
            }
            return d;
        }

        int integer(Object raw, String where, int def, int min, int max) {
            if (raw == null) {
                return def;
            }
            if (!(raw instanceof Integer i)) {
                error(where + ": expected an integer");
                return def;
            }
            if (i < min || i > max) {
                error(where + ": " + i + " is out of range [" + min + ", " + max + "]");
                return def;
            }
            return i;
        }

        double decimal(Object raw, String where, double def, double min, double max) {
            if (raw == null) {
                return def;
            }
            if (!(raw instanceof Number n)) {
                error(where + ": expected a number");
                return def;
            }
            double v = n.doubleValue();
            if (Double.isNaN(v) || v < min || v > max) {
                error(where + ": " + v + " is out of range [" + min + ", " + max + "]");
                return def;
            }
            return v;
        }

        RotationConfig rotation(Object raw, String where) {
            RotationConfig d = RotationConfig.defaults();
            if (raw == null) {
                return d;
            }
            Map<String, Object> m = map(raw, where);
            unknownKeys(m, ROTATION_KEYS, where);
            long maxBytes = d.maxBytes();
            if (m.get("maxSize") != null) {
                try {
                    maxBytes = Sizes.parse(String.valueOf(m.get("maxSize")));
                    if (maxBytes < 1024 || maxBytes > 10L * 1024 * 1024 * 1024) {
                        error(where + ".maxSize: must be between 1KB and 10GB");
                    }
                } catch (IllegalArgumentException | ArithmeticException e) {
                    error(where + ".maxSize: " + e.getMessage());
                }
            }
            int keep = integer(m.get("keep"), where + ".keep", d.keep(), 1, 100);
            return new RotationConfig(maxBytes, keep);
        }

        /** The directory must exist and be writable, or be creatable under a writable existing ancestor. */
        void writableLocation(Path dir, String where) {
            if (dir == null) {
                error(where + ": invalid location");
                return;
            }
            Path p = dir;
            while (p != null && !Files.exists(p)) {
                p = p.getParent();
            }
            if (p == null) {
                error(where + ": no existing ancestor for " + dir);
            } else if (!Files.isDirectory(p)) {
                error(where + ": " + p + " is not a directory");
            } else if (!Files.isWritable(p)) {
                error(where + ": " + (p.equals(dir) ? dir + " is not writable" : "cannot create " + dir + " (" + p
                        + " is not writable)"));
            }
        }
    }
}
