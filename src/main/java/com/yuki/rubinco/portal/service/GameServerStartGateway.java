package com.yuki.rubinco.portal.service;

/** Boundary that can later be replaced by an API Gateway/Lambda EC2 starter. */
public interface GameServerStartGateway {
  StartResult requestStart(String gameId, String requestedBy);

  record StartResult(boolean accepted, String status, String message) { }
}
