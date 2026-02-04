package com.litongjava.cicd.utils;
import java.io.IOException;

import com.litongjava.tio.utils.environment.EnvUtils;
import com.litongjava.tio.utils.notification.LarksuiteNotificationUtils;
import com.litongjava.tio.utils.notification.NotifactionWarmModel;

import lombok.extern.slf4j.Slf4j;
import okhttp3.Response;

@Slf4j
public class CiCdBuildBot {

  private static String webHookUrl = EnvUtils.get("warm.notification.webhook.url");

  public static void sendWarm(NotifactionWarmModel model) {
    try (Response response = LarksuiteNotificationUtils.sendWarm(webHookUrl, model)) {
      if (!response.isSuccessful()) {
        try {
          log.info("Failed to push message: {} {}", response.code(), response.body().string());
        } catch (IOException e1) {
          e1.printStackTrace();
        }
      }
    }
  }
}
