/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.bulkload.pipeline;

import org.apache.ignite.IgniteCheckedException;
import org.jetbrains.annotations.Nullable;

/**
 * A file parsing pipeline block. Accepts an portion of an input and (EOF flag is provided to signify the last block to
 * process) and optionally calls the next block with transformed input (or performs any other handling, such as writing
 * input to internal structures).
 */
public abstract class PipelineBlock<I, O> {
    /** The next block in pipeline or null if this block is a terminator. */
    @Nullable protected PipelineBlock<O, ?> nextBlock;

    /**
     * Creates a pipeline block. The next block is to set using {@link #append(PipelineBlock)} method.
     */
    public PipelineBlock() {
        this.nextBlock = null;
    }

    /**
     * Sets the next block in this block and returns the next block, so the following usage pattern is possible
     * (an example) {@code block1.append(block2).append(block3); }. Block2 here becomes the next for block1,
     * and block3 is the next one for the block2.
     *
     * @param next The next block for the current block.
     * @return The next block.
     */
    public <N> PipelineBlock<O, N> append(PipelineBlock<O, N> next) {
        nextBlock = next;
        return next;
    }

    /**
     * Accepts a portion of input. {@code isEof} parameter should be set if this is a last portion of the input.
     * The method must not be called after the eof (so the call with {@code isEof == true} should be the last one.
     *
     * @param inputPortion Portion of input.
     * @param isEof Is this the last portion.
     */
    public abstract void accept(I inputPortion, boolean isEof) throws IgniteCheckedException;
}
