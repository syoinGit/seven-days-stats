package com.yuki.sevendays_states.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class KarenImagePromptGenerator {

  private static final String STYLE = """
      realistic 3D zombie survival game screenshot, actual in-game third-person screenshot, no HUD,
      no UI, no captions, no social media interface, no text overlay, no watermark, realistic game
      character rendering, post-apocalyptic American environment, natural cinematic lighting, dirty
      dusty survivor aesthetic, not anime, not illustration, not movie poster, not overly photorealistic
      """.replace('\n', ' ').strip();

  private static final String APPEARANCE = """
      the same young adult woman named Karen in every image, blonde hair in a high ponytail, red bandana, goggles on her
      head, lightly tanned skin, athletic build, dirt and small scratches, rugged desert survivor outfit,
      practical tank top or cropped survival top, cargo or denim shorts, fingerless gloves, boots, small
      backpack and utility gear, subtle hoop earrings and necklace, attractive and clearly capable
      """.replace('\n', ' ').strip();

  private final AiAgentProfileService agentProfileService;

  private static final String NEGATIVE = """
      anime, illustration, painting, movie poster, fashion studio, glossy advertisement, HUD, user interface,
      captions, text overlay, watermark, logo overlay, extra fingers, malformed hands, duplicate person,
      different hair color, loose hair, explicit nudity, lingerie, fetish clothing, sexual pose
      """.replace('\n', ' ').strip();

  public ImagePrompt prompt(KarenPostGenerator.KarenPost post) {
    return new ImagePrompt(
        STYLE + ", character personality: "
            + agentProfileService.personalityPrompt(AiAgentProfileService.KAREN)
            + ", appearance: " + APPEARANCE + ", scene: " + post.imageScene(),
        NEGATIVE);
  }

  public record ImagePrompt(String text, String negativeText) { }
}
