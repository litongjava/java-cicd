package com.litongjava.cicd.handler;

import com.litongjava.cicd.service.TrigerService;
import com.litongjava.model.body.RespBodyVo;
import com.litongjava.tio.boot.http.TioRequestContext;
import com.litongjava.tio.http.common.HttpRequest;
import com.litongjava.tio.http.common.HttpResponse;

public class TrigerHandler {
  public HttpResponse trigger(HttpRequest request) {
    String projectName = request.getString("name");
    String protocol = request.getRequestLine().getProtocol();
    String host = request.getHost();
    String requestURI = request.getRequestURI();
    String requestURL = protocol.toLowerCase() + "://" + host + requestURI;
    RespBodyVo respVo = TrigerService.triger(projectName, requestURL);
    return TioRequestContext.getResponse().setJson(respVo);
  }
}