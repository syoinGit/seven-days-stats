package com.yuki.rubinco.watchpoint.web;

import com.yuki.rubinco.watchpoint.ai.WatchpointAiPublishingService;
import com.yuki.rubinco.watchpoint.readmodel.WatchpointAiObservationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/maintenance/ai-analysis")
@RequiredArgsConstructor
public class AiAnalysisController {

  private final WatchpointAiObservationService observationService;
  private final WatchpointAiPublishingService publishingService;

  @GetMapping(value = "/payload", produces = MediaType.APPLICATION_JSON_VALUE)
  public WatchpointAiObservationService.AnalysisRequest payload() {
    return observationService.buildRequest();
  }

  @PostMapping(value = "/publish", produces = MediaType.APPLICATION_JSON_VALUE)
  public WatchpointAiPublishingService.PublishResult publish() {
    return publishingService.publishNow();
  }
}
