package com.litongjava.cicd;

import com.litongjava.cicd.config.CiCiAdminAppConfig;
import com.litongjava.tio.boot.TioApplication;

public class CiCdApp {
  public static void main(String[] args) {
    long start = System.currentTimeMillis();
    TioApplication.run(CiCdApp.class, new CiCiAdminAppConfig(), args);
    long end = System.currentTimeMillis();
    System.out.println((end - start) + "ms");
  }
}