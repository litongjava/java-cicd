package com.litongjava.cicd.model;

public class BuildTask {
  public final String projectName;
  public final String projectKey;
  public final String buildId;

  public final String webhookUrl;
  public final String trigger;

  public BuildTask(String projectName, String projectKey, String buildId, String webhookUrl, String trigger) {
    this.projectName = projectName;
    this.projectKey = projectKey;
    this.buildId = buildId;
    this.webhookUrl = webhookUrl;
    this.trigger = trigger;
  }
}
