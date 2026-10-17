package com.yuki.rubinco.watchpoint.readmodel;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:diary_view;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.enabled=true",
    "app.sevendays.import.startup-enabled=false"
})
class DiaryViewServiceTests {

  @Autowired
  private DiaryViewService service;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void cleanUp() {
    jdbcTemplate.update("delete from t_ai_comment");
  }

  @Test
  void archiveUsesOnlyDatedDatabaseEntriesInNewestFirstOrder() {
    insert(null, "旧コメント", "公開日記ではないコメント");
    insert(LocalDate.of(2026, 8, 1), "一日目", "短い本文");
    insert(LocalDate.of(2026, 8, 2), "二日目", "長い本文 ".repeat(50));

    var archive = service.archive();

    assertThat(archive).hasSize(2);
    assertThat(archive).extracting(DiaryViewService.DiaryCard::date)
        .containsExactly(LocalDate.of(2026, 8, 2), LocalDate.of(2026, 8, 1));
    assertThat(archive.getFirst().excerpt()).endsWith("…");
    assertThat(archive.getFirst().excerpt().length()).isLessThanOrEqualTo(181);
  }

  @Test
  void detailReturnsFullDatabaseBodyForRequestedDate() {
    String body = "朝、病院へ向かった。\n夜、生存者は拠点へ帰還した。";
    insert(LocalDate.of(2026, 8, 2), "病院探索の日", body);

    var detail = service.detail(LocalDate.of(2026, 8, 2));

    assertThat(detail).isPresent();
    assertThat(detail.orElseThrow().body()).isEqualTo(body);
    assertThat(service.detail(LocalDate.of(2026, 8, 3))).isEmpty();
  }

  @Test
  void archiveUsesStoredSummaryAndTagsWhenAvailable() {
    LocalDate date = LocalDate.of(2026, 8, 4);
    jdbcTemplate.update("""
        insert into t_ai_comment
          (diary_date, title, body, summary, tags, published_at, source_type)
        values (?, '遠征', '長い本文', '短い要約', '探索,遠征', ?, 'AWS_BEDROCK_DIARY')
        """, date, OffsetDateTime.now(ZoneOffset.UTC));

    var entry = service.archive().getFirst();

    assertThat(entry.excerpt()).isEqualTo("短い要約");
    assertThat(entry.tags()).containsExactly("探索", "遠征");
  }

  private void insert(LocalDate date, String title, String body) {
    jdbcTemplate.update("""
        insert into t_ai_comment (diary_date, title, body, published_at, source_type)
        values (?, ?, ?, ?, 'MANUAL_BETA')
        """, date, title, body, OffsetDateTime.now(ZoneOffset.UTC));
  }
}
