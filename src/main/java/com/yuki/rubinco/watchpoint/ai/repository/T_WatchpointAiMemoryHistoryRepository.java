package com.yuki.rubinco.watchpoint.ai.repository;

import com.yuki.rubinco.watchpoint.ai.entity.T_WatchpointAiMemoryHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface T_WatchpointAiMemoryHistoryRepository
    extends JpaRepository<T_WatchpointAiMemoryHistory, Long> {
}
