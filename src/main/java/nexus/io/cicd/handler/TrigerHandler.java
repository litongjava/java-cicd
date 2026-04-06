package nexus.io.cicd.handler;

import lombok.extern.slf4j.Slf4j;
import nexus.io.cicd.service.TrigerService;
import nexus.io.model.body.RespBodyVo;
import nexus.io.tio.boot.http.TioRequestContext;
import nexus.io.tio.http.common.HttpRequest;
import nexus.io.tio.http.common.HttpResponse;

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