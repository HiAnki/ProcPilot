# ProcPilot

A small Linux process supervisor and `/proc` inspector in Java 21.

## What it does

Companies run many programs on their servers around the clock: websites, payment systems, data jobs. Sometimes
one of those programs crashes. If nobody notices, the service stays down until a person finds the problem and
starts it again by hand.

**ProcPilot is a "babysitter" for programs.** You give it a list of programs to look after, and it:

- **Starts them** and keeps a record of everything they print, so problems can be investigated later.
- **Notices when one crashes and restarts it automatically**, usually within a second or two.
- **Knows when to stop trying.** If a program keeps crashing, ProcPilot waits a little longer between each
  attempt and eventually gives up and marks it as failed, rather than retrying forever.
- **Shuts everything down cleanly** when asked. It first asks each program politely to finish, and only forces
  it to stop if it doesn't respond in time.
- **Reports on any running program's health:** how much memory and processor time it uses, and how many files
  it has open.

It also checks its settings file before doing anything, and explains mistakes clearly instead of failing in
confusing ways.

### Why it matters

Large companies rely on tools like this, such as Linux's built-in `systemd`, to keep services available.
ProcPilot is a compact version built from scratch to show an understanding of how they work underneath: how
operating systems run and stop programs, how to measure what a program is using, and how to handle several
things happening at the same moment without errors.

### What it demonstrates about the developer

- **Backend engineering in Java**, with a clean, organised code structure.
- **Linux and operating-system knowledge**: processes, signals, system resource monitoring.
- **Reliability thinking**: automatic recovery, protection against endless crash loops, clean shutdown.
- **Security awareness**: needs no administrator rights, never shows passwords or secret values, and rejects
  unsafe settings.
- **Quality practices**: 40 automated tests that run real programs to confirm that crash recovery and
  shutdown behave as described.

## Quick start

```bash
cd ~/Downloads/ProcPilot
mvn package                                                   # build + run tests
java -jar target/procpilot.jar help
java -jar target/procpilot.jar --config examples/procpilot.yaml run   # Ctrl-C to stop
java -jar target/procpilot.jar inspect $$                     # inspect your shell process
```

Optional alias: `alias procpilot='java -jar ~/Downloads/ProcPilot/target/procpilot.jar'`

Entry point: `com.procpilot.cli.Main` (`src/main/java/com/procpilot/cli/Main.java`).

## Build and test

Requires Linux, Java 21 and Maven.

```bash
mvn test                          # unit and integration tests (real child processes)
mvn -DskipTests package           # builds target/procpilot.jar
```

## Usage

```bash
java -jar target/procpilot.jar help
java -jar target/procpilot.jar --config examples/procpilot.yaml validate-config
java -jar target/procpilot.jar --config examples/procpilot.yaml run [service...] [--status-interval 30]
java -jar target/procpilot.jar inspect <pid>
```

`run` supervises the selected services in the foreground and prints a status table periodically.
**Ctrl-C** stops every service gracefully: SIGTERM first, then SIGKILL once `stopTimeout` expires.

Exit codes: 0 ok, 1 failure, 2 usage, 3 invalid config, 5 not found.

## Configuration (YAML)

```yaml
settings:
  logDir: ../logs              # relative paths resolve against the config file's directory
services:
  crashy:
    command: ["/bin/sh", "-c", "echo starting; sleep 1; exit 42"]   # argv list, never a shell string
    workingDir: .
    env: {PORT: "8080"}
    secretEnv: [DB_URL]        # values never printed (names containing token/secret/pass/key are masked too)
    stdout: logs/crashy.out    # default: <logDir>/<name>.out.log / .err.log
    restart:
      policy: on-failure       # never | on-failure | always
      maxAttempts: 3
      initialDelay: 500ms
      maxDelay: 4s
      multiplier: 2.0
      resetAfter: 60s          # a run at least this long resets the failure counter
    stopTimeout: 3s
```

Validation reports every error at once. It rejects unknown keys, duplicate names, commands given as one string,
missing, non-executable or world-writable executables, and out-of-range values.

## Crash-recovery demo

```
13:21:02.395 [crashy] started pid=20348
13:21:03.416 [crashy] exited unexpectedly with code 42 after 1003ms
13:21:03.432 [crashy] restarting in 500ms (attempt 1/3)
13:21:03.945 [crashy] started pid=20355 (restart #1)
13:21:04.943 [crashy] restarting in 1000ms (attempt 2/3)
13:21:06.949 [crashy] restarting in 2000ms (attempt 3/3)
13:21:09.958 [crashy] giving up after 3 restart attempts (crash loop)
--- status ---
ticker           RUNNING   pid=20343   restarts=0   lastExit=-
crashy           FAILED    pid=-       restarts=3   lastExit=42
```

### Reading the output

| Log line | Meaning |
|---|---|
| `started pid=N` | Child process launched; `(restart #k)` if it is a restart |
| `exited unexpectedly with code C after Tms` | The process ended without a stop request |
| `restarting in Dms (attempt a/max)` | Restart scheduled after an exponential backoff delay |
| `giving up after N restart attempts (crash loop)` | `maxAttempts` reached; service is now `FAILED` |
| `stopping pid=N (SIGTERM, timeout ...)` | Graceful stop requested |
| `did not exit within timeout; sending SIGKILL` | Forced termination after `stopTimeout` |
| `pending restart cancelled by stop request` | A scheduled restart was dropped because of shutdown |

Exit code `130` means the process was killed by SIGINT (128 + 2). When you press Ctrl-C, the terminal sends
SIGINT to the services too. A service can therefore show up as "exited unexpectedly" just before ProcPilot's own
shutdown starts. The restart that was scheduled is then cancelled, as the last line shows.

Service output goes to `logs/<name>.out.log` and `logs/<name>.err.log`, e.g. `tail -f logs/ticker.out.log`.

## Design notes

- **Output capture**: child stdout and stderr are redirected straight to files (`Redirect.appendTo`). There are
  no pipes, so a chatty child can never block on a full pipe, and nothing is buffered in memory.
- **Races**: per-service state is guarded by one monitor. Each launch gets a generation number, and exit
  callbacks from an older generation are ignored. An intentional stop sets a flag that suppresses restarts and
  cancels any pending restart, so a service is never restarted after a stop.
- **CPU %** in `inspect` is `Δ(utime+stime) / CLK_TCK / Δwall` over a 500ms sample. 100% means one full
  core, and it is also shown normalized by core count. If the process exits mid-sample, CPU shows `N/A`
  instead of a made-up value.
- **No root, no shell**: commands run via `ProcessBuilder` argument lists. Only processes ProcPilot
  started (and their descendants at stop time) are ever signalled.

