package com.litongjava.cicd.config;

import java.io.File;

import com.litongjava.cicd.consts.CiCdConst;
import com.litongjava.cicd.handler.TrigerHandler;

import nexus.io.context.BootConfiguration;
import nexus.io.tio.boot.server.TioBootServer;
import nexus.io.tio.http.server.router.HttpRequestRouter;

public class CiCiAdminAppConfig implements BootConfiguration {

  public void config() {

    File file = new File(CiCdConst.projects);
    if (!file.exists()) {
      file.mkdirs();
    }
    TioBootServer server = TioBootServer.me();
    HttpRequestRouter requestRouter = server.getRequestRouter();
    if (requestRouter != null) {
      TrigerHandler helloHandler = new TrigerHandler();
      requestRouter.add("/trigger/{name}", helloHandler::trigger);
    }
  }
}
