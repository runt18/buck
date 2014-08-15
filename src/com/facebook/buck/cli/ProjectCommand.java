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

package com.facebook.buck.cli;

import com.facebook.buck.apple.XcodeProjectConfig;
import com.facebook.buck.apple.XcodeProjectConfigDescription;
import com.facebook.buck.apple.XcodeWorkspaceConfigDescription;
import com.facebook.buck.apple.xcode.ProjectGenerator;
import com.facebook.buck.apple.xcode.SeparatedProjectsGenerator;
import com.facebook.buck.apple.xcode.WorkspaceAndProjectGenerator;
import com.facebook.buck.command.Project;
import com.facebook.buck.java.JavaLibraryDescription;
import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetException;
import com.facebook.buck.parser.AssociatedRulePredicate;
import com.facebook.buck.parser.AssociatedRulePredicates;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.parser.PartialGraph;
import com.facebook.buck.parser.RuleJsonPredicate;
import com.facebook.buck.parser.RuleJsonPredicates;
import com.facebook.buck.rules.ActionGraph;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.ProjectConfig;
import com.facebook.buck.rules.ProjectConfigDescription;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProcessExecutor;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.io.Files;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

public class ProjectCommand extends AbstractCommandRunner<ProjectCommandOptions> {

  private static final Logger LOG = Logger.get(ProjectCommand.class);

  /**
   * Include java library targets (and android library targets) that use annotation
   * processing.  The sources generated by these annotation processors is needed by
   * IntelliJ.
   */
  private static final RuleJsonPredicate ANNOTATION_PREDICATE = new RuleJsonPredicate() {
    @Override
     public boolean isMatch(
        Map<String, Object> rawParseData,
        BuildRuleType buildRuleType,
        BuildTarget buildTarget) {
      Object rawValue = rawParseData.get(JavaLibraryDescription.ANNOTATION_PROCESSORS);
      return ((rawValue instanceof Iterable) && !Iterables.isEmpty((Iterable<?>) rawValue));
    }
  };

  private static final AssociatedRulePredicate ASSOCIATED_PROJECTS_PREDICATE =
      new AssociatedRulePredicate() {
        @Override
        public boolean isMatch(BuildRule buildRule, ActionGraph actionGraph) {
          ProjectConfig projectConfig;
          if (buildRule instanceof ProjectConfig) {
            projectConfig = (ProjectConfig) buildRule;
          } else {
            return false;
          }

          BuildRule projectRule = projectConfig.getProjectRule();
          return (projectRule != null &&
              actionGraph.findBuildRuleByTarget(projectRule.getBuildTarget()) != null);
        }
      };

  private static final AssociatedRulePredicate ASSOCIATED_XCODE_PROJECTS_PREDICATE =
      new AssociatedRulePredicate() {
        @Override
        public boolean isMatch(
            BuildRule buildRule, ActionGraph actionGraph) {
          XcodeProjectConfig xcodeProjectConfig;
          if (buildRule instanceof XcodeProjectConfig) {
            xcodeProjectConfig = (XcodeProjectConfig) buildRule;
          } else {
            return false;
          }

          for (BuildRule includedBuildRule : xcodeProjectConfig.getRules()) {
            if (actionGraph.findBuildRuleByTarget(includedBuildRule.getBuildTarget()) != null) {
              return true;
            }
          }

          return false;
        }
      };

  public ProjectCommand(CommandRunnerParams params) {
    super(params);
  }

  @Override
  ProjectCommandOptions createOptions(BuckConfig buckConfig) {
    return new ProjectCommandOptions(buckConfig);
  }

  @Override
  int runCommandWithOptionsInternal(ProjectCommandOptions options)
      throws IOException, InterruptedException {
    switch (options.getIde()) {
      case "intellij":
        return runIntellijProjectGenerator(options);
      case "xcode":
        return runXcodeProjectGenerator(options);
      default:
        throw new HumanReadableException(String.format(
            "Unknown IDE `%s` in .buckconfig", options.getIde()));
    }
  }

