package com.yuki.sevendays_states.service;

import java.time.LocalDate;
import java.util.SplittableRandom;
import org.springframework.stereotype.Service;

@Service
public class MarkPopularityService {

  public int baseLikes(SurvivorMarkCandidateService.Candidate candidate, LocalDate date) {
    SplittableRandom random = new SplittableRandom(date.toEpochDay() ^ candidate.key().hashCode() ^ 0x4c494b45L);
    int min = candidate.kills() >= 3 || candidate.zombieTypes().stream()
        .anyMatch(type -> type != null && (type.contains("Radiated") || type.contains("Screamer"))) ? 80 : 20;
    int max = min == 80 ? 400 : 120;
    return random.nextInt(min, max + 1);
  }
}
