package com.yuki.rubinco.watchpoint.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yuki.rubinco.watchpoint.ai.config.AiBudgetProperties;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;

/** Every Bedrock call goes through the budget: over a limit, Bedrock is not called at all. */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:aibudget;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.enabled=true", "app.sevendays.import.startup-enabled=false",
    "app.ai.budget.daily-calls=5", "app.ai.budget.hourly-calls=3", "app.ai.budget.daily-output-tokens=1000",
    "app.ai.budget.max-tokens-per-call=500"
})
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
class AiCallBudgetTests {
  static final ZonedDateTime NOON = ZonedDateTime.of(2026, 10, 26, 12, 0, 0, 0, AiCallBudget.TOKYO);

  @Autowired AiCallBudget budget;
  @Autowired BedrockTextClient client;
  @Autowired JdbcTemplate jdbc;
  @Autowired TransactionTemplate transactions;
  @MockitoBean BedrockRuntimeClient runtime;
  @Autowired org.springframework.test.web.servlet.MockMvc mvc;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM T_AI_CALL_BUDGET");
  }

  @Test void callsStopAtTheHourAndTheDay() {
    for (int i = 0; i < 3; i++) budget.reserve(NOON.plusMinutes(i));
    assertThatThrownBy(() -> budget.reserve(NOON.plusMinutes(30))).isInstanceOf(AiCallBudget.ExceededException.class)
        .hasMessageContaining("1時間の呼び出し上限（3回）");
    // The next hour has room, until the day's five are used.
    budget.reserve(NOON.plusHours(1));
    budget.reserve(NOON.plusHours(1).plusMinutes(1));
    assertThatThrownBy(() -> budget.reserve(NOON.plusHours(2))).hasMessageContaining("1日の呼び出し上限（5回）");
    var usage = budget.today(NOON.plusHours(2));
    assertThat(usage.calls()).isEqualTo(5);
    assertThat(usage.refused()).isEqualTo(2);
    // A new Tokyo day starts again.
    budget.reserve(NOON.plusDays(1));
  }

  @Test void tokensStopTheDayAndEachCallIsCapped() {
    budget.reserve(NOON);
    budget.spent(NOON, 900, 1000);
    assertThatThrownBy(() -> budget.reserve(NOON.plusHours(1))).hasMessageContaining("1日の出力トークン上限（1000）");
    assertThat(budget.cap(2000)).isEqualTo(500);
    assertThat(budget.cap(300)).isEqualTo(300);
  }

  /** A caller's rollback doesn't undo the count of a call that was made. */
  @Test void theCountSurvivesTheCallersRollback() {
    transactions.executeWithoutResult(status -> {
      budget.reserve(NOON);
      status.setRollbackOnly();
    });
    assertThat(budget.today(NOON).calls()).isEqualTo(1);
  }

  @Test void parallelCallersCantTakeMoreThanTheLimit() throws Exception {
    List<Callable<Boolean>> calls = new ArrayList<>();
    for (int i = 0; i < 8; i++) calls.add(() -> {
      try {
        budget.reserve(NOON);
        return true;
      } catch (AiCallBudget.ExceededException over) {
        return false;
      }
    });
    int made = 0;
    try (var executor = Executors.newFixedThreadPool(8)) {
      for (var result : executor.invokeAll(calls)) if (result.get()) made++;
    }
    assertThat(made).isEqualTo(3);
    assertThat(budget.today(NOON).calls()).isEqualTo(3);
  }

  @Test void theClientCountsTokensAndDoesntCallBedrockOverTheLimit() {
    when(runtime.invokeModel(any(InvokeModelRequest.class))).thenReturn(InvokeModelResponse.builder().body(SdkBytes.fromUtf8String(
        "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"usage\":{\"input_tokens\":120,\"output_tokens\":30}}")).build());
    assertThat(client.generate("system", "prompt", 4000)).isEqualTo("ok");
    var sent = org.mockito.ArgumentCaptor.forClass(InvokeModelRequest.class);
    verify(runtime).invokeModel(sent.capture());
    assertThat(sent.getValue().body().asUtf8String()).contains("\"max_tokens\":500");
    var usage = budget.today(ZonedDateTime.now(AiCallBudget.TOKYO));
    assertThat(usage.calls()).isEqualTo(1);
    assertThat(usage.inputTokens()).isEqualTo(120);
    assertThat(usage.outputTokens()).isEqualTo(30);
    client.generate("system", "prompt", 100);
    client.generate("system", "prompt", 100);
    // Three this hour (unless the hour turned between the calls): the fourth never reaches Bedrock.
    if (budget.today(ZonedDateTime.now(AiCallBudget.TOKYO)).hourCalls() == 3) {
      assertThatThrownBy(() -> client.generate("system", "prompt", 100)).isInstanceOf(AiCallBudget.ExceededException.class);
      verify(runtime, times(3)).invokeModel(any(InvokeModelRequest.class));
    }
  }

  @Test void administratorsSeeTodaysUse() throws Exception {
    budget.reserve(ZonedDateTime.now(AiCallBudget.TOKYO));
    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/maintenance/ai-analysis/test")
            .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("admin").roles("ADMIN")))
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("今日のAI呼び出し（全機能）")))
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("1 / 5")));
  }

  @Test void limitsCantBeRaisedPastTheCeilings() {
    var limits = new AiBudgetProperties(100_000, 100_000, 100_000_000L, 100_000_000L, 100_000);
    assertThat(limits.dailyCalls()).isEqualTo(200);
    assertThat(limits.hourlyCalls()).isEqualTo(60);
    assertThat(limits.dailyInputTokens()).isEqualTo(1_000_000);
    assertThat(limits.dailyOutputTokens()).isEqualTo(300_000);
    assertThat(limits.maxTokensPerCall()).isEqualTo(4000);
    var defaults = new AiBudgetProperties(0, 0, 0, 0, 0);
    assertThat(defaults.dailyCalls()).isEqualTo(50);
    assertThat(defaults.hourlyCalls()).isEqualTo(20);
    assertThat(defaults.maxTokensPerCall()).isEqualTo(2000);
    verify(runtime, never()).invokeModel(any(InvokeModelRequest.class));
  }
}
