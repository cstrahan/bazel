// Copyright 2015 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.standalone;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static org.junit.Assert.fail;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Sets;
import com.google.common.eventbus.EventBus;
import com.google.devtools.build.lib.actions.ActionContext;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionInputPrefetcher;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.ArtifactExpander;
import com.google.devtools.build.lib.actions.BaseSpawn;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.ResourceManager;
import com.google.devtools.build.lib.actions.ResourceSet;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.SpawnActionContext;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.events.PrintingEventHandler;
import com.google.devtools.build.lib.events.Reporter;
import com.google.devtools.build.lib.exec.ActionContextProvider;
import com.google.devtools.build.lib.exec.BlazeExecutor;
import com.google.devtools.build.lib.exec.ExecutionOptions;
import com.google.devtools.build.lib.exec.SingleBuildFileCache;
import com.google.devtools.build.lib.exec.local.LocalExecutionOptions;
import com.google.devtools.build.lib.integration.util.IntegrationMock;
import com.google.devtools.build.lib.testutil.TestConstants;
import com.google.devtools.build.lib.testutil.TestUtils;
import com.google.devtools.build.lib.util.BlazeClock;
import com.google.devtools.build.lib.util.OS;
import com.google.devtools.build.lib.util.io.FileOutErr;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.util.FileSystems;
import com.google.devtools.common.options.Options;
import com.google.devtools.common.options.OptionsParser;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Test StandaloneSpawnStrategy.
 */
@RunWith(JUnit4.class)
public class StandaloneSpawnStrategyTest {
  private static final ArtifactExpander SIMPLE_ARTIFACT_EXPANDER =
      new ArtifactExpander() {
        @Override
        public void expand(Artifact artifact, Collection<? super Artifact> output) {
          output.add(artifact);
        }
      };

  private Reporter reporter =
      new Reporter(new EventBus(), PrintingEventHandler.ERRORS_AND_WARNINGS_TO_STDERR);
  private BlazeExecutor executor;
  private FileSystem fileSystem;
  private FileOutErr outErr;

  private Path createTestRoot() throws IOException {
    fileSystem = FileSystems.getNativeFileSystem();
    Path testRoot = fileSystem.getPath(TestUtils.tmpDir());
    try {
      FileSystemUtils.deleteTreesBelow(testRoot);
    } catch (IOException e) {
      System.err.println("Failed to remove directory " + testRoot + ": " + e.getMessage());
      throw e;
    }
    return testRoot;
  }

  @Before
  public final void setUp() throws Exception {
    Path testRoot = createTestRoot();
    Path workspaceDir = testRoot.getRelative("workspace-name");
    workspaceDir.createDirectory();
    outErr = new FileOutErr(testRoot.getRelative("stdout"), testRoot.getRelative("stderr"));

    // setup output base & directories
    Path outputBase = testRoot.getRelative("outputBase");
    outputBase.createDirectory();

    BlazeDirectories directories =
        new BlazeDirectories(outputBase, outputBase, workspaceDir, "mock-product-name");
    // This call implicitly symlinks the integration bin tools into the exec root.
    IntegrationMock.get().getIntegrationBinTools(directories, TestConstants.WORKSPACE_NAME);
    OptionsParser optionsParser = OptionsParser.newOptionsParser(ExecutionOptions.class);
    optionsParser.parse("--verbose_failures");
    LocalExecutionOptions localExecutionOptions = Options.getDefaults(LocalExecutionOptions.class);

    EventBus bus = new EventBus();

    ResourceManager resourceManager = ResourceManager.instanceForTestingOnly();
    resourceManager.setAvailableResources(
        ResourceSet.create(/*memoryMb=*/1, /*cpuUsage=*/1, /*ioUsage=*/1, /*localTestCount=*/1));
    Path execRoot = directories.getExecRoot(TestConstants.WORKSPACE_NAME);
    this.executor =
        new BlazeExecutor(
            execRoot,
            reporter,
            bus,
            BlazeClock.instance(),
            optionsParser,
            ImmutableList.<ActionContext>of(),
            ImmutableMap.<String, SpawnActionContext>of(
                "",
                new StandaloneSpawnStrategy(
                    execRoot, ActionInputPrefetcher.NONE, localExecutionOptions,
                    /*verboseFailures=*/false, "mock-product-name", resourceManager)),
            ImmutableList.<ActionContextProvider>of());

    executor.getExecRoot().createDirectory();
  }

  private Spawn createSpawn(String... arguments) {
    return new BaseSpawn.Local(
        Arrays.asList(arguments),
        ImmutableMap.<String, String>of(),
        new ActionsTestUtil.NullAction(),
        ResourceSet.ZERO);
  }

  private String out() {
    return outErr.outAsLatin1();
  }
  private String err() {
    return outErr.errAsLatin1();
  }

  @Test
  public void testBinTrueExecutesFine() throws Exception {
    Spawn spawn = createSpawn(getTrueCommand());
    executor.getSpawnActionContext(spawn.getMnemonic()).exec(spawn, createContext());

    assertThat(out()).isEmpty();
    assertThat(err()).isEmpty();
  }

  private void run(Spawn spawn) throws Exception {
    executor.getSpawnActionContext(spawn.getMnemonic()).exec(spawn, createContext());
  }
  private ActionExecutionContext createContext() {
    Path execRoot = executor.getExecRoot();
    return new ActionExecutionContext(
        executor,
        new SingleBuildFileCache(execRoot.getPathString(), execRoot.getFileSystem()),
        null,
        outErr,
        ImmutableMap.<String, String>of(),
        SIMPLE_ARTIFACT_EXPANDER);
  }

  @Test
  public void testBinFalseYieldsException() throws Exception {
    try {
      run(createSpawn(getFalseCommand()));
      fail();
    } catch (ExecException e) {
      assertWithMessage("got: " + e.getMessage())
          .that(e.getMessage().startsWith("false failed: error executing command"))
          .isTrue();
    }
  }

  private static String getFalseCommand() {
    return OS.getCurrent() == OS.DARWIN ? "/usr/bin/false" : "/bin/false";
  }

  private static String getTrueCommand() {
    return OS.getCurrent() == OS.DARWIN ? "/usr/bin/true" : "/bin/true";
  }

  @Test
  public void testBinEchoPrintsArguments() throws Exception {
    Spawn spawn = createSpawn("/bin/echo", "Hello,", "world.");
    run(spawn);
    assertThat(out()).isEqualTo("Hello, world.\n");
    assertThat(err()).isEmpty();
  }

  @Test
  public void testCommandRunsInWorkingDir() throws Exception {
    Spawn spawn = createSpawn("/bin/pwd");
    run(spawn);
    assertThat(out()).isEqualTo(executor.getExecRoot() + "\n");
  }

  @Test
  public void testCommandHonorsEnvironment() throws Exception {
    Spawn spawn = new BaseSpawn.Local(
        Arrays.asList("/usr/bin/env"),
        ImmutableMap.of("foo", "bar", "baz", "boo"),
        new ActionsTestUtil.NullAction(),
        ResourceSet.ZERO);
    run(spawn);
    assertThat(Sets.newHashSet(out().split("\n"))).isEqualTo(Sets.newHashSet("foo=bar", "baz=boo"));
  }

  @Test
  public void testStandardError() throws Exception {
    Spawn spawn = createSpawn("/bin/sh", "-c", "echo Oops! >&2");
    run(spawn);
    assertThat(err()).isEqualTo("Oops!\n");
    assertThat(out()).isEmpty();
  }
}
