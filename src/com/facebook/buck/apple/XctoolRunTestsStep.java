/*
 * Copyright 2015-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.apple;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.io.TeeInputStream;
import com.facebook.buck.log.Logger;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.test.selectors.TestDescription;
import com.facebook.buck.test.selectors.TestSelectorList;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.Escaper;
import com.facebook.buck.util.MoreThrowables;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Iterables;
import com.google.common.io.ByteStreams;
import com.google.common.io.CharStreams;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs {@code xctool} on one or more logic tests and/or application
 * tests (each paired with a host application).
 *
 * The output is written in streaming JSON format to stdout and is
 * parsed by {@link XctoolOutputParsing}.
 */
class XctoolRunTestsStep implements Step {

  private static final Semaphore stutterLock = new Semaphore(1);
  private static final ScheduledExecutorService stutterTimeoutExecutorService =
      Executors.newSingleThreadScheduledExecutor();
  private static final String XCTOOL_ENV_VARIABLE_PREFIX = "XCTOOL_TEST_ENV_";

  private final ProjectFilesystem filesystem;

  public interface StdoutReadingCallback {
    void readStdout(InputStream stdout) throws IOException;
  }

  private static final Logger LOG = Logger.get(XctoolRunTestsStep.class);

  private final ImmutableList<String> command;
  private final Optional<Long> xctoolStutterTimeout;
  private final Path outputPath;
  private final Optional<? extends StdoutReadingCallback> stdoutReadingCallback;
  private final Supplier<Optional<Path>> xcodeDeveloperDirSupplier;
  private final TestSelectorList testSelectorList;
  private final Optional<String> logDirectoryEnvironmentVariable;
  private final Optional<Path> logDirectory;
  private final Optional<String> logLevelEnvironmentVariable;
  private final Optional<String> logLevel;

  // Helper class to parse the output of `xctool -listTestsOnly` then
  // store it in a multimap of {target: [testDesc1, testDesc2, ...], ... } pairs.
  //
  // We need to remember both the target name and the test class/method names, since
  // `xctool -only` requires the format `TARGET:className/methodName,...`
  private static class ListTestsOnlyHandler implements XctoolOutputParsing.XctoolEventCallback {
    private String currentTestTarget;
    public Multimap<String, TestDescription> testTargetsToDescriptions;

    public ListTestsOnlyHandler() {
      this.currentTestTarget = null;
      // We use a LinkedListMultimap to make the order deterministic for testing.
      this.testTargetsToDescriptions = LinkedListMultimap.create();
    }

    @Override
    public void handleBeginOcunitEvent(XctoolOutputParsing.BeginOcunitEvent event) {
      // Signals the start of listing all tests belonging to a single target.
      this.currentTestTarget = event.targetName;
    }

    @Override
    public void handleEndOcunitEvent(XctoolOutputParsing.EndOcunitEvent event) {
      Preconditions.checkNotNull(this.currentTestTarget);
      Preconditions.checkState(this.currentTestTarget.equals(event.targetName));
      // Signals the end of listing all tests belonging to a single target.
      this.currentTestTarget = null;
    }

    @Override
    public void handleBeginTestSuiteEvent(XctoolOutputParsing.BeginTestSuiteEvent event) {
    }

    @Override
    public void handleEndTestSuiteEvent(XctoolOutputParsing.EndTestSuiteEvent event) {
    }

    @Override
    public void handleBeginStatusEvent(XctoolOutputParsing.StatusEvent event) {
    }

    @Override
    public void handleEndStatusEvent(XctoolOutputParsing.StatusEvent event) {
    }

    @Override
    public void handleBeginTestEvent(XctoolOutputParsing.BeginTestEvent event) {
      Preconditions.checkNotNull(this.currentTestTarget);
      testTargetsToDescriptions.put(
          this.currentTestTarget,
          new TestDescription(event.className, event.methodName));
    }

    @Override
    public void handleEndTestEvent(XctoolOutputParsing.EndTestEvent event) {
    }
  }

