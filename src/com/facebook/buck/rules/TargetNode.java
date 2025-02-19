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

package com.facebook.buck.rules;

import com.facebook.buck.model.BuildFileTree;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.util.ExceptionWithHumanReadableMessage;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;
import com.google.common.hash.HashCode;

import java.lang.reflect.Field;
import java.nio.file.Path;

/**
 * A {@link TargetNode} represents a node in the target graph which is created by the
 * {@link com.facebook.buck.parser.Parser} as a result of parsing BUCK files in a project. It is
 * responsible for processing the raw (python) inputs of a build rule, and gathering any build
 * targets and paths referenced from those inputs.
 */
public class TargetNode<T> implements Comparable<TargetNode<?>>, HasBuildTarget {

  private final HashCode rawInputsHashCode;
  private final TypeCoercerFactory typeCoercerFactory;
  private final BuildRuleFactoryParams ruleFactoryParams;
  private final Function<Optional<String>, Path> cellRoots;

  private final Description<T> description;

  private final T constructorArg;
  private final ImmutableSet<Path> pathsReferenced;
  private final ImmutableSet<BuildTarget> declaredDeps;
  private final ImmutableSortedSet<BuildTarget> extraDeps;
  private final ImmutableSet<VisibilityPattern> visibilityPatterns;

  @SuppressWarnings("unchecked")
  public TargetNode(
      HashCode rawInputsHashCode,
      Description<T> description,
      T constructorArg,
      TypeCoercerFactory typeCoercerFactory,
      BuildRuleFactoryParams params,
      ImmutableSet<BuildTarget> declaredDeps,
      ImmutableSet<VisibilityPattern> visibilityPatterns,
      Function<Optional<String>, Path> cellRoots)
      throws NoSuchBuildTargetException, InvalidSourcePathInputException {
    this.rawInputsHashCode = rawInputsHashCode;
    this.description = description;
    this.constructorArg = constructorArg;
    this.typeCoercerFactory = typeCoercerFactory;
    this.ruleFactoryParams = params;
    this.cellRoots = cellRoots;

    final ImmutableSet.Builder<Path> paths = ImmutableSet.builder();
    final ImmutableSortedSet.Builder<BuildTarget> extraDeps = ImmutableSortedSet.naturalOrder();

    // Scan the input to find possible BuildTargets, necessary for loading dependent rules.
    T arg = description.createUnpopulatedConstructorArg();
    for (Field field : arg.getClass().getFields()) {
      ParamInfo info = new ParamInfo(typeCoercerFactory, field);
      if (info.isDep() && info.isInput() &&
          info.hasElementTypes(BuildTarget.class, SourcePath.class, Path.class)) {
        detectBuildTargetsAndPathsForConstructorArg(extraDeps, paths, info, constructorArg);
      }
    }

    if (description instanceof ImplicitDepsInferringDescription) {
      extraDeps
          .addAll(
              ((ImplicitDepsInferringDescription<T>) description)
                  .findDepsForTargetFromConstructorArgs(params.target, cellRoots, constructorArg));
    }

    if (description instanceof ImplicitInputsInferringDescription) {
      paths
          .addAll(
              ((ImplicitInputsInferringDescription<T>) description)
                  .inferInputsFromConstructorArgs(
                      params.target.getUnflavoredBuildTarget(),
                      constructorArg));
    }

    this.extraDeps = ImmutableSortedSet.copyOf(Sets.difference(extraDeps.build(), declaredDeps));
    this.pathsReferenced = ruleFactoryParams.enforceBuckPackageBoundary()
        ? verifyPaths(paths.build())
        : paths.build();

    this.declaredDeps = declaredDeps;
    this.visibilityPatterns = visibilityPatterns;
  }

  /**
   * @return A hash of the raw input from the build file used to construct the node.
   */
  public HashCode getRawInputsHashCode() {
    return rawInputsHashCode;
  }

  public Description<T> getDescription() {
    return description;
  }

  public BuildRuleType getType() {
    return description.getBuildRuleType();
  }

  public T getConstructorArg() {
    return constructorArg;
  }

  @Override
  public BuildTarget getBuildTarget() {
    return ruleFactoryParams.target;
  }

  public ImmutableSet<Path> getInputs() {
    return pathsReferenced;
  }

  public ImmutableSet<BuildTarget> getDeclaredDeps() {
    return declaredDeps;
  }

  public ImmutableSet<BuildTarget> getExtraDeps() {
    return extraDeps;
  }

  public ImmutableSet<BuildTarget> getDeps() {
    ImmutableSet.Builder<BuildTarget> builder = ImmutableSet.builder();
    builder.addAll(getDeclaredDeps());
    builder.addAll(getExtraDeps());
    return builder.build();
  }

  public BuildRuleFactoryParams getRuleFactoryParams() {
    return ruleFactoryParams;
  }

  /**
   * TODO(andrewjcg): It'd be nice to eventually move this implementation to an
   * `AbstractDescription` base class, so that the various types of descriptions
   * can install their own implementations.  However, we'll probably want to move
   * most of what is now `BuildRuleParams` to `DescriptionParams` and set them up
   * while building the target graph.
   */
  public boolean isVisibleTo(TargetNode<?> other) {
    // Targets in the same build file are always visible to each other.
    if (getBuildTarget().getCellPath().equals(other.getBuildTarget().getCellPath()) &&
        getBuildTarget().getBaseName().equals(other.getBuildTarget().getBaseName())) {
      return true;
    }

    for (VisibilityPattern pattern : visibilityPatterns) {
      if (pattern.apply(other)) {
        return true;
      }
    }

    return false;
  }

