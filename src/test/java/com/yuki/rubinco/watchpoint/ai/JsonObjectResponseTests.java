package com.yuki.rubinco.watchpoint.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class JsonObjectResponseTests {

  @Test
  void handlesNestedObjectsEscapedQuotesAndBracesInsideStrings() {
    String json = "{\"body\":\"{quoted \\\"text\\\"}\\\\\",\"nested\":{\"values\":[1,2]}}";
    assertThat(JsonObjectResponse.extract("```json\n" + json + "\n``` trailing {ignored}"))
        .isEqualTo(json);
  }

  @Test
  void rejectsMissingAndIncompleteObjects() {
    assertThatThrownBy(() -> JsonObjectResponse.extract("No JSON"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> JsonObjectResponse.extract("{\"body\":\"unterminated}"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
