/**
 * (c) 2014 StreamSets, Inc. All rights reserved. May not
 * be copied, modified, or distributed in whole or part without
 * written consent of StreamSets, Inc.
 */
package com.streamsets.datacollector.runner;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Throwables;
import com.streamsets.datacollector.config.PipelineConfiguration;
import com.streamsets.datacollector.creation.PipelineBean;
import com.streamsets.datacollector.creation.PipelineBeanCreator;
import com.streamsets.datacollector.creation.PipelineConfigBean;
import com.streamsets.datacollector.memory.MemoryUsageCollectorResourceBundle;
import com.streamsets.datacollector.runner.production.BadRecordsHandler;
import com.streamsets.datacollector.stagelibrary.StageLibraryTask;
import com.streamsets.datacollector.util.Configuration;
import com.streamsets.datacollector.util.ContainerError;
import com.streamsets.datacollector.validation.Issue;
import com.streamsets.datacollector.validation.IssueCreator;
import com.streamsets.pipeline.api.Source;
import com.streamsets.pipeline.api.Stage;
import com.streamsets.pipeline.api.StageException;
import com.streamsets.pipeline.api.impl.Utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class Pipeline {
  private static final Logger LOG = LoggerFactory.getLogger(Pipeline.class);
  private final PipelineBean pipelineBean;
  private final String name;
  private final String rev;
  private final Configuration configuration;
  private final Pipe[] pipes;
  private final PipelineRunner runner;
  private final Observer observer;
  private final BadRecordsHandler badRecordsHandler;
  private final ResourceControlledScheduledExecutor scheduledExecutorService;
  private volatile boolean running;

  private Pipeline(String name, String rev, Configuration configuration, PipelineBean pipelineBean, Pipe[] pipes,
                   Observer observer, BadRecordsHandler badRecordsHandler, PipelineRunner runner,
                   ResourceControlledScheduledExecutor scheduledExecutorService) {
    this.pipelineBean = pipelineBean;
    this.name = name;
    this.rev = rev;
    this.configuration = configuration;
    this.pipes = pipes;
    this.observer = observer;
    this.badRecordsHandler = badRecordsHandler;
    this.runner = runner;
    this.scheduledExecutorService = scheduledExecutorService;
    this.running = false;
  }

  PipelineConfigBean getPipelineConfig() {
    return  pipelineBean.getConfig();
  }

  @VisibleForTesting
  Pipe[] getPipes() {
    return pipes;
  }

  public Source getSource() {
    Source source = null;
    for (Pipe pipe : pipes) {
      if (pipe.getStage().getStage() instanceof Source) {
        source = (Source)pipe.getStage().getStage();
        break;
      }
    }
    if (source == null) {
      throw new NullPointerException("Cannot find pipeline source");
    }
    return source;
  }

  public PipelineRunner getRunner() {
    return runner;
  }

  public List<Issue> validateConfigs() throws StageException {
    try {
      return init();
    } catch (Throwable throwable) {
      LOG.error("Uncaught error in init: " + throwable, throwable);
      throw Throwables.propagate(throwable);
    } finally {
      destroy();
    }
  }

  @SuppressWarnings("unchecked")
  public List<Issue> init() {
    PipeContext pipeContext = new PipeContext();
    List<Issue> issues = new ArrayList<>();
    try {
      issues.addAll(badRecordsHandler.init(pipeContext));
    } catch (Exception ex) {
      LOG.warn(ContainerError.CONTAINER_0700.getMessage(), ex.toString(), ex);
      issues.add(IssueCreator.getStage(badRecordsHandler.getInstanceName()).create(ContainerError.CONTAINER_0700,
        ex.toString()));
    }
    for (Pipe pipe : pipes) {
      try {
        issues.addAll(pipe.init(pipeContext));
      } catch (Exception ex) {
        String instanceName = pipe.getStage().getConfiguration().getInstanceName();
        LOG.warn(ContainerError.CONTAINER_0701.getMessage(), instanceName, ex.toString(), ex);
        issues.add(IssueCreator.getStage(instanceName).create(ContainerError.CONTAINER_0701, instanceName,
          ex.toString()));
      }
    }
    return issues;
  }

  public void destroy() {
    try {
      badRecordsHandler.destroy();
    } catch (Exception ex) {
      String msg = Utils.format("Exception thrown during bad record handler destroy: {}", ex);
      LOG.warn(msg, ex);
    }
    for (Pipe pipe : pipes) {
      try {
        pipe.destroy();
      } catch (RuntimeException ex) {
        String msg = Utils.format("Exception thrown during pipe '{}' destroy: {}", pipe, ex);
        LOG.warn(msg, ex);
      }
    }
    if (scheduledExecutorService != null) {
      scheduledExecutorService.shutdown();
    }
  }

  public void run() throws StageException, PipelineRuntimeException {
    this.running = true;
    try {
      runner.setObserver(observer);
      runner.run(pipes, badRecordsHandler);
    } finally {
      this.running = false;
    }
  }

  public void run(List<StageOutput> stageOutputsToOverride) throws StageException, PipelineRuntimeException {
    this.running = true;
    try {
    runner.setObserver(observer);
    runner.run(pipes, badRecordsHandler, stageOutputsToOverride);
    } finally {
      this.running = false;
    }
  }

  public boolean isRunning() {
    return running;
  }

  public static class Builder {
    private static final String EXECUTION_MODE_CONFIG_KEY = "executionMode";
    private static final String EXECUTION_MODE_CLUSTER = "CLUSTER";

    private final StageLibraryTask stageLib;
    private final Configuration configuration;
    private final String name;
    private final String pipelineName;
    private final String rev;
    private final PipelineConfiguration pipelineConf;
    private Observer observer;
    private final ResourceControlledScheduledExecutor scheduledExecutor =
      new ResourceControlledScheduledExecutor(0.01f); // consume 1% of a cpu calculating stage memory consumption
    private final MemoryUsageCollectorResourceBundle memoryUsageCollectorResourceBundle =
      new MemoryUsageCollectorResourceBundle();
    private List<Issue> errors;


    public Builder(StageLibraryTask stageLib, Configuration configuration, String name, String pipelineName, String rev,
                   PipelineConfiguration pipelineConf) {
      this.stageLib = stageLib;
      this.name = name;
      this.pipelineName = pipelineName;
      this.rev = rev;
      this.configuration = configuration;
      this.pipelineConf = pipelineConf;
      errors = Collections.emptyList();
    }
    public Builder setObserver(Observer observer) {
      this.observer = observer;
      return this;
    }

    public Pipeline build(PipelineRunner runner) throws PipelineRuntimeException {
      Pipeline pipeline = null;
      errors = new ArrayList<>();
      PipelineBean pipelineBean = PipelineBeanCreator.get().create(true, stageLib, pipelineConf, errors);
      StageRuntime[] stages = null;
      StageRuntime errorStage = null;
      if (pipelineBean != null) {
        stages = new StageRuntime[pipelineBean.getStages().size()];
        for (int i = 0; i < pipelineBean.getStages().size(); i++) {
          stages[i] = new StageRuntime(pipelineBean, pipelineBean.getStages().get(i));
        }
        errorStage = new StageRuntime(pipelineBean, pipelineBean.getErrorStage());
        setStagesContext(stages, errorStage, runner);
        Pipe[] pipes = createPipes(stages);
        BadRecordsHandler badRecordsHandler = new BadRecordsHandler(errorStage);
        try {
          pipeline = new Pipeline(name, rev, configuration, pipelineBean, pipes, observer, badRecordsHandler, runner,
            scheduledExecutor);
        } catch (Exception e) {
          String msg = "Could not create memory usage collector: " + e;
          throw new PipelineRuntimeException(ContainerError.CONTAINER_0151, msg, e);
        }
      }
      return pipeline;
    }

    public List<Issue> getIssues() {
      return errors;
    }

    private void setStagesContext(StageRuntime[] stages, StageRuntime errorStage, PipelineRunner runner) {
      List<Stage.Info> infos = new ArrayList<>(stages.length);
      List<Stage.Info> infosUnmodifiable = Collections.unmodifiableList(infos);
      boolean clusterMode = isClusterMode(pipelineConf);
      for (StageRuntime stage : stages) {
        infos.add(stage.getInfo());
        stage.setContext(new StageContext(pipelineName, rev, infosUnmodifiable, stage.getDefinition().getType(), runner.isPreview(),
          runner.getMetrics(), stage, pipelineConf.getMemoryLimitConfiguration().getMemoryLimit(), clusterMode,
          runner.getRuntimeInfo().getResourcesDir()));
      }
      errorStage.setContext(new StageContext(pipelineName, rev, infosUnmodifiable, errorStage.getDefinition().getType(), runner.isPreview(),
          runner.getMetrics(), errorStage, pipelineConf.getMemoryLimitConfiguration().getMemoryLimit(), clusterMode,
          runner.getRuntimeInfo().getResourcesDir()));
    }

    private boolean isClusterMode(PipelineConfiguration pipelineConf) {
      boolean clusterMode = false;
      if(pipelineConf.getConfiguration(EXECUTION_MODE_CONFIG_KEY) != null) {
        String executionMode = (String) pipelineConf.getConfiguration(EXECUTION_MODE_CONFIG_KEY).getValue();
        if (executionMode != null && !executionMode.isEmpty() && executionMode.equals(EXECUTION_MODE_CLUSTER)) {
          clusterMode = true;
        }
      }
      return clusterMode;
    }

    private Pipe[] createPipes(StageRuntime[] stages) throws PipelineRuntimeException {
      LaneResolver laneResolver = new LaneResolver(stages);
      List<Pipe> pipes = new ArrayList<>(stages.length * 3);
      for (int idx = 0; idx < stages.length; idx++) {
        Pipe pipe;
        StageRuntime stage = stages[idx];
        switch (stage.getDefinition().getType()) {
          case SOURCE:
            pipe = new StagePipe(pipelineName, rev, configuration, stage, laneResolver.getStageInputLanes(idx),
              laneResolver.getStageOutputLanes(idx), scheduledExecutor, memoryUsageCollectorResourceBundle);
            pipes.add(pipe);
            pipe = new ObserverPipe(stage, laneResolver.getObserverInputLanes(idx),
                                    laneResolver.getObserverOutputLanes(idx), observer);
            pipes.add(pipe);
            pipe = new MultiplexerPipe(stage, laneResolver.getMultiplexerInputLanes(idx),
                                       laneResolver.getMultiplexerOutputLanes(idx));
            pipes.add(pipe);
            break;
          case PROCESSOR:
            pipe = new CombinerPipe(stage, laneResolver.getCombinerInputLanes(idx),
                                    laneResolver.getCombinerOutputLanes(idx));
            pipes.add(pipe);
            pipe = new StagePipe(pipelineName, rev, configuration, stage, laneResolver.getStageInputLanes(idx),
                                 laneResolver.getStageOutputLanes(idx), scheduledExecutor,
              memoryUsageCollectorResourceBundle);
            pipes.add(pipe);
            pipe = new ObserverPipe(stage, laneResolver.getObserverInputLanes(idx),
                                    laneResolver.getObserverOutputLanes(idx), observer);
            pipes.add(pipe);
            pipe = new MultiplexerPipe(stage, laneResolver.getMultiplexerInputLanes(idx),
                                       laneResolver.getMultiplexerOutputLanes(idx));
            pipes.add(pipe);
            break;
          case TARGET:
            pipe = new CombinerPipe(stage, laneResolver.getCombinerInputLanes(idx),
                                    laneResolver.getCombinerOutputLanes(idx));
            pipes.add(pipe);
            pipe = new StagePipe(pipelineName, rev, configuration, stage, laneResolver.getStageInputLanes(idx),
              laneResolver.getStageOutputLanes(idx), scheduledExecutor, memoryUsageCollectorResourceBundle);
            pipes.add(pipe);
            break;
        }
      }
      return pipes.toArray(new Pipe[pipes.size()]);
    }

  }

  @Override
  public String toString() {
    Set<String> instances = new LinkedHashSet<>();
    for (Pipe pipe : pipes) {
      instances.add(pipe.getStage().getInfo().getInstanceName());
    }
    String observerName = (observer != null) ? observer.getClass().getSimpleName() : null;
    return Utils.format("Pipeline[stages='{}' runner='{}' observer='{}']", instances, runner.getClass().getSimpleName(),
                        observerName);
  }

}