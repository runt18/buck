/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.model;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.facebook.buck.config.Config;
import com.facebook.buck.config.ConfigBuilder;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.util.BuckConstant;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;

import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;

public class FilesystemBackedBuildFileTreeTest {

  @Rule
  public TemporaryPaths tmp = new TemporaryPaths();

  @Test @Ignore("Remove when test passes on OS X (the case preserving file system hurts us)")
  public void testCanConstructBuildFileTreeFromFilesystemOnOsX() throws IOException {
    Path tempDir = tmp.getRoot();
    ProjectFilesystem filesystem = new ProjectFilesystem(tempDir);

    Path command = tempDir.resolve("src/com/facebook/buck/command");
    Files.createDirectories(command);
    Path notbuck = tempDir.resolve("src/com/facebook/buck/notbuck");
    Files.createDirectories(notbuck);

    // Although these next two lines create a file and a directory, the OS X filesystem is often
    // case insensitive. As we run File.listFiles only the directory entry is returned. Thanks OS X.
    touch(tempDir.resolve("src/com/facebook/BUCK"));
    touch(tempDir.resolve("src/com/facebook/buck/BUCK"));
    touch(tempDir.resolve("src/com/facebook/buck/command/BUCK"));
    touch(tempDir.resolve("src/com/facebook/buck/notbuck/BUCK"));

    BuildFileTree buildFiles = new FilesystemBackedBuildFileTree(filesystem, "BUCK");
    Iterable<Path> allChildren =
        buildFiles.getChildPaths(BuildTarget.builder(tmp.getRoot(), "src", "com/facebook").build());
    assertEquals(ImmutableSet.of(Paths.get("buck")),
        ImmutableSet.copyOf(allChildren));

    Iterable<Path> subChildren = buildFiles.getChildPaths(
        BuildTarget.builder(tmp.getRoot(), "//src", "/com/facebook/buck").build());
    assertEquals(ImmutableSet.of(Paths.get("command"), Paths.get("notbuck")),
        ImmutableSet.copyOf(subChildren));
  }

  @Test
  public void testCanConstructBuildFileTreeFromFilesystem() throws IOException {
    Path tempDir = tmp.getRoot();
    ProjectFilesystem filesystem = new ProjectFilesystem(tempDir);

    Path command = tempDir.resolve("src/com/example/build/command");
    Files.createDirectories(command);
    Path notbuck = tempDir.resolve("src/com/example/build/notbuck");
    Files.createDirectories(notbuck);
    Files.createDirectories(tempDir.resolve("src/com/example/some/directory"));

    touch(tempDir.resolve("src/com/example/BUCK"));
    touch(tempDir.resolve("src/com/example/build/BUCK"));
    touch(tempDir.resolve("src/com/example/build/command/BUCK"));
    touch(tempDir.resolve("src/com/example/build/notbuck/BUCK"));
    touch(tempDir.resolve("src/com/example/some/directory/BUCK"));

    BuildFileTree buildFiles = new FilesystemBackedBuildFileTree(filesystem, "BUCK");
    Collection<Path> allChildren = buildFiles.getChildPaths(
        BuildTargetFactory.newInstance("//src/com/example:example"));
    assertEquals(ImmutableSet.of(Paths.get("build"), Paths.get("some/directory")),
        ImmutableSet.copyOf(allChildren));

    Iterable<Path> subChildren = buildFiles.getChildPaths(
        BuildTargetFactory.newInstance("//src/com/example/build:build"));
    assertEquals(ImmutableSet.of(Paths.get("command"), Paths.get("notbuck")),
        ImmutableSet.copyOf(subChildren));

    assertEquals(Paths.get("src/com/example"),
        buildFiles.getBasePathOfAncestorTarget(
            Paths.get("src/com/example/foo")).get());
    assertEquals(Paths.get("src/com/example"),
        buildFiles.getBasePathOfAncestorTarget(
            Paths.get("src/com/example/some/bar")).get());
    assertEquals(Paths.get("src/com/example/some/directory"),
        buildFiles.getBasePathOfAncestorTarget(
            Paths.get("src/com/example/some/directory/baz")).get());
  }