  public void checkVisibility(TargetNode<?> other) {
    if (!isVisibleTo(other)) {
      throw new HumanReadableException(
          "%s depends on %s, which is not visible",
          other,
          getBuildTarget());
    }
  }

  /**
   * Type safe checked cast of the constructor arg.
   */
  @SuppressWarnings("unchecked")
  public <U> Optional<TargetNode<U>> castArg(Class<U> cls) {
    if (cls.isInstance(constructorArg)) {
      return Optional.of((TargetNode<U>) this);
    } else {
      return Optional.absent();
    }
  }

  private void detectBuildTargetsAndPathsForConstructorArg(
      final ImmutableSet.Builder<BuildTarget> depsBuilder,
      final ImmutableSet.Builder<Path> pathsBuilder,
      ParamInfo info,
      T constructorArg) throws NoSuchBuildTargetException {
    // We'll make no test for optionality here. Let's assume it's done elsewhere.

    try {
      info.traverse(
          new ParamInfo.Traversal() {
            @Override
            public void traverse(Object object) {
              if (object instanceof PathSourcePath) {
                pathsBuilder.add(((PathSourcePath) object).getRelativePath());
              } else if (object instanceof BuildTargetSourcePath) {
                depsBuilder.add(((BuildTargetSourcePath) object).getTarget());
              } else if (object instanceof Path) {
                pathsBuilder.add((Path) object);
              } else if (object instanceof BuildTarget) {
                depsBuilder.add((BuildTarget) object);
              }
            }
          },
          constructorArg);
    } catch (RuntimeException e) {
      if (e.getCause() instanceof NoSuchBuildTargetException) {
        throw (NoSuchBuildTargetException) e.getCause();
      }
    }
  }

  private ImmutableSet<Path> verifyPaths(ImmutableSet<Path> paths)
      throws InvalidSourcePathInputException {
    Path basePath = getBuildTarget().getBasePath();
    BuildFileTree buildFileTree = ruleFactoryParams.getBuildFileTree();

    for (Path path : paths) {
      if (!basePath.toString().isEmpty() && !path.startsWith(basePath)) {
        throw new InvalidSourcePathInputException(
            "'%s' in '%s' refers to a parent directory.",
            basePath.relativize(path),
            getBuildTarget());
      }

      Optional<Path> ancestor = buildFileTree.getBasePathOfAncestorTarget(path);
      // It should not be possible for us to ever get an Optional.absent() for this because that
      // would require one of two conditions:
      // 1) The source path references parent directories, which we check for above.
      // 2) You don't have a build file above this file, which is impossible if it is referenced in
      //    a build file *unless* you happen to be referencing something that is ignored.
      if (!ancestor.isPresent()) {
        throw new InvalidSourcePathInputException(
            "'%s' in '%s' crosses a buck package boundary.  This is probably caused by " +
                "specifying one of the folders in '%s' in your .buckconfig under `project.ignore`.",
            path,
            getBuildTarget(),
            path);
      }
      if (!ancestor.get().equals(basePath)) {
        throw new InvalidSourcePathInputException(
            "'%s' in '%s' crosses a buck package boundary.  This file is owned by '%s'.  Find " +
                "the owning rule that references '%s', and use a reference to that rule instead " +
                "of referencing the desired file directly.",
            path,
            getBuildTarget(),
            ancestor.get(),
            path);
      }
    }

    return paths;
  }

  @Override
  public int compareTo(TargetNode<?> o) {
    return getBuildTarget().compareTo(o.getBuildTarget());
  }

  @Override
  public final String toString() {
    return getBuildTarget().getFullyQualifiedName();
  }

  /**
   * Return a copy of the current TargetNode, with the {@link Description} used for creating
   * {@link BuildRule} instances switched out.
   */
  @SuppressWarnings({"rawtypes", "unchecked"})
  public TargetNode<?> withDescription(Description<?> description) {
    try {
      return new TargetNode(
          rawInputsHashCode,
          description,
          constructorArg,
          typeCoercerFactory,
          ruleFactoryParams,
          declaredDeps,
          visibilityPatterns,
          cellRoots);
    } catch (InvalidSourcePathInputException | NoSuchBuildTargetException e) {
      // This is extremely unlikely to happen --- we've already created a TargetNode with these
      // values before.
      throw new RuntimeException(e);
    }
  }

  public TargetNode<T> withFlavors(Iterable<Flavor> flavors) {
    try {
      return new TargetNode<>(
          rawInputsHashCode,
          description,
          constructorArg,
          typeCoercerFactory,
          ruleFactoryParams.withFlavors(flavors),
          declaredDeps,
          visibilityPatterns,
          cellRoots);
    } catch (InvalidSourcePathInputException | NoSuchBuildTargetException e) {
      // This is extremely unlikely to happen --- we've already created a TargetNode with these
      // values before.
      throw new RuntimeException(e);
    }
  }

  public Function<Optional<String>, Path> getCellNames() {
    return cellRoots;
  }

  @SuppressWarnings("serial")
  public static class InvalidSourcePathInputException extends Exception
      implements ExceptionWithHumanReadableMessage{

    private InvalidSourcePathInputException(String message, Object...objects) {
      super(String.format(message, objects));
    }

    @Override
    public String getHumanReadableErrorMessage() {
      return getMessage();
    }
  }
}
