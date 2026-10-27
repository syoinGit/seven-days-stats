package com.yuki.rubinco.guild;

import com.yuki.rubinco.watchpoint.ai.AiCallBudget;

/**
 * The AI could not write a scene, talk or dungeon run. call: the Bedrock call itself failed (otherwise its output was
 * refused). The message is the refusal's reason, or the failure's class name (never text from the model or the request).
 */
public class GuildWritingException extends RuntimeException {
  private final boolean call;

  GuildWritingException(boolean call, RuntimeException cause) {
    super(cause instanceof GuildInteractionWriter.Refused ? cause.getMessage()
        : cause instanceof AiCallBudget.ExceededException ? "AI呼び出しの上限に達した"
        : cause.getClass().getSimpleName(), cause);
    this.call = call;
  }

  public boolean call() {
    return call;
  }
}
