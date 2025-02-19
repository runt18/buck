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

package com.facebook.buck.cli;

import static com.facebook.buck.util.MoreStringsForTests.normalizeNewlines;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.ProjectWorkspace.ProcessResult;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ObjectMappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.CharMatcher;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import org.hamcrest.MatcherAssert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

public class TargetsCommandIntegrationTest {

  private static final String ABSOLUTE_PATH_TO_FILE_OUTSIDE_THE_PROJECT_THAT_EXISTS_ON_THE_FS =
      "/bin/sh";

  private static final CharMatcher LOWER_CASE_HEX_DIGITS =
      CharMatcher.inRange('0', '9').or(CharMatcher.inRange('a', 'f'));

  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  @Rule
  public ExpectedException thrown = ExpectedException.none();

  @Test
  public void testOutputPath() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "output_path", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand(
        "targets",
        "--show-output",
        "//:test",
        "//:another-test");
    result.assertSuccess();
    assertEquals(
        "//:another-test buck-out/gen/another-test/test-output\n" +
            "//:test buck-out/gen/test/test-output\n",
        result.getStdout());
  }

  @Test
  public void testRuleKeyWithOneTarget() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "output_path", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand(
        "targets",
        "--show-rulekey",
        "//:test");
    result.assertSuccess();
    assertEquals("//:test 12c109cdbab186fbb8fdd785853d8bcb4538aed2\n", result.getStdout());
  }

  @Test
  public void testRuleKey() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "output_path", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand(
        "targets",
        "--show-rulekey",
        "//:test",
        "//:another-test");
    result.assertSuccess();
    parseAndVerifyTargetsAndHashes(
        result.getStdout(),
        "//:another-test",
        "//:test");
  }

  @Test
  public void testBothOutputAndRuleKey() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "output_path", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand(
        "targets",
        "--show-rulekey",
        "--show-output",
        "//:test");
    result.assertSuccess();
    assertEquals(
        "//:test 12c109cdbab186fbb8fdd785853d8bcb4538aed2 buck-out/gen/test/test-output\n",
        result.getStdout());
  }

  @Test
  public void testOutputWithoutTarget() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "output_path", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand(
        "targets",
        "--show-output");
    result.assertSuccess();
    assertEquals(
        "//:another-test buck-out/gen/another-test/test-output\n" +
            "//:test buck-out/gen/test/test-output\n",
        result.getStdout());
  }

  @Test
  public void testRuleKeyWithoutTarget() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "output_path", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand(
        "targets",
        "--show-rulekey");
    result.assertSuccess();
    assertEquals(
        "//:another-test 9837c730735a095e73fde946172bca84a228cb6d\n" +
            "//:test 12c109cdbab186fbb8fdd785853d8bcb4538aed2\n",
        result.getStdout());
  }

  private ImmutableList<String> parseAndVerifyTargetsAndHashes(
      String outputLine,
      String... targets) {
    List<String> lines = Splitter.on('\n').splitToList(
        CharMatcher.whitespace().trimFrom(outputLine));
    assertEquals(targets.length, lines.size());
    ImmutableList.Builder<String> hashes = ImmutableList.builder();
    for (int i = 0; i < targets.length; ++i) {
      String line = lines.get(i);
      String target = targets[i];
      hashes.add(parseAndVerifyTargetAndHash(line, target));
    }
    return hashes.build();
  }

  private String parseAndVerifyTargetAndHash(String outputLine, String target) {
    List<String> targetAndHash = Splitter.on(' ').splitToList(
        CharMatcher.whitespace().trimFrom(outputLine));
    assertEquals(2, targetAndHash.size());
    assertEquals(target, targetAndHash.get(0));
    assertFalse(targetAndHash.get(1).isEmpty());
    assertTrue(LOWER_CASE_HEX_DIGITS.matchesAllOf(targetAndHash.get(1)));
    return targetAndHash.get(1);
  }

  @Test
  public void testTargetHashWithoutTarget() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "output_path", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand(
        "targets",
        "--show-target-hash");
    result.assertSuccess();
    parseAndVerifyTargetsAndHashes(
        result.getStdout(),
        "//:another-test",
        "//:test");
  }

  @Test
  public void testRuleKeyWithReferencedFiles() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "java_library_with_tests", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand(
        "targets",
        "--show-rulekey",
        "--referenced-file",
        "Test.java");
    result.assertSuccess();
    parseAndVerifyTargetAndHash(result.getStdout(), "//:test");
  }

  @Test
  public void testRuleKeyWithReferencedFilesAndDetectTestChanges() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "java_library_with_tests", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand(
        "targets",
        "--show-rulekey",
        "--detect-test-changes",
        "--referenced-file",
        "Test.java");
    result.assertSuccess();
    parseAndVerifyTargetsAndHashes(
        result.getStdout(),
        "//:lib",
        "//:test");
  }

  @Test
  public void testTargetHash() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "output_path", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand(
        "targets",
        "--show-target-hash",
        "//:test",
        "//:another-test");
    result.assertSuccess();
    parseAndVerifyTargetsAndHashes(
        result.getStdout(),
        "//:another-test",
        "//:test");
  }

  @Test
  public void testTargetHashAndRuleKeyThrows() throws IOException {
    thrown.expect(HumanReadableException.class);
    thrown.expectMessage("Cannot show rule key and target hash at the same time.");
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "output_path", tmp);
    workspace.setUp();

    workspace.runBuckCommand(
        "targets",
        "--show-target-hash",
        "--show-rulekey",
        "//:test");
  }

  @Test
  public void testTargetHashAndRuleKeyAndOutputThrows() throws IOException {
    thrown.expect(HumanReadableException.class);
    thrown.expectMessage("Cannot show rule key and target hash at the same time.");
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "output_path", tmp);
    workspace.setUp();

    workspace.runBuckCommand(
        "targets",
        "--show-target-hash",
        "--show-rulekey",
        "--show-output",
        "//:test");
  }

  @Test
  public void testTargetHashXcodeWorkspaceWithTests() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "xcode_workspace_with_tests", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand(
        "targets",
        "--show-target-hash",
        "--detect-test-changes",
        "//workspace:workspace");
    result.assertSuccess();
    parseAndVerifyTargetAndHash(result.getStdout(), "//workspace:workspace");
  }

  @Test
  public void testTargetHashXcodeWorkspaceWithTestsForAllTargets() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "xcode_workspace_with_tests", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand(
        "targets",
        "--show-target-hash",
        "--detect-test-changes");
    result.assertSuccess();
    parseAndVerifyTargetsAndHashes(
        result.getStdout(),
        "//bin:bin",
        "//bin:genrule",
        "//lib:lib",
        "//test:test",
        "//workspace:workspace");
  }

  @Test
  public void testTargetHashWithBrokenTargets() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "detect_test_changes_with_broken_targets", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand(
        "targets",
        "--show-target-hash",
        "--detect-test-changes",
        "//:test");
    result.assertSuccess();
    parseAndVerifyTargetAndHash(result.getStdout(), "//:test");
  }

  @Test
  public void testTargetHashXcodeWorkspaceWithoutTestsDiffersFromWithTests() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "xcode_workspace_with_tests", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand(
        "targets",
        "--show-target-hash",
        "--detect-test-changes",
        "//workspace:workspace");
    result.assertSuccess();
    String hash = parseAndVerifyTargetAndHash(result.getStdout(), "//workspace:workspace");

    ProcessResult result2 = workspace.runBuckCommand(
        "targets",
        "--show-target-hash",
        "//workspace:workspace");
    result2.assertSuccess();
    String hash2 = parseAndVerifyTargetAndHash(result2.getStdout(), "//workspace:workspace");
    assertNotEquals(hash, hash2);
  }

  @Test
  public void testTargetHashChangesAfterModifyingSourceFile() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "xcode_workspace_with_tests", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand(
        "targets",
        "--show-target-hash",
        "--detect-test-changes",
        "//workspace:workspace");
    result.assertSuccess();
    String hash = parseAndVerifyTargetAndHash(
        result.getStdout(),
        "//workspace:workspace");

    String fileName = "test/Test.m";
    Files.write(workspace.getPath(fileName), "// This is not a test\n".getBytes(UTF_8));
    ProcessResult result2 = workspace.runBuckCommand(
        "targets",
        "--show-target-hash",
        "--detect-test-changes",
        "//workspace:workspace");
    result2.assertSuccess();
    String hash2 = parseAndVerifyTargetAndHash(
        result2.getStdout(),
        "//workspace:workspace");

    assertNotEquals(hash, hash2);
  }

  @Test
  public void testTargetHashChangesAfterModifyingSourceFileForAllTargets() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "xcode_workspace_with_tests", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand(
        "targets",
        "--show-target-hash",
        "--detect-test-changes");
    result.assertSuccess();
    List<String> hashes = parseAndVerifyTargetsAndHashes(
        result.getStdout(),
        "//bin:bin",
        "//bin:genrule",
        "//lib:lib",
        "//test:test",
        "//workspace:workspace");

    String fileName = "test/Test.m";
    Files.write(workspace.getPath(fileName), "// This is not a test\n".getBytes(UTF_8));
    ProcessResult result2 = workspace.runBuckCommand(
        "targets",
        "--show-target-hash",
        "--detect-test-changes");
    result2.assertSuccess();
    List<String> hashesAfterModification = parseAndVerifyTargetsAndHashes(
        result2.getStdout(),
        "//bin:bin",
        "//bin:genrule",
        "//lib:lib",
        "//test:test",
        "//workspace:workspace");

    assertNotEquals(hashes.get(0), hashesAfterModification.get(0));
    // bin:genrule wasn't changed
    assertEquals(hashes.get(1), hashesAfterModification.get(1));
    assertNotEquals(hashes.get(2), hashesAfterModification.get(2));
    assertNotEquals(hashes.get(3), hashesAfterModification.get(3));
    assertNotEquals(hashes.get(4), hashesAfterModification.get(4));
  }

  @Test
  public void testTargetHashChangesAfterDeletingSourceFile() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "xcode_workspace_with_tests", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand(
        "targets",
        "--show-target-hash",
        "--detect-test-changes",
        "//workspace:workspace");
    result.assertSuccess();
    String hash = parseAndVerifyTargetAndHash(
        result.getStdout(),
        "//workspace:workspace");

    String fileName = "test/Test.m";
    Files.delete(workspace.getPath(fileName));
    ProcessResult result2 = workspace.runBuckCommand(
        "targets",
        "--show-target-hash",
        "--detect-test-changes",
        "//workspace:workspace");
    result2.assertSuccess();

    String hash2 = parseAndVerifyTargetAndHash(
        result2.getStdout(),
        "//workspace:workspace");
    assertNotEquals(hash, hash2);
  }

  @Test
  public void testBuckTargetsReferencedFileWithFileOutsideOfProject() throws IOException {
    // The contents of the project are not relevant for this test. We just want a non-empty project
    // to prevent against a regression where all of the build rules are printed.
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "project_slice", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand(
        "targets",
        "--referenced-file",
        ABSOLUTE_PATH_TO_FILE_OUTSIDE_THE_PROJECT_THAT_EXISTS_ON_THE_FS);
    result.assertSuccess("Even though the file is outside the project, " +
        "`buck targets` should succeed.");
    assertEquals("Because no targets match, stdout should be empty.", "", result.getStdout());
  }

  @Test
  public void testBuckTargetsReferencedFileWithFilesInAndOutsideOfProject() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "project_slice", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand(
        "targets",
        "--type",
        "prebuilt_jar",
        "--referenced-file",
        ABSOLUTE_PATH_TO_FILE_OUTSIDE_THE_PROJECT_THAT_EXISTS_ON_THE_FS,
        "libs/guava.jar", // relative path in project
        tmp.getRootPath().resolve("libs/junit.jar").toString()); // absolute path in project
    result.assertSuccess("Even though one referenced file is outside the project, " +
        "`buck targets` should succeed.");
    assertEquals(
        ImmutableSet.of(
            "//libs:guava",
            "//libs:junit"),
        ImmutableSet.copyOf(Splitter.on('\n').omitEmptyStrings().split(result.getStdout())));
  }

  @Test
  public void testBuckTargetsReferencedFileWithNonExistentFile() throws IOException {
    // The contents of the project are not relevant for this test. We just want a non-empty project
    // to prevent against a regression where all of the build rules are printed.
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "project_slice", tmp);
    workspace.setUp();

    String pathToNonExistentFile = "modules/dep1/dep2/hello.txt";
    assertFalse(Files.exists(workspace.getPath(pathToNonExistentFile)));
    ProcessResult result = workspace.runBuckCommand(
        "targets",
        "--referenced-file",
        pathToNonExistentFile);
    result.assertSuccess("Even though the file does not exist, buck targets` should succeed.");
    assertEquals("Because no targets match, stdout should be empty.", "", result.getStdout());
  }

  @Test
  public void testValidateBuildTargetForNonAliasTarget() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "target_validation", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand(
        "targets", "--resolve-alias", "//:test-library");
    assertTrue(result.getStdout(), result.getStdout().contains("//:test-library"));

    try {
      workspace.runBuckCommand("targets", "--resolve-alias", "//:");
    } catch (HumanReadableException e) {
      assertEquals("//: cannot end with a colon", e.getMessage());
    }

    try {
      workspace.runBuckCommand("targets", "--resolve-alias", "//:test-libarry");
    } catch (HumanReadableException e) {
      assertEquals("//:test-libarry is not a valid target.", e.getMessage());
    }

    try {
      workspace.runBuckCommand("targets", "--resolve-alias", "//blah/foo");
    } catch (HumanReadableException e) {
      assertEquals("//blah/foo must contain exactly one colon (found 0)", e.getMessage());
    }
  }

  @Test
  public void testJsonOutputWithShowOptions() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "output_path", tmp);
    workspace.setUp();
    ProcessResult result = workspace.runBuckCommand(
        "targets", "--json", "--show-output", "//:test");
    ObjectMapper objectMapper = ObjectMappers.newDefaultInstance();

    // Parse the observed JSON.
    JsonNode observed = objectMapper.readTree(
        objectMapper.getFactory().createParser(result.getStdout())
    );

    System.out.println(observed.toString());

    String expectedJson = workspace.getFileContents("output_path_json.js");
    JsonNode expected = objectMapper.readTree(
        objectMapper.getFactory().createParser(normalizeNewlines(expectedJson))
    );

    MatcherAssert.assertThat(
        "Output from targets command should match expected JSON.",
        observed,
        equalTo(expected));
  }

  @Test
  public void testShowAllTargets() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "xcode_workspace_with_tests", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand(
        "targets");
    result.assertSuccess();
    assertEquals(
        ImmutableSet.of(
            "//bin:bin",
            "//bin:genrule",
            "//lib:lib",
            "//test:test",
            "//workspace:workspace"),
        ImmutableSet.copyOf(Splitter.on('\n').omitEmptyStrings().split(result.getStdout())));
  }
}
