/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.haskell;

import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.CxxPlatformUtils;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.rules.AbstractNodeBuilder;
import com.facebook.buck.rules.SourcePath;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

public class HaskellBinaryBuilder
    extends AbstractNodeBuilder<HaskellBinaryDescription.Arg> {

  public HaskellBinaryBuilder(
      BuildTarget target,
      HaskellConfig haskellConfig,
      FlavorDomain<CxxPlatform> cxxPlatforms,
      CxxPlatform defaultCxxPlatform) {
    super(
        new HaskellBinaryDescription(haskellConfig, cxxPlatforms, defaultCxxPlatform),
        target);
  }

  public HaskellBinaryBuilder(BuildTarget target) {
    this(
        target,
        FakeHaskellConfig.DEFAULT,
        CxxPlatformUtils.DEFAULT_PLATFORMS,
        CxxPlatformUtils.DEFAULT_PLATFORM);
  }

  public HaskellBinaryBuilder setSrcs(ImmutableList<SourcePath> srcs) {
    arg.srcs = Optional.of(srcs);
    return this;
  }

  public HaskellBinaryBuilder setCompilerFlags(ImmutableList<String> flags) {
    arg.compilerFlags = Optional.of(flags);
    return this;
  }

  public HaskellBinaryBuilder setDeps(ImmutableSortedSet<BuildTarget> deps) {
    arg.deps = Optional.of(deps);
    return this;
  }

}
