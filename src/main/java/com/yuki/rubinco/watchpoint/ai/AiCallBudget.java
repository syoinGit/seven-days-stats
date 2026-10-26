package com.yuki.rubinco.watchpoint.ai;

import com.yuki.rubinco.watchpoint.ai.config.AiBudgetProperties;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Counts every Bedrock call in the database and refuses one that would go over the day's or the hour's limit (calls,
 * and tokens the day's replies already used). A call is counted before it is made, in its own transaction, so
 * parallel callers and restarts can't slip past the limit, and a failed call still counts.
 */
@Service
@Slf4j
public class AiCallBudget {
  public static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");

  private final JdbcTemplate jdbc;
  private final AiBudgetProperties limits;
  private final TransactionTemplate transactions;

  public AiCallBudget(JdbcTemplate jdbc, AiBudgetProperties limits, TransactionTemplate transactions) {
    this.jdbc = jdbc;
    this.limits = limits;
    // Its own transaction: a caller's rollback must not undo the count of a call that was made.
    this.transactions = new TransactionTemplate(transactions.getTransactionManager());
    this.transactions.setPropagationBehavior(Propagation.REQUIRES_NEW.value());
  }

  public static class ExceededException extends RuntimeException {
    ExceededException(String reason) {
      super(reason);
    }
  }

  /** Today's use so far, for the maintenance page. */
  public record Usage(int calls, long inputTokens, long outputTokens, int refused, int hourCalls, AiBudgetProperties limits) { }

  /** maxTokens held at the per-call limit. */
  public int cap(int maxTokens) {
    return Math.min(maxTokens, limits.maxTokensPerCall());
  }

  /** Counts one call now, or throws ExceededException (and counts the refusal) when it would go over a limit. */
  public void reserve(ZonedDateTime now) {
    String day = day(now);
    String hour = hour(now);
    ensure(day);
    ensure(hour);
    String refusal = transactions.execute(status -> {
      // Locks both rows, so two callers can't both take the last call.
      var used = jdbc.queryForMap("SELECT calls, input_tokens, output_tokens FROM T_AI_CALL_BUDGET WHERE period_key = ? FOR UPDATE", day);
      int hourCalls = jdbc.queryForObject("SELECT calls FROM T_AI_CALL_BUDGET WHERE period_key = ? FOR UPDATE", Integer.class, hour);
      String reason = ((Number) used.get("calls")).intValue() >= limits.dailyCalls() ? "1日の呼び出し上限（" + limits.dailyCalls() + "回）"
          : hourCalls >= limits.hourlyCalls() ? "1時間の呼び出し上限（" + limits.hourlyCalls() + "回）"
          : ((Number) used.get("input_tokens")).longValue() >= limits.dailyInputTokens() ? "1日の入力トークン上限（" + limits.dailyInputTokens() + "）"
          : ((Number) used.get("output_tokens")).longValue() >= limits.dailyOutputTokens() ? "1日の出力トークン上限（" + limits.dailyOutputTokens() + "）"
          : null;
      if (reason != null) {
        jdbc.update("UPDATE T_AI_CALL_BUDGET SET refused = refused + 1, updated_at = CURRENT_TIMESTAMP WHERE period_key = ?", day);
        return reason;
      }
      jdbc.update("UPDATE T_AI_CALL_BUDGET SET calls = calls + 1, updated_at = CURRENT_TIMESTAMP WHERE period_key IN (?, ?)", day, hour);
      return null;
    });
    if (refusal != null) {
      log.warn("Bedrock call refused: over {}", refusal);
      throw new ExceededException("AIの呼び出しを止めました：" + refusal + "に達しています。");
    }
  }

  /** Adds the tokens a reply reported to the day it was reserved in. */
  public void spent(ZonedDateTime now, long inputTokens, long outputTokens) {
    transactions.executeWithoutResult(status -> jdbc.update(
        "UPDATE T_AI_CALL_BUDGET SET input_tokens = input_tokens + ?, output_tokens = output_tokens + ?, updated_at = CURRENT_TIMESTAMP "
            + "WHERE period_key IN (?, ?)", Math.max(0, inputTokens), Math.max(0, outputTokens), day(now), hour(now)));
  }

  public Usage today(ZonedDateTime now) {
    var day = jdbc.queryForList("SELECT calls, input_tokens, output_tokens, refused FROM T_AI_CALL_BUDGET WHERE period_key = ?", day(now));
    var hour = jdbc.queryForList("SELECT calls FROM T_AI_CALL_BUDGET WHERE period_key = ?", Integer.class, hour(now));
    if (day.isEmpty()) return new Usage(0, 0, 0, 0, hour.isEmpty() ? 0 : hour.getFirst(), limits);
    var row = day.getFirst();
    return new Usage(((Number) row.get("calls")).intValue(), ((Number) row.get("input_tokens")).longValue(),
        ((Number) row.get("output_tokens")).longValue(), ((Number) row.get("refused")).intValue(), hour.isEmpty() ? 0 : hour.getFirst(), limits);
  }

  /** Makes the period's row in a transaction of its own; losing the race to another caller is fine. */
  private void ensure(String key) {
    if (jdbc.queryForObject("SELECT COUNT(*) FROM T_AI_CALL_BUDGET WHERE period_key = ?", Integer.class, key) > 0) return;
    try {
      transactions.executeWithoutResult(status -> jdbc.update("INSERT INTO T_AI_CALL_BUDGET (period_key) VALUES (?)", key));
    } catch (DuplicateKeyException raced) {
      // Another caller made it first.
    }
  }

  private static String day(ZonedDateTime now) {
    return "D:" + now.withZoneSameInstant(TOKYO).toLocalDate();
  }

  private static String hour(ZonedDateTime now) {
    var local = now.withZoneSameInstant(TOKYO);
    return "H:" + local.toLocalDate() + "T" + String.format("%02d", local.getHour());
  }
}
