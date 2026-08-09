package com.yuki.sevendays_states.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yuki.sevendays_states.config.SurvivorKarenProperties;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import tools.jackson.databind.ObjectMapper;

class ImageGenerationServiceTests {

  @Test
  void invokesNovaCanvasAndUploadsThePngToS3() {
    BedrockRuntimeClient bedrock = mock(BedrockRuntimeClient.class);
    S3Client s3 = mock(S3Client.class);
    SurvivorKarenProperties properties = new SurvivorKarenProperties(
        true, true, true, 3, 12, "us-east-1", "amazon.nova-canvas-v1:0",
        "watchpoint-images", "watchpoint/posts/survivor-karen", "https://cdn.example.com/media");
    ImageGenerationService service = new ImageGenerationService(
        bedrock, s3, properties, new ObjectMapper());
    when(bedrock.invokeModel(any(InvokeModelRequest.class))).thenReturn(
        InvokeModelResponse.builder()
            .body(SdkBytes.fromUtf8String("{\"images\":[\"aGVsbG8=\"]}"))
            .build());
    when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
        .thenReturn(PutObjectResponse.builder().build());

    String url = service.generateAndStore(
        "game screenshot", "no text", "watchpoint/posts/survivor-karen/2026-08-09.png", 42);

    assertThat(url).isEqualTo(
        "https://cdn.example.com/media/watchpoint/posts/survivor-karen/2026-08-09.png");
    ArgumentCaptor<InvokeModelRequest> request = ArgumentCaptor.forClass(InvokeModelRequest.class);
    verify(bedrock).invokeModel(request.capture());
    assertThat(request.getValue().body().asString(StandardCharsets.UTF_8))
        .contains("TEXT_IMAGE", "game screenshot", "negativeText", "1024");
    ArgumentCaptor<PutObjectRequest> put = ArgumentCaptor.forClass(PutObjectRequest.class);
    verify(s3).putObject(put.capture(), any(RequestBody.class));
    assertThat(put.getValue().bucket()).isEqualTo("watchpoint-images");
    assertThat(put.getValue().contentType()).isEqualTo("image/png");
  }
}
