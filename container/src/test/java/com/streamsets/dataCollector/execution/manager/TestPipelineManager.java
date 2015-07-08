/**
 * (c) 2015 StreamSets, Inc. All rights reserved. May not
 * be copied, modified, or distributed in whole or part without
 * written consent of StreamSets, Inc.
 */
package com.streamsets.dataCollector.execution.manager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.codahale.metrics.MetricRegistry;
import com.streamsets.dataCollector.execution.Manager;
import com.streamsets.dataCollector.execution.PipelineState;
import com.streamsets.dataCollector.execution.PipelineStateStore;
import com.streamsets.dataCollector.execution.PipelineStatus;
import com.streamsets.dataCollector.execution.Previewer;
import com.streamsets.dataCollector.execution.Runner;
import com.streamsets.dataCollector.execution.store.CachePipelineStateStore;
import com.streamsets.dataCollector.execution.store.FilePipelineStateStore;
import com.streamsets.dataCollector.execution.store.TestPipelineStateStore;
import com.streamsets.pipeline.api.ExecutionMode;
import com.streamsets.pipeline.lib.executor.SafeScheduledExecutorService;
import com.streamsets.pipeline.main.RuntimeInfo;
import com.streamsets.pipeline.main.RuntimeModule;
import com.streamsets.pipeline.runner.MockStages;
import com.streamsets.pipeline.stagelibrary.StageLibraryTask;
import com.streamsets.pipeline.store.PipelineStoreTask;
import com.streamsets.pipeline.store.impl.CachePipelineStoreTask;
import com.streamsets.pipeline.store.impl.FilePipelineStoreTask;
import com.streamsets.pipeline.util.Configuration;

public class TestPipelineManager {

  private PipelineStoreTask pipelineStoreTask;
  private URLClassLoader emptyCL;
  private RuntimeInfo runtimeInfo;
  private StageLibraryTask stageLibraryTask;
  private Manager pipelineManager;
  private PipelineStateStore pipelineStateStore;
  private final Configuration configuration = new Configuration();
  private SafeScheduledExecutorService scheduledExecutor;

  private void setUpManager(long expiryTime) {
    emptyCL = new URLClassLoader(new URL[0]);
    runtimeInfo =
      new RuntimeInfo(RuntimeModule.SDC_PROPERTY_PREFIX, new MetricRegistry(),
        Arrays.asList(TestPipelineStateStore.class.getClassLoader()));
    stageLibraryTask = MockStages.createStageLibrary(emptyCL);
    configuration.set(PipelineManager.RUNNER_EXPIRY_INTERVAL, expiryTime);
    pipelineStateStore = new CachePipelineStateStore(new FilePipelineStateStore(runtimeInfo, configuration));
    pipelineStateStore.init();
    pipelineStoreTask =
      new CachePipelineStoreTask(new FilePipelineStoreTask(runtimeInfo, stageLibraryTask, pipelineStateStore));
    pipelineStoreTask.init();
    scheduledExecutor = new SafeScheduledExecutorService(6, "PipelineManager");
    pipelineManager =
      new PipelineManager(runtimeInfo, configuration, pipelineStoreTask, pipelineStateStore, stageLibraryTask,
        scheduledExecutor, scheduledExecutor, scheduledExecutor);
    pipelineManager.init();
  }

  @Before
  public void setup() throws IOException {
    System.setProperty(RuntimeModule.SDC_PROPERTY_PREFIX + RuntimeInfo.DATA_DIR, "./target/var");
    File f = new File(System.getProperty(RuntimeModule.SDC_PROPERTY_PREFIX + RuntimeInfo.DATA_DIR));
    FileUtils.deleteDirectory(f);
    setUpManager(PipelineManager.DEFAULT_RUNNER_EXPIRY_INTERVAL);
  }

  @After
  public void tearDown() {
    pipelineStoreTask.stop();
    pipelineManager.stop();
    scheduledExecutor.shutdownNow();
  }

  @Test
  public void testPreviewer() {
    Previewer previewer = pipelineManager.createPreviewer("abcd", "0");
    assertEquals(previewer, pipelineManager.getPreview(previewer.getId()));
    ((PipelineManager)pipelineManager).outputRetrieved(previewer.getId());
    assertNull(pipelineManager.getPreview(previewer.getId()));
  }

  @Test
  public void testRunner() throws Exception {
    pipelineStoreTask.create("aaaa", "blah", "user");
    assertNotNull(pipelineManager.getRunner("aaaa", "0", "user1"));
  }

  @Test
  public void testGetPipelineStates() throws Exception {
    pipelineStoreTask.create("aaaa", "blah", "user");
    List<PipelineState> pipelineStates = pipelineManager.getPipelines();

    assertEquals("aaaa", pipelineStates.get(0).getName());
    assertEquals("0", pipelineStates.get(0).getRev());

    pipelineStoreTask.create("bbbb", "blah", "user");
    pipelineStates = pipelineManager.getPipelines();
    assertEquals(2, pipelineStates.size());

    pipelineStoreTask.delete("aaaa");
    pipelineStates = pipelineManager.getPipelines();
    assertEquals(1, pipelineStates.size());
    pipelineStoreTask.delete("bbbb");
    pipelineStates = pipelineManager.getPipelines();
    assertEquals(0, pipelineStates.size());
  }

  @Test
  public void testInitTask() throws Exception {
    pipelineStoreTask.create("aaaa", "blah", "user");
    pipelineStateStore.saveState("user", "aaaa", "0", PipelineStatus.CONNECTING, "blah", null, ExecutionMode.STANDALONE);
    pipelineStoreTask.stop();
    pipelineManager.stop();
    setUpManager(PipelineManager.DEFAULT_RUNNER_EXPIRY_INTERVAL);
    Thread.sleep(2000);
    List<PipelineState> pipelineStates = pipelineManager.getPipelines();
    assertEquals(1, pipelineStates.size());
    assertTrue(((PipelineManager) pipelineManager).isRunnerPresent("aaaa", "0"));
    pipelineStateStore.saveState("user", "aaaa", "0", PipelineStatus.FINISHING, "blah", null, ExecutionMode.STANDALONE);
    pipelineStoreTask.stop();
    pipelineManager.stop();
    setUpManager(PipelineManager.DEFAULT_RUNNER_EXPIRY_INTERVAL);
    pipelineStates = pipelineManager.getPipelines();
    assertEquals(1, pipelineStates.size());
    assertEquals(PipelineStatus.FINISHED, pipelineStates.get(0).getStatus());
    // no runner is created
    assertFalse(((PipelineManager) pipelineManager).isRunnerPresent("aaaa", "0"));
  }

  @Test
  public void testExpiry() throws Exception {
    pipelineStoreTask.create("aaaa", "blah", "user");
    Runner runner = pipelineManager.getRunner("aaaa", "0", "user1");
    pipelineStateStore.saveState("user", "aaaa", "0", PipelineStatus.RUNNING_ERROR, "blah", null, ExecutionMode.STANDALONE);
    assertEquals(PipelineStatus.RUNNING_ERROR, runner.getStatus());
    pipelineStoreTask.stop();
    pipelineManager.stop();
    pipelineManager = null;
    setUpManager(100);
    Thread.sleep(2000);
    assertFalse(((PipelineManager) pipelineManager).isRunnerPresent("aaaa", "0"));
  }

}