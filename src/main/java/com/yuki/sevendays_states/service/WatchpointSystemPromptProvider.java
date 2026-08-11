package com.yuki.sevendays_states.service;

import com.yuki.sevendays_states.config.AiAnalysisProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class WatchpointSystemPromptProvider {

  private final ResourceLoader resourceLoader;
  private final AiAnalysisProperties properties;
  private final AiAgentProfileService agentProfileService;
  private final WatchpointAiStateService stateService;
  private volatile String cachedContract;

  public String systemPrompt() {
    return agentProfileService.personalityPrompt(AiAgentProfileService.WATCHPOINT)
        + "\n\n" + contractPrompt() + "\n\n" + stateService.promptContext();
  }

  private String contractPrompt() {
    String current = cachedContract;
    if (current != null) {
      return current;
    }
    synchronized (this) {
      if (cachedContract == null) {
        cachedContract = loadPrompt();
      }
      return cachedContract;
    }
  }

  private String loadPrompt() {
    try (var input = resourceLoader.getResource(properties.systemPromptResource()).getInputStream()) {
      return new String(input.readAllBytes(), StandardCharsets.UTF_8).strip();
    } catch (IOException e) {
      throw new IllegalStateException("WATCHPOINT system prompt cannot be loaded.", e);
    }
  }
}
