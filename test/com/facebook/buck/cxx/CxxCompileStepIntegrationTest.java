/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.cxx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class CxxCompileStepIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  private void assertCompDir(Path compDir, Optional<String> failure) throws Exception {
    ProjectFilesystem filesystem = new ProjectFilesystem(tmp.getRoot().toPath());
    CxxPlatform platform = DefaultCxxPlatforms.build(
        new CxxBuckConfig(FakeBuckConfig.builder().build()));

    // Build up the paths to various files the archive step will use.
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    ImmutableList<String> compiler =
        platform.getCc().resolve(resolver).getCommandPrefix(pathResolver);
    Path output = filesystem.resolve(Paths.get("output.o"));
    Path depFile = filesystem.resolve(Paths.get("output.dep"));
    Path relativeInput = Paths.get("input.c");
    Path input = filesystem.resolve(relativeInput);
    filesystem.writeContentsToPath("int main() {}", relativeInput);
    Path scratchDir = filesystem.getRootPath().getFileSystem().getPath("scratchDir");
    filesystem.mkdirs(scratchDir);

    ImmutableList.Builder<String> preprocessorCommand = ImmutableList.builder();
    preprocessorCommand.addAll(compiler);

    ImmutableList.Builder<String> compilerCommand = ImmutableList.builder();
    compilerCommand.addAll(compiler);
    compilerCommand.add("-g");

    DebugPathSanitizer sanitizer = new DebugPathSanitizer(
        200,
        File.separatorChar,
        compDir,
        ImmutableBiMap.<Path, Path>of());

    // Build an archive step.
    CxxPreprocessAndCompileStep step =
        new CxxPreprocessAndCompileStep(
            filesystem,
            CxxPreprocessAndCompileStep.Operation.COMPILE_MUNGE_DEBUGINFO,
            output,
            depFile,
            relativeInput,
            CxxSource.Type.C,
            Optional.of(
                new CxxPreprocessAndCompileStep.ToolCommand(
                    preprocessorCommand.build(),
                    ImmutableMap.<String, String>of(),
                    Optional.<ImmutableList<String>>absent())),
            Optional.of(
                new CxxPreprocessAndCompileStep.ToolCommand(
                    compilerCommand.build(),
                    ImmutableMap.<String, String>of(),
                    Optional.<ImmutableList<String>>absent())),
            HeaderPathNormalizer.empty(pathResolver),
            sanitizer,
            CxxPlatformUtils.DEFAULT_CONFIG.getHeaderVerification(),
            scratchDir);

    // Execute the archive step and verify it ran successfully.
    ExecutionContext executionContext = TestExecutionContext.newInstance();
    TestConsole console = (TestConsole) executionContext.getConsole();
    int exitCode = step.execute(executionContext);
    if (failure.isPresent()) {
      assertNotEquals("compile step succeeded", 0, exitCode);
      assertThat(
          console.getTextWrittenToStdErr(),
          console.getTextWrittenToStdErr(),
          Matchers.containsString(failure.get()));
    } else {
      assertEquals("compile step failed: " + console.getTextWrittenToStdErr(), 0, exitCode);
      // Verify that we find the expected compilation dir embedded in the file.
      String contents = new String(Files.readAllBytes(output));
      assertThat(
          contents,
          Matchers.containsString(sanitizer.getCompilationDirectory()));
    }

    // Cleanup.
    Files.delete(input);
    Files.deleteIfExists(output);
  }

  @Test
  public void updateCompilationDir() throws Exception {
    assertCompDir(Paths.get("."), Optional.<String>absent());
    assertCompDir(Paths.get("blah"), Optional.<String>absent());
  }

}
