package com.yuki.sevendays_states.service;

import java.time.LocalDate;
import java.util.SplittableRandom;
import org.springframework.stereotype.Service;

@Service
public class KarenPopularityService {

  public int baseLikes(KarenPostTheme theme, boolean hasImage, LocalDate date) {
    SplittableRandom random = KarenPostGenerator.random(date, 0x4c494b4553L + theme.ordinal());
    int likes = random.nextInt(theme.minimumLikes(), theme.maximumLikes() + 1);
    if (!hasImage) return likes;

    if (isBuzz(theme, random)) {
      return theme == KarenPostTheme.GLAMOUR_SELFIE
          ? random.nextInt(8_000, 20_001)
          : random.nextInt(5_000, 10_001);
    }
    int multiplierPercent = random.nextInt(150, 301);
    return Math.max(likes + 1, Math.round(likes * multiplierPercent / 100f));
  }

  private boolean isBuzz(KarenPostTheme theme, SplittableRandom random) {
    return (theme == KarenPostTheme.SELFIE || theme == KarenPostTheme.GLAMOUR_SELFIE)
        && random.nextInt(100) < 2;
  }
}
