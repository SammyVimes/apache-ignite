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

package org.apache.ignite.internal.commandline.diagnostic;

import java.io.File;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.apache.ignite.cluster.ClusterNode;
import org.apache.ignite.internal.client.GridClient;
import org.apache.ignite.internal.client.GridClientConfiguration;
import org.apache.ignite.internal.commandline.Command;
import org.apache.ignite.internal.commandline.CommandArgIterator;
import org.apache.ignite.internal.commandline.CommandLogger;
import org.apache.ignite.internal.commandline.TaskExecutor;
import org.apache.ignite.internal.visor.diagnostic.VisorPageLocksResult;
import org.apache.ignite.internal.visor.diagnostic.VisorPageLocksTask;
import org.apache.ignite.internal.visor.diagnostic.VisorPageLocksTrackerArgs;

import static org.apache.ignite.internal.commandline.CommandHandler.UTILITY_NAME;
import static org.apache.ignite.internal.commandline.CommandList.DIAGNOSTIC;
import static org.apache.ignite.internal.commandline.diagnostic.DiagnosticSubCommand.PAGE_LOCKS;

/**
 *
 */
public class PageLocksCommand implements Command<PageLocksCommand.Args> {
    /**
     *
     */
    public static final String COMMAND = "dump";

    /**
     *
     */
    private Args args;

    /**
     *
     */
    private CommandLogger logger;

    /**
     *
     */
    private boolean help;

    /** {@inheritDoc} */
    @Override public Object execute(GridClientConfiguration clientCfg, CommandLogger logger) throws Exception {
        this.logger = logger;

        if (help) {
            help = false;

            printUsage(logger);

            return null;
        }

        Set<String> nodeIds = args.nodeIds;

        try (GridClient client = Command.startClient(clientCfg)) {
            if (args.allNodes) {
                client.compute().nodes().forEach(n -> {
                    nodeIds.add(String.valueOf(n.consistentId()));
                    nodeIds.add(n.nodeId().toString());
                });
            }
        }

        VisorPageLocksTrackerArgs taskArg = new VisorPageLocksTrackerArgs(args.op, args.type, args.filePath, nodeIds);

        Map<ClusterNode, VisorPageLocksResult> res;

        try (GridClient client = Command.startClient(clientCfg)) {
            res = TaskExecutor.executeTask(
                client,
                VisorPageLocksTask.class,
                taskArg,
                clientCfg
            );
        }

        printResult(res);

        return res;
    }

    /** {@inheritDoc} */
    @Override public Args arg() {
        return args;
    }

    /** {@inheritDoc} */
    @Override public void parseArguments(CommandArgIterator argIter) {
        if (argIter.hasNextSubArg()) {
            String cmd = argIter.nextArg("").toLowerCase();

            if (COMMAND.equals(cmd)) {
                String type = null;
                String filePath = null;
                boolean allNodes = false;

                Set<String> nodeIds = new TreeSet<>();

                if (argIter.hasNextSubArg()) {
                    String nextArg = argIter.nextArg("").toLowerCase();

                    if ("log".equals(nextArg))
                        type = nextArg;
                    else if (new File(nextArg).isDirectory())
                        filePath = nextArg;

                    if (argIter.hasNextArg()) {
                        nextArg = argIter.nextArg("").toLowerCase();

                        if ("-a".equals(nextArg) || "--all".equals(nextArg))
                            allNodes = true;
                        else {
                            do {
                                nodeIds.add(nextArg);
                            }
                            while (argIter.hasNextArg());
                        }
                    }
                }

                args = new Args(COMMAND, type, filePath, allNodes, nodeIds);
            }
            else
                help = true;
        }
    }

    /** {@inheritDoc} */
    @Override public void printUsage(CommandLogger logger) {
        logger.log("View pages locks state information on the node or nodes.");
        logger.log("Use -a or --all for dump locks on all nodes in cluster in the end of command line.");
        logger.log(CommandLogger.join(" ",
            "Example:\n" + UTILITY_NAME, DIAGNOSTIC, PAGE_LOCKS, COMMAND, " -a or --all"));

        logger.log("You can specify set of node via list ids in the end of command line.");
        logger.log(CommandLogger.join(" ",
            "Example:\n" + UTILITY_NAME, DIAGNOSTIC, PAGE_LOCKS, COMMAND, " {UUID} {UUID} {UUID} ... " +
                "or {ConsistentId} {ConsistentId} {ConsistentId}... " +
                "or {UUID} {ConsistentId} {UUID}..."));
        logger.log(CommandLogger.join(" ",
            UTILITY_NAME, DIAGNOSTIC, PAGE_LOCKS, COMMAND, "// Save page locks dump to file generated in IGNITE_HOME/work directory."));
        logger.log(CommandLogger.join(" ",
            UTILITY_NAME, DIAGNOSTIC, PAGE_LOCKS, COMMAND + " log", "// Pring page locks dump to console on the node or nodes."));
        logger.log(CommandLogger.join(" ",
            UTILITY_NAME, DIAGNOSTIC, PAGE_LOCKS, COMMAND + " {path}", "// Save page locks dump to specific path."));
        logger.nl();
    }

    /**
     * @param res Result.
     */
    private void printResult(Map<ClusterNode, VisorPageLocksResult> res) {
        res.forEach((n, res0) -> {
            logger.log(n.id() + " (" + n.consistentId() + ") " + res0.result());
        });
    }

    /**
     *
     */
    public static class Args {
        /**
         *
         */
        private final String op;
        /**
         *
         */
        private final String type;
        /**
         *
         */
        private final String filePath;
        /**
         *
         */
        private final boolean allNodes;
        /**
         *
         */
        private final Set<String> nodeIds;

        /**
         * @param op Operation.
         * @param type Type.
         * @param filePath File path.
         * @param nodeIds Node ids.
         */
        public Args(String op, String type, String filePath, boolean allNodes, Set<String> nodeIds) {
            this.op = op;
            this.type = type;
            this.filePath = filePath;
            this.allNodes = allNodes;
            this.nodeIds = nodeIds;
        }
    }
}
