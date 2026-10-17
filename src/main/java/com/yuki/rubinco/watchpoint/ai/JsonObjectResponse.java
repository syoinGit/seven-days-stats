package com.yuki.rubinco.watchpoint.ai;

/** Extracts a complete object from model text without treating quoted braces as delimiters. */
final class JsonObjectResponse {

  private JsonObjectResponse() {
  }

  /**
   * Claude may occasionally wrap an otherwise valid JSON response in a code fence or a short
   * introductory sentence. Extract only the first complete JSON object; normal validation still
   * rejects missing fields and fabricated evidence keys afterwards.
   */
  static String extract(String responseText) {
    int start = responseText.indexOf('{');
    if (start < 0) {
      throw new IllegalArgumentException("Bedrockの応答にJSONオブジェクトがありません。");
    }
    int depth = 0;
    boolean inString = false;
    boolean escaped = false;
    for (int index = start; index < responseText.length(); index++) {
      char character = responseText.charAt(index);
      if (inString) {
        if (escaped) {
          escaped = false;
        } else if (character == '\\') {
          escaped = true;
        } else if (character == '"') {
          inString = false;
        }
        continue;
      }
      if (character == '"') {
        inString = true;
      } else if (character == '{') {
        depth++;
      } else if (character == '}' && --depth == 0) {
        return responseText.substring(start, index + 1);
      }
    }
    throw new IllegalArgumentException("Bedrockの応答に完全なJSONオブジェクトがありません。");
  }

}
