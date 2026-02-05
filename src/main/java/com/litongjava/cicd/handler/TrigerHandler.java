package com.litongjava.cicd.handler;

import com.litongjava.cicd.service.TrigerService;
import com.litongjava.model.body.RespBodyVo;
import com.litongjava.tio.boot.http.TioRequestContext;
import com.litongjava.tio.http.common.HttpRequest;
import com.litongjava.tio.http.common.HttpResponse;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class TrigerHandler {
  public HttpResponse trigger(HttpRequest request) {
    log.info("body:{}", request.getBodyString());
    String projectName = request.getString("name");
    String requestURL = "Hide";
    RespBodyVo respVo = TrigerService.triger(projectName, requestURL);
    return TioRequestContext.getResponse().setJson(respVo);
  }
}