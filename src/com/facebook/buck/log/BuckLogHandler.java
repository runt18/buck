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

package com.facebook.buck.log;

import com.facebook.buck.util.BuckConstant;

import java.io.IOException;
import java.util.logging.FileHandler;

/**
 * A subclass of {@link FileHandler} using a predefined pattern to determine the Buck output logs.
 */
public class BuckLogHandler extends FileHandler {

  public BuckLogHandler() throws IOException {
    super(BuckConstant.getLogPath().resolve("buck-%g.log").toString());
  }

}
