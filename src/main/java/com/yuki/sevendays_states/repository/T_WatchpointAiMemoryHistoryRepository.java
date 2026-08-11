package com.yuki.sevendays_states.repository;

import com.yuki.sevendays_states.entity.T_WatchpointAiMemoryHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface T_WatchpointAiMemoryHistoryRepository
    extends JpaRepository<T_WatchpointAiMemoryHistory, Long> {
}
