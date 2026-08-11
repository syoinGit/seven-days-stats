package com.yuki.sevendays_states.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.yuki.sevendays_states.config.SevenDaysDataProperties;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class DockerLogStreamingServiceTests {

  @Test
  void dockerModeIsTheOnlyStreamingGate() {
    SevenDaysDataProperties properties = mock(SevenDaysDataProperties.class);
    DockerLogStreamingService service = new DockerLogStreamingService(
        properties, mock(GameLogImportService.class));

    when(properties.mode()).thenReturn("file");
    assertThat((Boolean) ReflectionTestUtils.invokeMethod(service, "shouldStart")).isFalse();

    when(properties.mode()).thenReturn("docker");
    assertThat((Boolean) ReflectionTestUtils.invokeMethod(service, "shouldStart")).isTrue();
  }
}
