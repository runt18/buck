/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.jvm.java.FakeJavaLibrary;
import com.facebook.buck.jvm.java.JavaLibrary;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.hash.HashCode;

import org.junit.Test;

public class DexWithClassesTest {

  @Test
  public void testIntermediateDexRuleToDexWithClasses() {
        SourcePathResolver resolver =
 new SourcePathResolver(
         new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer())
     );
    BuildTarget javaLibraryTarget = BuildTargetFactory.newInstance("//java/com/example:lib");
    JavaLibrary javaLibrary = new FakeJavaLibrary(javaLibraryTarget, resolver);

    BuildTarget buildTarget = BuildTargetFactory.newInstance("//java/com/example:lib#dex");
    BuildRuleParams params = new FakeBuildRuleParamsBuilder(buildTarget).build();
    DexProducedFromJavaLibrary dexFromJavaLibrary =
        new DexProducedFromJavaLibrary(params, resolver, javaLibrary);
    dexFromJavaLibrary.getBuildOutputInitializer().setBuildOutput(
        new DexProducedFromJavaLibrary.BuildOutput(
            /* linearAllocEstimate */ 1600,
            /* classNamesToHashes */ ImmutableSortedMap.of(
                "com/example/Main",
                HashCode.fromString(Strings.repeat("cafebabe", 5)))));

    DexWithClasses dexWithClasses = DexWithClasses.TO_DEX_WITH_CLASSES.apply(dexFromJavaLibrary);
    assertEquals(
        BuildTargets.getGenPath(buildTarget, "%s.dex.jar"),
        dexWithClasses.getPathToDexFile());
    assertEquals(ImmutableSet.of("com/example/Main"), dexWithClasses.getClassNames());
    assertEquals(1600, dexWithClasses.getSizeEstimate());
  }

  @Test
  public void testIntermediateDexRuleToDexWithClassesWhenIntermediateDexHasNoClasses() {
    SourcePathResolver resolver = new SourcePathResolver(
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer())
    );
    BuildTarget javaLibraryTarget = BuildTargetFactory.newInstance("//java/com/example:lib");
    JavaLibrary javaLibrary = new FakeJavaLibrary(javaLibraryTarget, resolver);

    BuildTarget buildTarget = BuildTargetFactory.newInstance("//java/com/example:lib#dex");
    BuildRuleParams params = new FakeBuildRuleParamsBuilder(buildTarget).build();
    DexProducedFromJavaLibrary dexFromJavaLibrary =
        new DexProducedFromJavaLibrary(params, resolver, javaLibrary);
    dexFromJavaLibrary.getBuildOutputInitializer().setBuildOutput(
        new DexProducedFromJavaLibrary.BuildOutput(
            /* linearAllocEstimate */ 1600,
            /* classNamesToHashes */ ImmutableSortedMap.<String, HashCode>of()));

    DexWithClasses dexWithClasses = DexWithClasses.TO_DEX_WITH_CLASSES.apply(dexFromJavaLibrary);
    assertNull(
        "If the JavaLibraryRule does not produce any .class files, " +
            "then DexWithClasses.TO_DEX_WITH_CLASSES should return null.",
        dexWithClasses);
  }
}
