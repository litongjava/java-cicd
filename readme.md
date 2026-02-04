# java-cicd

A lightweight, high-performance CI/CD trigger service built with modern Java.  
Designed for reliability, safety, and simplicity, this project executes build scripts inside controlled concurrency limits while providing structured notifications via Lark/Feishu webhooks.

---

## Features

### Safe Build Execution
- Executes `build.sh` from `projects/{name}`
- Prevents directory traversal attacks
- Automatically ensures script executability on Unix systems
- OS-aware command handling (Windows / Linux / macOS)

---

### Concurrency Control
- Virtual-thread powered workers
- Queue-based scheduling
- Backpressure protection
- One active build per project (queued or running)

This prevents resource exhaustion caused by uncontrolled process spawning.

---

### Structured Notifications
Sends a **single consolidated message** per build:

Includes:

- Build status (SUCCESS / FAILED / TIMEOUT / ERROR)
- Project name
- Build ID
- Hostname
- Trigger source
- Execution duration
- Exit code
- Log directory
- Last stderr/stdout (when failed)

Compatible with:

- Lark / Feishu bots
- Any webhook adapter

---

### Security-Oriented Design
Projects must exist under:

```
projects/{projectName}
```

Invalid paths are rejected automatically.

Example blocked inputs:

```
../../etc
/var/tmp
projects/other
```

---

## Project Structure

```
java-cicd
│
├── projects/
│   ├── demo-service/
│   │   ├── build.sh
│   │   └── logs/
│
├── src/main/java/
│   ├── cicd/service/TrigerService.java
│   ├── cicd/model/BuildTask.java
│   └── cicd/utils/CiCdBuildBot.java

```

---

## Requirements

### Java
Recommended:

```
Java 21+
```

Virtual threads require modern Java.

---

### Build Script

Each project must provide:

```
build.sh
````

Example:

```bash
#!/bin/bash
set -e

echo "Starting build..."

mvn clean package

echo "Build finished."
````

Make it executable:

```
chmod +x build.sh
```

---

## Usage

### Trigger a Build

```java
RespBodyVo response =
    TrigerService.triger("demo-service", webhookUrl);
```

Parameters:

| Parameter   | Description               |
| ----------- | ------------------------- |
| projectName | Folder inside `projects/` |
| webhookUrl  | Lark/Feishu bot webhook   |

---

### Example Response

**Queued**

```json
{
  "msg": "build queued",
  "data": "20250204-103322-ab12cd"
}
```

---

## Notification Example

```
[CI SUCCESS]

Project  : /opt/java-cicd/projects/demo-service
BuildId  : 20250204-103322-ab12cd
Host     : ci-node-1
Trigger  : api
Time     : 2025-02-04T10:33:22
Duration : 18234 ms
OS       : unix
Command  : /opt/java-cicd/projects/demo-service/build.sh
LogDir   : /opt/java-cicd/projects/demo-service/logs/20250204-103322-ab12cd
ExitCode : 0
```

---

## Concurrency Model

The system uses a **bounded queue + virtual thread workers**.

Why this matters:

Without limits:

```
50 webhook triggers
→ 50 docker builds
→ CPU saturation
→ disk contention
→ node crash
```

With java-cicd:

```
Workers = CPU cores
Excess builds → queued
```

This is the same protection strategy used by:

* Kafka
* Netty
* Kubernetes controllers
* Nginx

---

## Failure Handling

Automatically detects:

* Missing project directory
* Missing build.sh
* Non-zero exit code
* Build timeout
* Unexpected exceptions

All cases produce a notification.

---

## Timeout

Default:

```
30 minutes
```

Configurable inside:

```
TrigerService.TIMEOUT_SECONDS
```

---

## Log Strategy

Each build generates:

```
projects/{name}/logs/{buildId}/
```

Benefits:

* No log overwrites
* Easy debugging
* Clean history
* Parallel-safe

---

## Design Philosophy

java-cicd favors:

* Predictability over magic
* Resource control over blind parallelism
* Operational clarity
* Minimal dependencies
* Production safety

This is not a full CI platform — it is an **execution core** you can trust.

---

## Recommended Production Setup

### Do NOT set workers equal to CPU cores for heavy builds.

Better:

```
workers = cores / 2
```

Reason:

Build workloads are both CPU and IO intensive.

Leave headroom for:

* Docker
* Filesystem cache
* JVM
* OS scheduler

---

## Common Enhancements (Optional)

If you plan to evolve this into a larger CI system, consider adding:

* Build status API
* Web UI
* Retry support
* Priority queue
* Distributed runners
* Artifact storage
* Live log streaming
* Git webhook integration

The current architecture already supports these upgrades.

---

## Example API Layer

Typical REST endpoint:

```java
@PostMapping("/build")
public RespBodyVo build(
        @RequestParam String project,
        @RequestParam String webhook) {

    return TrigerService.triger(project, webhook);
}
```

---

## Troubleshooting

### Build never starts

Check:

```
projects/{name}/build.sh
```

Exists and is executable.

---

### Script runs manually but fails in CI

Often caused by missing environment variables.

Use absolute paths inside scripts.

---

### Windows users

Install one of:

* Git Bash
* WSL
* MSYS2

Ensure `bash` is available in PATH.

---

## Why Virtual Threads?

Virtual threads provide:

* Massive scalability
* Minimal memory footprint
* Blocking-friendly execution
* Cleaner code vs reactive styles

Perfect for process-heavy orchestration.

---

## License

MIT (recommended — adjust if needed)

---

## Final Notes

java-cicd is intentionally simple yet production-aware.

It avoids the biggest CI mistake:

> Unlimited parallel builds.

Control your processes — control your uptime.

```
```