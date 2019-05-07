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

package org.apache.ignite.internal.processors.diagnostic;

import java.io.File;
import org.apache.ignite.IgniteCheckedException;
import org.apache.ignite.IgniteException;
import org.apache.ignite.internal.GridKernalContext;
import org.apache.ignite.mxbean.BaselineAutoAdjustMXBean;
import org.apache.ignite.mxbean.DiagnosticMXBean;

/**
 * {@link DiagnosticMXBean} implementation.
 */
public class DiagnosticMXBeanImpl implements DiagnosticMXBean {

    private DiagnosticProcessor debug;

    /**
     * @param ctx Context.
     */
    public DiagnosticMXBeanImpl(GridKernalContext ctx) {
        debug = ctx.diagnostic();
    }

    /** {@inheritDoc} */
    @Override public void dumpPageHistory(boolean dumpToFile, boolean dumpToLog, String filePath, long... pageIds) {
        PageHistoryDiagnoster.DiagnosticPageBuilder builder = new PageHistoryDiagnoster.DiagnosticPageBuilder()
            .pageIds(pageIds);

        if (filePath != null)
            builder.folderForDump(new File(filePath));

        if (dumpToFile)
            builder.addAction(DiagnosticProcessor.DiagnosticAction.PRINT_TO_FILE);

        if (dumpToLog)
            builder.addAction(DiagnosticProcessor.DiagnosticAction.PRINT_TO_LOG);

        try {
            debug.dumpPageHistory(builder);
        }
        catch (IgniteCheckedException e) {
            throw new IgniteException(e);
        }
    }

    /** {@inheritDoc} */
    @Override public void dumpPageHistory(boolean dumpToFile, boolean dumpToLog, long... pageIds) {
        dumpPageHistory(dumpToFile, dumpToLog, null, pageIds);
    }
}
