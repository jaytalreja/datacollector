/**
 * (c) 2015 StreamSets, Inc. All rights reserved. May not
 * be copied, modified, or distributed in whole or part without
 * written consent of StreamSets, Inc.
 */
package com.streamsets.datacollector.creation;

import com.streamsets.datacollector.config.StageConfiguration;
import com.streamsets.datacollector.config.StageDefinition;
import com.streamsets.datacollector.stagelibrary.ClassLoaderReleaser;
import com.streamsets.pipeline.api.Stage;

public class StageBean {
  private final StageDefinition definition;
  private final StageConfiguration conf;
  private final StageConfigBean systemConfigs;
  private final Stage stage;
  private final ClassLoaderReleaser classLoaderReleaser;

  public StageBean(StageDefinition definition, StageConfiguration conf, StageConfigBean systemConfigs, Stage stage,
      ClassLoaderReleaser classLoaderReleaser) {
    this.definition = definition;
    this.conf = conf;
    this.systemConfigs = systemConfigs;
    this.stage = stage;
    this.classLoaderReleaser = classLoaderReleaser;
  }

  public StageDefinition getDefinition() {
    return definition;
  }

  public StageConfiguration getConfiguration() {
    return conf;
  }

  public StageConfigBean getSystemConfigs() {
    return systemConfigs;
  }

  public Stage getStage() {
    return stage;
  }

  public void releaseClassLoader() {
    classLoaderReleaser.releaseStageClassLoader(stage.getClass().getClassLoader());
  }
}