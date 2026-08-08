package com.yuki.sevendays_states.service;

import com.yuki.sevendays_states.entity.T_AiComment;
import com.yuki.sevendays_states.entity.AiPostType;
import com.yuki.sevendays_states.repository.T_AiCommentRepository;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AiCommentService {

  private final T_AiCommentRepository repository;
  private final TimelinePostService timelinePostService;

  @Value("${app.ai-comment.editor-key:}")
  private String editorKey;

  public Optional<AiCommentEntry> latestDiary() {
    return repository.findTopByDiaryDateIsNotNullOrderByDiaryDateDescPublishedAtDesc()
        .map(this::toEntry);
  }

  public Optional<AiCommentEntry> latestComment() {
    return repository.findTopByOrderByPublishedAtDesc().map(this::toEntry);
  }

  public Optional<AiCommentEntry> latestBySourceType(String sourceType) {
    return repository.findTopBySourceTypeOrderByPublishedAtDesc(sourceType).map(this::toEntry);
  }

  public List<AiCommentEntry> latestBySourceType(String sourceType, int limit) {
    return repository.findTop20BySourceTypeOrderByPublishedAtDesc(sourceType).stream()
        .limit(Math.max(0, Math.min(limit, 20)))
        .map(this::toEntry)
        .toList();
  }

  public List<AiCommentEntry> diaries() {
    return repository.findTop100ByDiaryDateIsNotNullOrderByDiaryDateDescPublishedAtDesc().stream()
        .map(this::toEntry)
        .toList();
  }

  public Optional<AiCommentEntry> findByDiaryDate(LocalDate diaryDate) {
    return repository.findByDiaryDate(diaryDate).map(this::toEntry);
  }

  public boolean editorEnabled() {
    return editorKey != null && !editorKey.isBlank();
  }

  @Transactional
  public AiCommentEntry publish(
      LocalDate diaryDate, String title, String body, String submittedEditorKey) {
    String normalizedTitle = normalize(title);
    String normalizedBody = normalize(body);
    if (!editorEnabled()) {
      throw new IllegalArgumentException("AIコメント編集機能が設定されていません。");
    }
    if (normalizedTitle.isBlank() || normalizedTitle.length() > 120) {
      throw new IllegalArgumentException("タイトルは1〜120文字で入力してください。");
    }
    if (normalizedBody.isBlank() || normalizedBody.length() > 4000) {
      throw new IllegalArgumentException("本文は1〜4000文字で入力してください。");
    }
    if (!editorKey.equals(submittedEditorKey)) {
      throw new IllegalArgumentException("編集キーが正しくありません。");
    }
    T_AiComment comment = diaryDate == null
        ? new T_AiComment()
        : repository.findByDiaryDate(diaryDate).orElseGet(T_AiComment::new);
    comment.setTitle(normalizedTitle);
    comment.setBody(normalizedBody);
    comment.setDiaryDate(diaryDate);
    comment.setPublishedAt(OffsetDateTime.now(ZoneOffset.UTC));
    comment.setSourceType("MANUAL_BETA");
    return publishToTimeline(repository.save(comment));
  }

  @Transactional
  public AiCommentEntry publishGenerated(String title, String body, String sourceType) {
    return publishGenerated(title, body, sourceType, AiPostType.NORMAL, null);
  }

  @Transactional
  public AiCommentEntry publishGenerated(
      String title, String body, String sourceType, AiPostType postType, Long targetPlayerId) {
    String normalizedTitle = normalize(title);
    String normalizedBody = normalize(body);
    String normalizedSource = normalize(sourceType);
    if (normalizedTitle.isBlank() || normalizedTitle.length() > 120) {
      throw new IllegalArgumentException("タイトルは1〜120文字で入力してください。");
    }
    if (normalizedBody.isBlank() || normalizedBody.length() > 4000) {
      throw new IllegalArgumentException("本文は1〜4000文字で入力してください。");
    }
    if (normalizedSource.isBlank() || normalizedSource.length() > 30) {
      throw new IllegalArgumentException("生成元は1〜30文字で指定してください。");
    }
    T_AiComment comment = new T_AiComment();
    comment.setTitle(normalizedTitle);
    comment.setBody(normalizedBody);
    comment.setPublishedAt(OffsetDateTime.now(ZoneOffset.UTC));
    comment.setSourceType(normalizedSource);
    comment.setPostType(postType.name());
    comment.setTargetPlayerId(targetPlayerId);
    comment.setAiGenerated(true);
    return publishToTimeline(repository.save(comment));
  }

  public long generatedTimelineCountSince(OffsetDateTime from) {
    return repository.countByAiGeneratedTrueAndDiaryDateIsNullAndPublishedAtGreaterThanEqual(from);
  }

  public boolean hasPostTypeSince(AiPostType postType, OffsetDateTime from) {
    return repository.existsByPostTypeAndPublishedAtGreaterThanEqual(postType.name(), from);
  }

  @Transactional
  public AiCommentEntry publishGeneratedDiary(
      LocalDate diaryDate,
      String title,
      String summary,
      List<String> tags,
      String body) {
    if (diaryDate == null) {
      throw new IllegalArgumentException("日記の日付が必要です。");
    }
    String normalizedTitle = normalize(title);
    String normalizedSummary = normalize(summary);
    String normalizedBody = normalize(body);
    String normalizedTags = tags == null ? "" : tags.stream()
        .map(this::normalize)
        .filter(tag -> !tag.isBlank())
        .distinct()
        .limit(8)
        .collect(java.util.stream.Collectors.joining(","));
    if (normalizedTitle.isBlank() || normalizedTitle.length() > 120) {
      throw new IllegalArgumentException("タイトルは1〜120文字で指定してください。");
    }
    if (normalizedSummary.isBlank() || normalizedSummary.length() > 500) {
      throw new IllegalArgumentException("要約は1〜500文字で指定してください。");
    }
    if (normalizedTags.isBlank() || normalizedTags.length() > 500) {
      throw new IllegalArgumentException("タグを1つ以上指定してください。");
    }
    if (normalizedBody.isBlank() || normalizedBody.length() > 4000) {
      throw new IllegalArgumentException("本文は1〜4000文字で指定してください。");
    }
    T_AiComment comment = repository.findByDiaryDate(diaryDate).orElseGet(T_AiComment::new);
    comment.setDiaryDate(diaryDate);
    comment.setTitle(normalizedTitle);
    comment.setSummary(normalizedSummary);
    comment.setTags(normalizedTags);
    comment.setBody(normalizedBody);
    comment.setPublishedAt(OffsetDateTime.now(ZoneOffset.UTC));
    comment.setSourceType("AWS_BEDROCK_DIARY");
    comment.setAiGenerated(true);
    return publishToTimeline(repository.save(comment));
  }

  private String normalize(String value) {
    return value == null ? "" : value.strip();
  }

  private AiCommentEntry publishToTimeline(T_AiComment comment) {
    AiCommentEntry entry = toEntry(comment);
    if (entry.diaryDate() == null) {
      timelinePostService.publishWatchpoint(entry.id(), entry.targetPlayerId(), entry.publishedAt(), entry.body());
    }
    return entry;
  }

  private AiCommentEntry toEntry(T_AiComment comment) {
    return new AiCommentEntry(
        comment.getId(), comment.getDiaryDate(), comment.getTitle(), comment.getBody(),
        comment.getPublishedAt(), comment.getSourceType(), comment.getSummary(),
        splitTags(comment.getTags()), parsePostType(comment.getPostType()),
        comment.getTargetPlayerId(), comment.isAiGenerated());
  }

  private AiPostType parsePostType(String value) {
    try {
      return AiPostType.valueOf(value == null ? AiPostType.NORMAL.name() : value);
    } catch (IllegalArgumentException ignored) {
      return AiPostType.NORMAL;
    }
  }

  private List<String> splitTags(String tags) {
    return tags == null || tags.isBlank() ? List.of() : List.of(tags.split(","));
  }

  public record AiCommentEntry(
      Long id,
      LocalDate diaryDate,
      String title,
      String body,
      OffsetDateTime publishedAt,
      String sourceType,
      String summary,
      List<String> tags,
      AiPostType postType,
      Long targetPlayerId,
      boolean aiGenerated) {
    public AiCommentEntry(
        Long id, LocalDate diaryDate, String title, String body, OffsetDateTime publishedAt,
        String sourceType, String summary, List<String> tags) {
      this(id, diaryDate, title, body, publishedAt, sourceType, summary, tags,
          AiPostType.NORMAL, null, sourceType != null && sourceType.startsWith("AWS_BEDROCK"));
    }
  }
}
