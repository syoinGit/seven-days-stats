package com.yuki.rubinco.portal.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    prefix = "app.games", name = "start-provider", havingValue = "mock", matchIfMissing = true)
public class MockGameServerStartGateway implements GameServerStartGateway {
  @Override
  public StartResult requestStart(String gameId, String requestedBy) {
    return new StartResult(
        true, "starting", "モック起動要求を受け付けました。実EC2は操作していません。");
  }
}
