package com.litongjava.cicd.service;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

import com.litongjava.cicd.consts.CiCdConst;
import com.litongjava.cicd.model.BuildTask;
import com.litongjava.cicd.utils.CiCdBuildBot;
import com.litongjava.model.body.RespBodyVo;
import com.litongjava.tio.utils.commandline.ProcessResult;
import com.litongjava.tio.utils.commandline.ProcessUtils;
import com.litongjava.tio.utils.notification.NotifactionWarmModel;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class TrigerService {

  // ----------------------
  // Config
  // ----------------------
  private static final int WORKERS = Math.max(1, Runtime.getRuntime().availableProcessors());
  private static final int QUEUE_CAPACITY = 200;
  private static final int TIMEOUT_SECONDS = 30 * 60; // 30 minutes
  private static final int LOG_TAIL_CHARS = 3000;

  /** Projects root: projects/{name} */
  private static final File PROJECTS_ROOT = new File(CiCdConst.projects).getAbsoluteFile();

  // ----------------------
  // Queue + Workers
  // ----------------------
  private static final BlockingQueue<BuildTask> QUEUE = new LinkedBlockingQueue<>(QUEUE_CAPACITY);

  /**
   * One build per normalized project path (queued or running). Value is buildId.
   */
  private static final ConcurrentHashMap<String, String> RUNNING = new ConcurrentHashMap<>();

  private static final String HOSTNAME = resolveHostname();

  private static final String CI_REJECTED = "[CI REJECTED]";
  private static final String CI_ERROR = "[CI ERROR]";
  private static final String CI_SUCCESS = "[CI SUCCESS]";
  private static final String CI_FAILED = "[CI FAILED]";
  private static final String CI_TIMEOUT = "[CI TIMEOUT]";
  private static final String CI_EXCEPTION = "[CI EXCEPTION]";
  private static final String CI_FATAL = "[CI FATAL]";

  static {
    for (int i = 0; i < WORKERS; i++) {
      Thread.ofVirtual().name("ci-worker-" + i).start(() -> {
        while (true) {
          try {
            BuildTask task = QUEUE.take();
            runBuild(task);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
          } catch (Exception e) {
            log.error(e.getMessage(), e);
          }
        }
      });
    }
  }

  /**
   * Trigger a build.
   *
   * @param projectName project name (NOT a path). Real directory is
   *                    projects/{name}
   * @param webhookUrl  Lark webhook URL (passed in by caller)
   */
  public static RespBodyVo triger(String projectName, String webhookUrl) {
    if (projectName == null || projectName.isBlank()) {
      return RespBodyVo.fail("projectName is blank");
    }
    if (webhookUrl == null || webhookUrl.isBlank()) {
      return RespBodyVo.fail("webhookUrl is blank");
    }

    // Resolve project directory as projects/{name}
    File projectDir;
    try {
      projectDir = resolveProjectDir(projectName);
    } catch (IllegalArgumentException e) {
      String buildId = newBuildId();
      sendOnce(webhookUrl, buildId, projectName,
          CI_REJECTED + " Invalid project name\n\n" + "Project  : " + projectName + "\n" + "Reason   : "
              + e.getMessage() + "\n" + "Host     : " + HOSTNAME + "\n" + "Time     : " + now() + "\n" + "Root     : "
              + PROJECTS_ROOT.getAbsolutePath() + "\n");
      return RespBodyVo.fail("invalid projectName");
    }

    String projectKey = projectDir.getAbsolutePath(); // normalized key
    String buildId = newBuildId();

    // Reserve slot at trigger time (queued or running)
    String prev = RUNNING.putIfAbsent(projectKey, buildId);
    if (prev != null) {
      String content = formatRejectedInProgress(projectKey, prev, buildId, "api");
      sendOnce(webhookUrl, buildId, projectName, content);
      return RespBodyVo.fail("build already in progress").data(prev);
    }

    BuildTask task = new BuildTask(projectName, projectKey, buildId, webhookUrl, "api");

    boolean offered = QUEUE.offer(task);
    if (!offered) {
      // Queue full: release reservation
      RUNNING.remove(projectKey, buildId);

      String content = formatRejectedQueueFull(projectKey, buildId, "api");
      sendOnce(webhookUrl, buildId, projectName, content);
      return RespBodyVo.fail("build queue is full").data(buildId);
    }

    return RespBodyVo.ok("build queued").data(buildId);
  }

  // ----------------------
  // Build execution (single notification per task)
  // ----------------------
  private static void runBuild(BuildTask task) {
    final String projectName = task.projectName; // name
    final String projectKey = task.projectKey; // absolute path
    final String buildId = task.buildId;
    final String webhookUrl = task.webhookUrl;
    final String trigger = task.trigger;

    long start = System.currentTimeMillis();

    String status = null;
    String reason = null;

    String osLine = null;
    String cmdLine = null;

    String logDirPath = null;
    Integer exitCode = null;

    String stdErrTail = null;
    String stdOutTail = null;

    try {
      File projectDir = new File(projectKey); // always projects/{name}
      File build = new File(projectDir, "build.sh");

      // Project dir validation
      if (!projectDir.exists() || !projectDir.isDirectory()) {
        status = CI_ERROR;
        reason = "Project directory does not exist (not a directory)";
        sendOnce(webhookUrl, buildId, projectName, formatFinalMessage(status, projectKey, buildId, trigger, start, null,
            null, null, reason, null, null, null));
        return;
      }

      // build.sh validation
      if (!build.exists() || !build.isFile()) {
        status = CI_ERROR;
        reason = "build.sh not found: " + build.getAbsolutePath();
        sendOnce(webhookUrl, buildId, projectName, formatFinalMessage(status, projectKey, buildId, trigger, start, null,
            null, null, reason, null, null, null));
        return;
      }

      // Per-build log dir: projects/{name}/logs/{buildId}
      File logDir = new File(projectDir, "logs" + File.separator + buildId);
      logDirPath = logDir.getAbsolutePath();

      ProcessBuilder pb = new ProcessBuilder();
      pb.directory(projectDir);

      // OS-specific command
      if (isWindows()) {
        osLine = "windows";
        cmdLine = "bash " + build.getAbsolutePath();
        pb.command("bash", build.getAbsolutePath());
      } else {
        osLine = "unix";
        if (!build.canExecute()) {
          build.setExecutable(true, false); // best effort
        }
        cmdLine = build.getAbsolutePath();
        pb.command(build.getAbsolutePath());
      }

      ProcessResult r = ProcessUtils.execute(logDir, buildId, pb, TIMEOUT_SECONDS);

      exitCode = r.getExitCode();
      stdErrTail = tail(r.getStdErr());
      stdOutTail = tail(r.getStdOut());

      if (exitCode == 0) {
        status = CI_SUCCESS;
      } else if (exitCode == -1) {
        status = CI_TIMEOUT;
        reason = "Build timed out after " + TIMEOUT_SECONDS + " seconds";
      } else {
        status = CI_FAILED;
        reason = "Non-zero exit code";
      }

      sendOnce(webhookUrl, buildId, projectName, formatFinalMessage(status, projectKey, buildId, trigger, start, osLine,
          cmdLine, logDirPath, reason, exitCode, stdErrTail, stdOutTail));

    } catch (IOException | InterruptedException e) {
      Thread.currentThread().interrupt();
      status = CI_EXCEPTION;
      reason = e.getClass().getSimpleName() + ": " + e.getMessage();

      sendOnce(webhookUrl, buildId, projectName, formatFinalMessage(status, projectKey, buildId, trigger, start, osLine,
          cmdLine, logDirPath, reason, exitCode, stdErrTail, stdOutTail));

    } catch (Exception e) {
      status = CI_FATAL;
      reason = e.getClass().getSimpleName() + ": " + e.getMessage();

      sendOnce(webhookUrl, buildId, projectName, formatFinalMessage(status, projectKey, buildId, trigger, start, osLine,
          cmdLine, logDirPath, reason, exitCode, stdErrTail, stdOutTail));
    } finally {
      RUNNING.remove(projectKey, buildId);
    }
  }

  // ----------------------
  // Notification
  // ----------------------
  private static void sendOnce(String webhookUrl, String buildId, String projectName, String content) {
    NotifactionWarmModel model = new NotifactionWarmModel();
    model.setTaskId(buildId);
    model.setWarningName(projectName);
    model.setUrl(webhookUrl);
    model.setContent(content);
    CiCdBuildBot.sendWarm(model);
  }

  // ----------------------
  // Message formatting
  // ----------------------
  private static String formatRejectedInProgress(String projectKey, String runningBuildId, String thisBuildId,
      String trigger) {
    StringBuilder sb = new StringBuilder(256);
    sb.append(CI_REJECTED).append(" Build already in progress\n\n");
    sb.append("Project  : ").append(projectKey).append('\n');
    sb.append("Running  : ").append(runningBuildId).append('\n');
    sb.append("Requested: ").append(thisBuildId).append('\n');
    sb.append("Trigger  : ").append(trigger).append('\n');
    sb.append("Host     : ").append(HOSTNAME).append('\n');
    sb.append("Time     : ").append(now()).append('\n');
    return sb.toString();
  }

  private static String formatRejectedQueueFull(String projectKey, String buildId, String trigger) {
    StringBuilder sb = new StringBuilder(256);
    sb.append(CI_REJECTED).append(" Build queue is full, trigger rejected\n\n");
    sb.append("Project : ").append(projectKey).append('\n');
    sb.append("BuildId : ").append(buildId).append('\n');
    sb.append("Trigger : ").append(trigger).append('\n');
    sb.append("Host    : ").append(HOSTNAME).append('\n');
    sb.append("Time    : ").append(now()).append('\n');
    return sb.toString();
  }

  private static String formatFinalMessage(String status, String projectKey, String buildId, String trigger,
      long startMs, String osLine, String cmdLine, String logDirPath, String reason, Integer exitCode,
      String stderrTail, String stdoutTail) {

    long durationMs = System.currentTimeMillis() - startMs;

    StringBuilder sb = new StringBuilder(1024);
    sb.append(status).append('\n').append('\n');

    sb.append("Project  : ").append(projectKey).append('\n');
    sb.append("BuildId  : ").append(buildId).append('\n');
    sb.append("Host     : ").append(HOSTNAME).append('\n');
    sb.append("Trigger  : ").append(trigger).append('\n');
    sb.append("Time     : ").append(now()).append('\n');
    sb.append("Duration : ").append(durationMs).append(" ms\n");

    if (osLine != null)
      sb.append("OS       : ").append(osLine).append('\n');
    if (cmdLine != null)
      sb.append("Command  : ").append(cmdLine).append('\n');
    if (logDirPath != null)
      sb.append("LogDir   : ").append(logDirPath).append('\n');
    if (exitCode != null)
      sb.append("ExitCode : ").append(exitCode).append('\n');

    if (reason != null && !reason.isBlank()) {
      sb.append('\n').append("Reason:\n").append(reason).append('\n');
    }

    // Only attach logs for non-success
    if (!CI_SUCCESS.equals(status)) {
      if (stderrTail != null && !stderrTail.isBlank()) {
        sb.append('\n').append("Last stderr:\n").append(stderrTail).append('\n');
      }
      if (stdoutTail != null && !stdoutTail.isBlank()) {
        sb.append('\n').append("Last stdout:\n").append(stdoutTail).append('\n');
      }
    }

    return sb.toString();
  }

  // ----------------------
  // Helpers
  // ----------------------

  /**
   * Resolve project directory as projects/{name}, and prevent path traversal.
   */
  private static File resolveProjectDir(String projectName) {
    String name = projectName.trim();

    // reject obvious traversal / path inputs
    if (name.contains("..") || name.contains("/") || name.contains("\\") || name.contains(File.separator)) {
      throw new IllegalArgumentException("projectName must be a simple name (no path separators)");
    }

    File dir = new File(PROJECTS_ROOT, name).getAbsoluteFile();

    // strong check: must stay under projects root
    String rootPath = PROJECTS_ROOT.getPath();
    String dirPath = dir.getPath();
    if (!dirPath.equals(rootPath) && !dirPath.startsWith(rootPath + File.separator)) {
      throw new IllegalArgumentException("projectName resolves outside projects root");
    }

    return dir;
  }

  private static String tail(String s) {
    if (s == null)
      return "";
    return s.length() <= LOG_TAIL_CHARS ? s : s.substring(s.length() - LOG_TAIL_CHARS);
  }

  private static boolean isWindows() {
    String os = System.getProperty("os.name");
    return os != null && os.toLowerCase().contains("win");
  }

  private static String newBuildId() {
    String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
    String rand = UUID.randomUUID().toString().substring(0, 8);
    return ts + "-" + rand;
  }

  private static String now() {
    return LocalDateTime.now().toString();
  }

  private static String resolveHostname() {
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (Exception e) {
      return "unknown-host";
    }
  }
}