  /**
   * Run intellij specific project generation actions.
   */
  int runIntellijProjectGenerator(ProjectCommandOptions options)
      throws IOException, InterruptedException {
    // Create a PartialGraph that only contains targets that can be represented as IDE
    // configuration files.
    PartialGraph partialGraph;

    try {
      partialGraph = createPartialGraph(
          RuleJsonPredicates.matchBuildRuleType(ProjectConfigDescription.TYPE),
          ASSOCIATED_PROJECTS_PREDICATE,
          options);
    } catch (BuildTargetException | BuildFileParseException e) {
      throw new HumanReadableException(e);
    }

    ExecutionContext executionContext = createExecutionContext(options,
        partialGraph.getActionGraph());

    Project project = new Project(partialGraph,
        options.getBasePathToAliasMap(),
        options.getJavaPackageFinder(),
        executionContext,
        getProjectFilesystem(),
        options.getPathToDefaultAndroidManifest(),
        options.getPathToPostProcessScript(),
        options.getBuckConfig().getPythonInterpreter(),
        getObjectMapper());

    File tempDir = Files.createTempDir();
    File tempFile = new File(tempDir, "project.json");
    int exitCode;
    try {
      exitCode = createIntellijProject(project,
          tempFile,
          executionContext.getProcessExecutor(),
          !options.getArgumentsFormattedAsBuildTargets().isEmpty(),
          console.getStdOut(),
          console.getStdErr());
      if (exitCode != 0) {
        return exitCode;
      }

      List<String> additionalInitialTargets = ImmutableList.of();
      if (options.shouldProcessAnnotations()) {
        try {
          additionalInitialTargets = getAnnotationProcessingTargets(options);
        } catch (BuildTargetException | BuildFileParseException e) {
          throw new HumanReadableException(e);
        }
      }

      // Build initial targets.
      if (options.hasInitialTargets() || !additionalInitialTargets.isEmpty()) {
        BuildCommand buildCommand = new BuildCommand(getCommandRunnerParams());
        BuildCommandOptions buildOptions =
            options.createBuildCommandOptionsWithInitialTargets(additionalInitialTargets);


        exitCode = runBuildCommand(buildCommand, buildOptions);
        if (exitCode != 0) {
          return exitCode;
        }
      }
    } finally {
      // Either leave project.json around for debugging or delete it on exit.
      if (console.getVerbosity().shouldPrintOutput()) {
        getStdErr().printf("project.json was written to %s", tempFile.getAbsolutePath());
      } else {
        tempFile.delete();
        tempDir.delete();
      }
    }

    if (options.getArguments().isEmpty()) {
      String greenStar = console.getAnsi().asHighlightedSuccessText(" * ");
      getStdErr().printf(
          console.getAnsi().asHighlightedSuccessText("=== Did you know ===") + "\n" +
              greenStar + "You can run `buck project <target>` to generate a minimal project " +
              "just for that target.\n" +
              greenStar + "This will make your IDE faster when working on large projects.\n" +
              greenStar + "See buck project --help for more info.\n" +
              console.getAnsi().asHighlightedSuccessText(
                  "--=* Knowing is half the battle!") + "\n");
    }

    return 0;
  }

  ImmutableList<String> getAnnotationProcessingTargets(ProjectCommandOptions options)
      throws BuildTargetException, BuildFileParseException, IOException, InterruptedException {
    return ImmutableList.copyOf(
        Iterables.transform(
            createPartialGraph(
                ANNOTATION_PREDICATE,
                AssociatedRulePredicates.alwaysTrue(),
                options).getTargets(),
            new Function<BuildTarget, String>() {
              @Override
              public String apply(BuildTarget target) {
                return target.getFullyQualifiedName();
              }
            }));
  }

  /**
   * Calls {@link Project#createIntellijProject}
   *
   * This is factored into a separate method for testing purposes.
   */
  @VisibleForTesting
  int createIntellijProject(Project project,
      File jsonTemplate,
      ProcessExecutor processExecutor,
      boolean generateMinimalProject,
      PrintStream stdOut,
      PrintStream stdErr)
      throws IOException, InterruptedException {
    return project.createIntellijProject(
        jsonTemplate,
        processExecutor,
        generateMinimalProject,
        stdOut,
        stdErr);
  }