  public XctoolRunTestsStep(
      ProjectFilesystem filesystem,
      Path xctoolPath,
      Optional<Long> xctoolStutterTimeout,
      String sdkName,
      Optional<String> destinationSpecifier,
      Collection<Path> logicTestBundlePaths,
      Map<Path, Path> appTestBundleToHostAppPaths,
      Path outputPath,
      Optional<? extends StdoutReadingCallback> stdoutReadingCallback,
      Supplier<Optional<Path>> xcodeDeveloperDirSupplier,
      TestSelectorList testSelectorList,
      Optional<String> logDirectoryEnvironmentVariable,
      Optional<Path> logDirectory,
      Optional<String> logLevelEnvironmentVariable,
      Optional<String> logLevel) {
    Preconditions.checkArgument(
        !(logicTestBundlePaths.isEmpty() &&
          appTestBundleToHostAppPaths.isEmpty()),
        "Either logic tests (%s) or app tests (%s) must be present",
        logicTestBundlePaths,
        appTestBundleToHostAppPaths);

    this.filesystem = filesystem;

    // Each test bundle must have one of these extensions. (xctool
    // depends on them to choose which test runner to use.)
    Preconditions.checkArgument(
        AppleBundleExtensions.allPathsHaveValidTestExtensions(logicTestBundlePaths),
        "Extension of all logic tests must be one of %s (got %s)",
        AppleBundleExtensions.VALID_XCTOOL_BUNDLE_EXTENSIONS,
        logicTestBundlePaths);
    Preconditions.checkArgument(
        AppleBundleExtensions.allPathsHaveValidTestExtensions(appTestBundleToHostAppPaths.keySet()),
        "Extension of all app tests must be one of %s (got %s)",
        AppleBundleExtensions.VALID_XCTOOL_BUNDLE_EXTENSIONS,
        appTestBundleToHostAppPaths.keySet());

    this.command = createCommandArgs(
        xctoolPath,
        sdkName,
        destinationSpecifier,
        logicTestBundlePaths,
        appTestBundleToHostAppPaths);
    this.xctoolStutterTimeout = xctoolStutterTimeout;
    this.outputPath = outputPath;
    this.stdoutReadingCallback = stdoutReadingCallback;
    this.xcodeDeveloperDirSupplier = xcodeDeveloperDirSupplier;
    this.testSelectorList = testSelectorList;
    this.logDirectoryEnvironmentVariable = logDirectoryEnvironmentVariable;
    this.logDirectory = logDirectory;
    this.logLevelEnvironmentVariable = logLevelEnvironmentVariable;
    this.logLevel = logLevel;
  }

  @Override
  public String getShortName() {
    return "xctool-run-tests";
  }

  public ImmutableMap<String, String> getEnv(ExecutionContext context) {
    Map<String, String> environment = new HashMap<>();
    environment.putAll(context.getEnvironment());
    Optional<Path> xcodeDeveloperDir = xcodeDeveloperDirSupplier.get();
    if (xcodeDeveloperDir.isPresent()) {
      environment.put("DEVELOPER_DIR", xcodeDeveloperDir.get().toString());
    } else {
      throw new RuntimeException("Cannot determine xcode developer dir");
    }
    // xctool will only pass through to the test environment variables whose names
    // start with `XCTOOL_TEST_ENV_`. (It will remove that prefix when passing them
    // to the test.)
    if (logDirectoryEnvironmentVariable.isPresent() && logDirectory.isPresent()) {
      environment.put(
          XCTOOL_ENV_VARIABLE_PREFIX + logDirectoryEnvironmentVariable.get(),
          logDirectory.get().toString());
    }
    if (logLevelEnvironmentVariable.isPresent() && logLevel.isPresent()) {
      environment.put(
          XCTOOL_ENV_VARIABLE_PREFIX + logLevelEnvironmentVariable.get(),
          logLevel.get());
    }

    return ImmutableMap.copyOf(environment);
  }

