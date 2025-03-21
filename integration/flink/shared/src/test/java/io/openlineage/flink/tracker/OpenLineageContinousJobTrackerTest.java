/*
/* Copyright 2018-2025 contributors to the OpenLineage project
/* SPDX-License-Identifier: Apache-2.0
*/

package io.openlineage.flink.tracker;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.configureFor;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.openlineage.flink.api.OpenLineageContext;
import io.openlineage.flink.api.OpenLineageContext.JobIdentifier;
import io.openlineage.flink.client.CheckpointFacet;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import lombok.SneakyThrows;
import org.apache.flink.api.common.JobID;
import org.apache.flink.configuration.ReadableConfig;
import org.apache.flink.configuration.RestOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OpenLineageContinousJobTrackerTest {

  private static final String CHECKPOINTS = "checkpoints";
  private static final String SECOND_CHECKPOINT = "second checkpoint";
  private static final String CHECKPOINTS_URL = "/jobs/%s/checkpoints";

  WireMockServer wireMockServer = new WireMockServer(18088);
  ReadableConfig config = mock(ReadableConfig.class);
  Consumer<CheckpointFacet> onJobCheckpoint = mock(Consumer.class);
  OpenLineageContinousJobTracker tracker =
      new OpenLineageContinousJobTracker(Duration.ofMillis(100), "http://localhost:18088/jobs");
  OpenLineageContext openLineageContext = mock(OpenLineageContext.class);
  MeterRegistry meterRegistry;
  JobID jobID = new JobID(1, 2);
  CheckpointFacet expectedCheckpointFacet = new CheckpointFacet(1, 5, 6, 7, 1);

  String jsonCheckpointResponse =
      "{\"counts\":"
          + "{"
          + "\"completed\":%d,"
          + "\"failed\":5,"
          + "\"in_progress\":6,"
          + "\"restored\":7,"
          + "\"total\":%d"
          + "}"
          + "}";

  @BeforeEach
  public void setup() {
    wireMockServer.start();
    configureFor("localhost", 18088);
    meterRegistry = new SimpleMeterRegistry();
    when(openLineageContext.getJobId())
        .thenReturn(JobIdentifier.builder().flinkJobId(jobID).build());
    when(openLineageContext.getMeterRegistry()).thenReturn(meterRegistry);
    when(config.get(RestOptions.ADDRESS)).thenReturn("localhost");
    when(config.get(RestOptions.PORT)).thenReturn(18088);
  }

  @AfterEach
  public void stop() {
    wireMockServer.stop();
  }

  @Test
  @SneakyThrows
  void testStartTrackingEventsEmitted() {
    stubFor(
        get(urlEqualTo(String.format(CHECKPOINTS_URL, jobID.toString())))
            .inScenario(CHECKPOINTS)
            .whenScenarioStateIs(STARTED)
            .willReturn(aResponse().withBody(String.format(jsonCheckpointResponse, 0, 0)))
            .willSetStateTo(SECOND_CHECKPOINT));

    stubFor(
        get(urlEqualTo(String.format(CHECKPOINTS_URL, jobID.toString())))
            .inScenario(CHECKPOINTS)
            .whenScenarioStateIs(SECOND_CHECKPOINT)
            .willReturn(aResponse().withBody(String.format(jsonCheckpointResponse, 1, 1))));

    CountDownLatch methodDone = new CountDownLatch(2);
    doAnswer(
            invocation -> {
              methodDone.countDown();
              return null;
            })
        .when(onJobCheckpoint)
        .accept(any());

    tracker.startTracking(openLineageContext, onJobCheckpoint);
    methodDone.await(10, TimeUnit.SECONDS);

    verify(onJobCheckpoint, times(1)).accept(eq(expectedCheckpointFacet));
    tracker.stopTracking();
  }

  @Test
  @SneakyThrows
  void testTrackerContinuesToWorkWhenRestApiGoesDownForSomeTime() {
    stubFor(
        get(urlEqualTo(String.format(CHECKPOINTS_URL, jobID.toString())))
            .inScenario(CHECKPOINTS)
            .whenScenarioStateIs(STARTED)
            .willReturn(aResponse().withBody(String.format(jsonCheckpointResponse, 0, 0)))
            .willSetStateTo("api goes down"));

    stubFor(
        get(urlEqualTo(String.format(CHECKPOINTS_URL, jobID.toString())))
            .inScenario(CHECKPOINTS)
            .whenScenarioStateIs("api goes down")
            .willReturn(aResponse().withStatus(403))
            .willSetStateTo(SECOND_CHECKPOINT));

    stubFor(
        get(urlEqualTo(String.format(CHECKPOINTS_URL, jobID.toString())))
            .inScenario(CHECKPOINTS)
            .whenScenarioStateIs(SECOND_CHECKPOINT)
            .willReturn(aResponse().withBody(String.format(jsonCheckpointResponse, 1, 1))));

    CountDownLatch methodDone = new CountDownLatch(2);
    doAnswer(
            invocation -> {
              methodDone.countDown();
              return null;
            })
        .when(onJobCheckpoint)
        .accept(any());

    tracker.startTracking(openLineageContext, onJobCheckpoint);
    methodDone.await(10, TimeUnit.SECONDS);

    verify(onJobCheckpoint, times(1)).accept(eq(expectedCheckpointFacet));
    tracker.stopTracking();
  }
}
