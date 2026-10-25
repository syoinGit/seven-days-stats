package com.yuki.rubinco.portal.service;

/** A game that knows how many players are connected right now, for the portal's site list. */
public interface OnlinePlayerSource {
  String gameId();

  int onlineCount();
}
