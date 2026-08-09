package com.yuki.sevendays_states.service;

import org.springframework.stereotype.Service;

@Service
public class MarkImagePromptGenerator {

  private static final String STYLE = """
      realistic 3D zombie survival game screenshot, in-game third-person exploration record, no HUD,
      no UI, no captions, no text overlay, no watermark, practical post-apocalyptic American setting,
      natural cinematic lighting, dirty survival equipment, not anime, not illustration, not movie poster
      """.replace('\n', ' ').strip();
  private static final String MARK = """
      same adult western male survivor named Mark in every image, late 30s or early 40s, short dark hair
      or a worn cap, light beard or stubble, rugged jacket or flannel shirt, tactical backpack, gloves,
      weathered and dirty, practical survival equipment, rifle or flashlight optional, no selfie pose
      """.replace('\n', ' ').strip();
  private static final String NEGATIVE = """
      anime, illustration, painting, movie poster, fashion studio, HUD, user interface, captions,
      text overlay, watermark, logo overlay, selfie, glamour pose, extra fingers, malformed hands,
      duplicate person, explicit nudity, sexualized clothing
      """.replace('\n', ' ').strip();

  public ImagePrompt prompt(MarkPostGenerator.MarkPost post) {
    return new ImagePrompt(STYLE + ", " + MARK + ", scene: " + post.imageScene(), NEGATIVE);
  }

  public record ImagePrompt(String text, String negativeText) { }
}
