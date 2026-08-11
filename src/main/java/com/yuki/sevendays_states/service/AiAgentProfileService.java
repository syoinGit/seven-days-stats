package com.yuki.sevendays_states.service;

import com.yuki.sevendays_states.repository.M_AiAgentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AiAgentProfileService {
  public static final String WATCHPOINT = "WATCHPOINT";
  public static final String KAREN = "SURVIVOR_KAREN";
  public static final String MARK = "SURVIVOR_MARK";

  private final M_AiAgentRepository repository;

  @Transactional(readOnly = true)
  public String personalityPrompt(String agentCode) {
    return repository.findByAgentCodeAndEnabledTrue(agentCode)
        .map(agent -> agent.getPersonalityPrompt().strip())
        .filter(prompt -> !prompt.isBlank())
        .orElseThrow(() -> new IllegalStateException("AI agent master is missing: " + agentCode));
  }
}