  /**
   * Run xcode specific project generation actions.
   */
  int runXcodeProjectGenerator(ProjectCommandOptions options)
      throws IOException, InterruptedException {
    PartialGraph partialGraph;
    try {
      final ImmutableSet<String> defaultExcludePaths = options.getDefaultExcludePaths();
      final ImmutableSet<BuildTarget> passedInTargetsSet =
        ImmutableSet.copyOf(getBuildTargets(options.getArgumentsFormattedAsBuildTargets()));

      partialGraph = createPartialGraph(
          new RuleJsonPredicate() {
            @Override
            public boolean isMatch(
                Map<String, Object> rawParseData,
                BuildRuleType buildRuleType,
                BuildTarget buildTarget) {
              if (XcodeProjectConfigDescription.TYPE != buildRuleType) {
                return false;
              }
              String targetName = buildTarget.getFullyQualifiedName();
              for (String prefix : defaultExcludePaths) {
                if (targetName.startsWith("//" + prefix) &&
                    !passedInTargetsSet.contains(buildTarget)) {
                  LOG.debug(
                      "Ignoring build target %s (exclude_paths contains %s)",
                      buildTarget,
                      prefix);
                  return false;
                }
              }
              return true;
            }
          },
          ASSOCIATED_XCODE_PROJECTS_PREDICATE,
          options);
    } catch (BuildTargetException | BuildFileParseException e) {
      throw new HumanReadableException(e);
    }

    ImmutableSet<BuildTarget> passedInTargetsSet;

    try {
      ImmutableSet<String> argumentsAsBuildTargets = options.getArgumentsFormattedAsBuildTargets();
      passedInTargetsSet = getBuildTargets(argumentsAsBuildTargets);
    } catch (NoSuchBuildTargetException e) {
      throw new HumanReadableException(e);
    }

    ExecutionContext executionContext = createExecutionContext(options,
        partialGraph.getActionGraph());

    ImmutableSet.Builder<ProjectGenerator.Option> optionsBuilder = ImmutableSet.builder();
    if (options.getReadOnly()) {
      optionsBuilder.add(ProjectGenerator.Option.GENERATE_READ_ONLY_FILES);
    }

    if (options.getCombinedProject() != null) {
      // Generate a single project containing a target and all its dependencies and tests.
      ProjectGenerator projectGenerator = new ProjectGenerator(
          partialGraph,
          passedInTargetsSet,
          getProjectFilesystem(),
          executionContext,
          getProjectFilesystem().getPathForRelativePath(Paths.get("_gen")),
          "GeneratedProject",
          optionsBuilder.addAll(ProjectGenerator.COMBINED_PROJECT_OPTIONS).build());
      projectGenerator.createXcodeProjects();
    } else if (options.getWorkspaceAndProjects()) {
      ImmutableSet<BuildTarget> targets;
      if (passedInTargetsSet.isEmpty()) {
        targets = getAllTargetsOfType(
            partialGraph.getActionGraph().getNodes(),
            XcodeWorkspaceConfigDescription.TYPE);
      } else {
        targets = passedInTargetsSet;
      }
      WorkspaceAndProjectGenerator generator = new WorkspaceAndProjectGenerator(
          getProjectFilesystem(),
          partialGraph,
          executionContext,
          targets,
          optionsBuilder.build());
      generator.generateWorkspacesAndDependentProjects();
    } else {
      // Generate projects based on xcode_project_config rules, and place them in the same directory
      // as the Buck file.

      ImmutableSet<BuildTarget> targets;
      if (passedInTargetsSet.isEmpty()) {
        targets = getAllTargetsOfType(
            partialGraph.getActionGraph().getNodes(),
            XcodeProjectConfigDescription.TYPE);
      } else {
        targets = passedInTargetsSet;
      }

      SeparatedProjectsGenerator projectGenerator = new SeparatedProjectsGenerator(
          getProjectFilesystem(),
          partialGraph,
          executionContext,
          targets,
          optionsBuilder.build());
      ImmutableSet<Path> generatedProjectPaths = projectGenerator.generateProjects();
      for (Path path : generatedProjectPaths) {
        console.getStdOut().println(path.toString());
      }
    }

    return 0;
  }

  private static final ImmutableSet<BuildTarget> getAllTargetsOfType(
      Iterable<BuildRule> nodes,
      BuildRuleType type) {
    ImmutableSet.Builder<BuildTarget> targetsBuilder = ImmutableSet.builder();
    for (BuildRule node : nodes) {
      if (node.getType() == type) {
        targetsBuilder.add(node.getBuildTarget());
      }
    }
    return targetsBuilder.build();
  }

  /**
   * Calls {@link BuildCommand#runCommandWithOptions}
   *
   * This is factored into a separate method for testing purposes.
   */
  @VisibleForTesting
  int runBuildCommand(BuildCommand buildCommand, BuildCommandOptions options)
      throws IOException, InterruptedException {
    return buildCommand.runCommandWithOptions(options);
  }

  @VisibleForTesting
  PartialGraph createPartialGraph(
      RuleJsonPredicate rulePredicate,
      AssociatedRulePredicate associatedRulePredicate,
      ProjectCommandOptions options)
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    ImmutableSet<String> argumentsAsBuildTargets = options.getArgumentsFormattedAsBuildTargets();

    if (argumentsAsBuildTargets.isEmpty()) {
      return PartialGraph.createPartialGraph(
          rulePredicate,
          getProjectFilesystem(),
          options.getDefaultIncludes(),
          getParser(),
          getBuckEventBus(),
          console,
          environment);
    } else {
      // If build targets were specified, generate a partial intellij project that contains the
      // files needed to build the build targets specified.
      ImmutableSet<BuildTarget> targets = getBuildTargets(argumentsAsBuildTargets);

      ImmutableList.Builder<RuleJsonPredicate> predicateBuilder = ImmutableList.builder();
      ImmutableList.Builder<AssociatedRulePredicate> associatedRulePredicateBuilder =
          ImmutableList.builder();

      if (options.isWithTests()) {
        predicateBuilder.add(RuleJsonPredicates.isTestRule());
        associatedRulePredicateBuilder.add(AssociatedRulePredicates.associatedTestsRules());
      }

      predicateBuilder.add(rulePredicate);
      associatedRulePredicateBuilder.add(associatedRulePredicate);

      return PartialGraph.createPartialGraphFromRootsWithAssociatedRules(
          targets,
          predicateBuilder.build(),
          associatedRulePredicateBuilder.build(),
          getProjectFilesystem(),
          options.getDefaultIncludes(),
          getParser(),
          getBuckEventBus(),
          console,
          environment);
    }
  }

  @Override
  String getUsageIntro() {
    return "generates project configuration files for an IDE";
  }
}