  @Override
  public int execute(ExecutionContext context) throws InterruptedException {
    ImmutableMap<String, String> env = getEnv(context);

    ProcessExecutorParams.Builder processExecutorParamsBuilder = ProcessExecutorParams.builder()
        .addAllCommand(command)
        .setDirectory(filesystem.getRootPath().toAbsolutePath().toFile())
        .setRedirectOutput(ProcessBuilder.Redirect.PIPE)
        .setEnvironment(env);

    if (!testSelectorList.isEmpty()) {
      try {
        ImmutableList.Builder<String> xctoolFilterParamsBuilder = ImmutableList.builder();
        int returnCode = listAndFilterTestsThenFormatXctoolParams(
            context.getProcessExecutor(),
            context.getConsole(),
            testSelectorList,
            // Copy the entire xctool command and environment but add a -listTestsOnly arg.
            ProcessExecutorParams.builder()
                .from(processExecutorParamsBuilder.build())
                .addCommand("-listTestsOnly")
                .build(),
            xctoolFilterParamsBuilder);
        if (returnCode != 0) {
          context.getConsole().printErrorText("Failed to query tests with xctool");
          return returnCode;
        }
        ImmutableList<String> xctoolFilterParams = xctoolFilterParamsBuilder.build();
        if (xctoolFilterParams.isEmpty()) {
          context.getConsole().printBuildFailure(
              String.format(
                  Locale.US,
                  "No tests found matching specified filter (%s)",
                  testSelectorList.getExplanation()));
          return 0;
        }
        processExecutorParamsBuilder.addAllCommand(xctoolFilterParams);
      } catch (IOException e) {
        context.getConsole().printErrorText("Failed to get list of tests from test bundle");
        context.getConsole().printBuildFailureWithStacktrace(e);
        return 1;
      }
    }

    ProcessExecutorParams processExecutorParams = processExecutorParamsBuilder.build();

    // Only launch one instance of xctool at the time
    final AtomicBoolean stutterLockIsNotified = new AtomicBoolean(false);
    try {
      LOG.debug("Running command: %s", processExecutorParams);

      try {
        acquireStutterLock(stutterLockIsNotified);

        // Start the process.
        ProcessExecutor.LaunchedProcess launchedProcess =
            context.getProcessExecutor().launchProcess(processExecutorParams);

        int exitCode;
        String stderr;
        try (OutputStream outputStream = filesystem.newFileOutputStream(
            outputPath);
             TeeInputStream stdoutWrapperStream = new TeeInputStream(
                 launchedProcess.getInputStream(), outputStream);
             InputStreamReader stderrReader = new InputStreamReader(
                 launchedProcess.getErrorStream(),
                 StandardCharsets.UTF_8);
             BufferedReader bufferedStderrReader = new BufferedReader(stderrReader)) {
          if (stdoutReadingCallback.isPresent()) {
            // The caller is responsible for reading all the data, which TeeInputStream will
            // copy to outputStream.
            stdoutReadingCallback.get().readStdout(stdoutWrapperStream);
          } else {
            // Nobody's going to read from stdoutWrapperStream, so close it and copy
            // the process's stdout to outputPath directly.
            stdoutWrapperStream.close();
            ByteStreams.copy(launchedProcess.getInputStream(), outputStream);
          }
          stderr = CharStreams.toString(bufferedStderrReader).trim();
          exitCode = waitForProcessAndGetExitCode(context.getProcessExecutor(), launchedProcess);
          LOG.debug("Finished running command, exit code %d, stderr %s", exitCode, stderr);
        } finally {
          context.getProcessExecutor().destroyLaunchedProcess(launchedProcess);
          context.getProcessExecutor().waitForLaunchedProcess(launchedProcess);
        }

        if (exitCode != 0) {
          if (!stderr.isEmpty()) {
            context.getConsole().printErrorText(
                String.format(
                    Locale.US,
                    "xctool failed with exit code %d: %s",
                    exitCode,
                    stderr));
          } else {
            context.getConsole().printErrorText(
                String.format(
                    Locale.US,
                    "xctool failed with exit code %d",
                    exitCode));
          }
        }

        return exitCode;

      } catch (Exception e) {
        LOG.error(e, "Exception while running %s", processExecutorParams.getCommand());
        MoreThrowables.propagateIfInterrupt(e);
        context.getConsole().printBuildFailureWithStacktrace(e);
        return 1;
      }
    } finally {
      releaseStutterLock(stutterLockIsNotified);
    }
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return Joiner.on(' ').join(Iterables.transform(command, Escaper.SHELL_ESCAPER));
  }

  private static int listAndFilterTestsThenFormatXctoolParams(
      ProcessExecutor processExecutor,
      Console console,
      TestSelectorList testSelectorList,
      ProcessExecutorParams listTestsOnlyParams,
      ImmutableList.Builder<String> filterParamsBuilder) throws IOException, InterruptedException {
    Preconditions.checkArgument(!testSelectorList.isEmpty());
    LOG.debug("Filtering tests with selector list: %s", testSelectorList.getExplanation());

    LOG.debug("Listing tests with command: %s", listTestsOnlyParams);
    ProcessExecutor.LaunchedProcess launchedProcess =
        processExecutor.launchProcess(listTestsOnlyParams);

    ListTestsOnlyHandler listTestsOnlyHandler = new ListTestsOnlyHandler();
    String stderr;
    int listTestsResult;
    try (InputStreamReader isr =
         new InputStreamReader(
             launchedProcess.getInputStream(),
             StandardCharsets.UTF_8);
         BufferedReader br = new BufferedReader(isr);
         InputStreamReader esr =
         new InputStreamReader(
             launchedProcess.getErrorStream(),
             StandardCharsets.UTF_8);
         BufferedReader ebr = new BufferedReader(esr)) {
      XctoolOutputParsing.streamOutputFromReader(br, listTestsOnlyHandler);
      stderr = CharStreams.toString(ebr).trim();
      listTestsResult = processExecutor.waitForLaunchedProcess(launchedProcess);
    }

    if (listTestsResult != 0) {
      if (!stderr.isEmpty()) {
        console.printErrorText(
            String.format(
                Locale.US,
                "xctool failed with exit code %d: %s",
                listTestsResult,
                stderr));
      } else {
        console.printErrorText(
            String.format(
                Locale.US,
                "xctool failed with exit code %d",
                listTestsResult));
      }
    } else {
      formatXctoolFilterParams(
          testSelectorList, listTestsOnlyHandler.testTargetsToDescriptions, filterParamsBuilder);
    }

    return listTestsResult;
  }

