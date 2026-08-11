package com.yuki.sevendays_states.repository;

import com.yuki.sevendays_states.entity.M_AiAgent;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface M_AiAgentRepository extends JpaRepository<M_AiAgent, Long> {
  Optional<M_AiAgent> findByAgentCodeAndEnabledTrue(String agentCode);
}