  @Test
  public void respectsIgnorePaths() throws IOException {
    Path tempDir = tmp.getRoot();
    Path fooBuck = tempDir.resolve("foo/BUCK");
    Path fooBarBuck = tempDir.resolve("foo/bar/BUCK");
    Path fooBazBuck = tempDir.resolve("foo/baz/BUCK");
    Files.createDirectories(fooBarBuck.getParent());
    Files.createDirectories(fooBazBuck.getParent());
    touch(fooBuck);
    touch(fooBarBuck);
    touch(fooBazBuck);

    Config config = ConfigBuilder.createFromText(
        "[project]",
        "ignore = foo/bar");
    ProjectFilesystem filesystem = new ProjectFilesystem(tempDir, config);
    BuildFileTree buildFiles = new FilesystemBackedBuildFileTree(filesystem, "BUCK");

    Collection<Path> children =
        buildFiles.getChildPaths(BuildTarget.builder(tempDir, "//foo", "foo").build());
    assertEquals(ImmutableSet.of(Paths.get("baz")), children);

    Path ancestor = buildFiles.getBasePathOfAncestorTarget(Paths.get("foo/bar/xyzzy")).get();
    assertEquals(Paths.get("foo"), ancestor);
  }

  @Test
  public void rootBasePath() throws IOException {
    Path root = tmp.getRoot();
    Files.createFile(root.resolve("BUCK"));
    Files.createDirectory(root.resolve("foo"));
    Files.createFile(root.resolve("foo/BUCK"));

    ProjectFilesystem filesystem = new ProjectFilesystem(root);
    BuildFileTree buildFileTree = new FilesystemBackedBuildFileTree(filesystem, "BUCK");

    Optional<Path> ancestor = buildFileTree.getBasePathOfAncestorTarget(Paths.get("bar/baz"));
    assertEquals(Optional.of(Paths.get("")), ancestor);
  }

  @Test
  public void missingBasePath() throws IOException {
    Path root = tmp.getRoot();
    Files.createDirectory(root.resolve("foo"));
    Files.createFile(root.resolve("foo/BUCK"));

    ProjectFilesystem filesystem = new ProjectFilesystem(root);
    BuildFileTree buildFileTree = new FilesystemBackedBuildFileTree(filesystem, "BUCK");

    Optional<Path> ancestor = buildFileTree.getBasePathOfAncestorTarget(Paths.get("bar/baz"));
    assertEquals(Optional.<Path>absent(), ancestor);
  }

  @Test
  public void shouldIgnoreBuckOutputDirectoriesByDefault() throws IOException {
    Path root = tmp.getRoot();

    Path buckOut = root.resolve(BuckConstant.getBuckOutputPath());
    Files.createDirectories(buckOut);
    touch(buckOut.resolve("BUCK"));
    Path sibling = buckOut.resolve("someFile");
    touch(sibling);

    // Config doesn't set any "ignore" entries.
    ProjectFilesystem filesystem = new ProjectFilesystem(root, new Config());
    BuildFileTree buildFileTree = new FilesystemBackedBuildFileTree(filesystem, "BUCK");

    Optional<Path> ancestor = buildFileTree.getBasePathOfAncestorTarget(
        BuckConstant.getBuckOutputPath().resolve("someFile"));
    assertFalse(ancestor.isPresent());
  }

  @Test
  public void shouldIgnoreBuckCacheDirectoriesByDefault() throws IOException {
    Path root = tmp.getRoot();

    Path cacheDir = root.resolve(BuckConstant.getDefaultCacheDir());
    Files.createDirectories(cacheDir);
    touch(cacheDir.resolve("BUCK"));
    Path sibling = cacheDir.resolve("someFile");
    touch(sibling);

    // Config doesn't set any "ignore" entries.
    ProjectFilesystem filesystem = new ProjectFilesystem(root, new Config());
    BuildFileTree buildFileTree = new FilesystemBackedBuildFileTree(filesystem, "BUCK");

    Optional<Path> ancestor = buildFileTree.getBasePathOfAncestorTarget(
        cacheDir.resolve("someFile"));
    assertFalse(ancestor.isPresent());
  }


  private void touch(Path path) throws IOException {
    Files.write(path, "".getBytes(UTF_8));
  }
}
