package com.yuki.rubinco.watchpoint.readmodel;

import com.yuki.rubinco.shared.DisplayTimeFormatter;
import com.yuki.rubinco.watchpoint.ai.AiCommentService;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DiaryViewService {

  private static final int ARCHIVE_EXCERPT_LENGTH = 180;

  private final AiCommentService aiCommentService;
  private final DisplayTimeFormatter displayTimeFormatter = new DisplayTimeFormatter();

  public List<DiaryCard> archive() {
    return aiCommentService.diaries().stream()
        .map(entry -> new DiaryCard(
            entry.diaryDate(), entry.title(),
            entry.summary() == null || entry.summary().isBlank()
                ? excerpt(entry.body(), ARCHIVE_EXCERPT_LENGTH) : entry.summary(),
            entry.tags(), displayTimeFormatter.format(entry.publishedAt())))
        .toList();
  }

  public Optional<DiaryDetail> detail(LocalDate date) {
    return aiCommentService.findByDiaryDate(date)
        .map(entry -> new DiaryDetail(
            entry.diaryDate(), entry.title(), entry.body(), entry.summary(), entry.tags(),
            displayTimeFormatter.format(entry.publishedAt())));
  }

  static String excerpt(String body, int maxLength) {
    String normalized = body == null ? "" : body.replaceAll("\\s+", " ").strip();
    if (normalized.length() <= maxLength) {
      return normalized;
    }
    return normalized.substring(0, maxLength).stripTrailing() + "…";
  }

  public record DiaryCard(
      LocalDate date, String title, String excerpt, List<String> tags, String publishedAt) {
  }

  public record DiaryDetail(
      LocalDate date,
      String title,
      String body,
      String summary,
      List<String> tags,
      String publishedAt) {
  }
}