  private static void formatXctoolFilterParams(
      TestSelectorList testSelectorList,
      Multimap<String, TestDescription> testTargetsToDescriptions,
      ImmutableList.Builder<String> filterParamsBuilder) {
    for (String testTarget : testTargetsToDescriptions.keySet()) {
      StringBuilder sb = new StringBuilder();
      boolean matched = false;
      for (TestDescription testDescription : testTargetsToDescriptions.get(testTarget)) {
        if (!testSelectorList.isIncluded(testDescription)) {
          continue;
        }
        if (!matched) {
          matched = true;
          sb.append(testTarget);
          sb.append(':');
        } else {
          sb.append(',');
        }
        sb.append(testDescription.getClassName());
        sb.append('/');
        sb.append(testDescription.getMethodName());
      }
      if (matched) {
        filterParamsBuilder.add("-only");
        filterParamsBuilder.add(sb.toString());
      }
    }
  }

  private static ImmutableList<String> createCommandArgs(
      Path xctoolPath,
      String sdkName,
      Optional<String> destinationSpecifier,
      Collection<Path> logicTestBundlePaths,
      Map<Path, Path> appTestBundleToHostAppPaths) {
    ImmutableList.Builder<String> args = ImmutableList.builder();
    args.add(xctoolPath.toString());
    args.add("-reporter");
    args.add("json-stream");
    args.add("-sdk", sdkName);
    if (destinationSpecifier.isPresent()) {
      args.add("-destination");
      args.add(destinationSpecifier.get());
    }
    args.add("run-tests");
    for (Path logicTestBundlePath : logicTestBundlePaths) {
      args.add("-logicTest");
      args.add(logicTestBundlePath.toString());
    }
    for (Map.Entry<Path, Path> appTestBundleAndHostApp : appTestBundleToHostAppPaths.entrySet()) {
      args.add("-appTest");
      args.add(appTestBundleAndHostApp.getKey() + ":" + appTestBundleAndHostApp.getValue());
    }

    return args.build();
  }

  private static int waitForProcessAndGetExitCode(
      ProcessExecutor processExecutor,
      ProcessExecutor.LaunchedProcess launchedProcess)
      throws InterruptedException {
    int processExitCode = processExecutor.waitForLaunchedProcess(launchedProcess);
    if (processExitCode == 0 || processExitCode == 1) {
      // Test failure is denoted by xctool returning 1. Unfortunately, there's no way
      // to distinguish an internal xctool error from a test failure:
      //
      // https://github.com/facebook/xctool/issues/511
      //
      // We don't want to fail the step on a test failure, so return 0 on either
      // xctool exit code.
      return 0;
    } else {
      // Some unknown failure.
      return processExitCode;
    }
  }

  private void acquireStutterLock(final AtomicBoolean stutterLockIsNotified)
      throws InterruptedException {
    if (!xctoolStutterTimeout.isPresent()) {
      return;
    }
    try {
      stutterLock.acquire();
    } catch (Exception e) {
      releaseStutterLock(stutterLockIsNotified);
      throw e;
    }
    stutterTimeoutExecutorService.schedule(
        new Runnable() {
          @Override
          public void run() {
            releaseStutterLock(stutterLockIsNotified);
          }
        },
        xctoolStutterTimeout.get(),
        TimeUnit.MILLISECONDS);
  }

  private void releaseStutterLock(AtomicBoolean stutterLockIsNotified) {
    if (!xctoolStutterTimeout.isPresent()) {
      return;
    }
    if (!stutterLockIsNotified.getAndSet(true)) {
      stutterLock.release();
    }
  }

  public ImmutableList<String> getCommand() {
    return command;
  }

}
