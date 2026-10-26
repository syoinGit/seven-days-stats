-- Hard limits on every Bedrock call (AiCallBudget), so a bug or a loop can't run up the bill. One row per Tokyo day
-- ('D:2026-10-26') and per Tokyo hour ('H:2026-10-26T20'): calls made (including failed ones), the tokens the
-- replies reported, and calls refused for being over the limit. Only counts; no prompt or reply text.
CREATE TABLE T_AI_CALL_BUDGET (
  period_key VARCHAR(20) PRIMARY KEY,
  calls INTEGER NOT NULL DEFAULT 0,
  input_tokens BIGINT NOT NULL DEFAULT 0,
  output_tokens BIGINT NOT NULL DEFAULT 0,
  refused INTEGER NOT NULL DEFAULT 0,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
