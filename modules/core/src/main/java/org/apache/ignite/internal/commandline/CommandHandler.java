/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.commandline;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.apache.ignite.cluster.ClusterNode;
import org.apache.ignite.internal.client.GridClient;
import org.apache.ignite.internal.client.GridClientAuthenticationException;
import org.apache.ignite.internal.client.GridClientClosedException;
import org.apache.ignite.internal.client.GridClientClusterState;
import org.apache.ignite.internal.client.GridClientCompute;
import org.apache.ignite.internal.client.GridClientConfiguration;
import org.apache.ignite.internal.client.GridClientDisconnectedException;
import org.apache.ignite.internal.client.GridClientException;
import org.apache.ignite.internal.client.GridClientFactory;
import org.apache.ignite.internal.client.GridClientHandshakeException;
import org.apache.ignite.internal.client.GridClientNode;
import org.apache.ignite.internal.client.GridServerUnreachableException;
import org.apache.ignite.internal.client.impl.connection.GridClientConnectionResetException;
import org.apache.ignite.internal.util.typedef.F;
import org.apache.ignite.internal.util.typedef.X;
import org.apache.ignite.internal.util.typedef.internal.U;
import org.apache.ignite.internal.visor.VisorTaskArgument;
import org.apache.ignite.internal.visor.baseline.VisorBaselineNode;
import org.apache.ignite.internal.visor.baseline.VisorBaselineOperation;
import org.apache.ignite.internal.visor.baseline.VisorBaselineTask;
import org.apache.ignite.internal.visor.baseline.VisorBaselineTaskArg;
import org.apache.ignite.internal.visor.baseline.VisorBaselineTaskResult;
import org.apache.ignite.internal.visor.tx.VisorTxInfo;
import org.apache.ignite.internal.visor.tx.VisorTxOperation;
import org.apache.ignite.internal.visor.tx.VisorTxProjection;
import org.apache.ignite.internal.visor.tx.VisorTxSortOrder;
import org.apache.ignite.internal.visor.tx.VisorTxTask;
import org.apache.ignite.internal.visor.tx.VisorTxTaskArg;
import org.apache.ignite.internal.visor.tx.VisorTxTaskResult;
import org.apache.ignite.lang.IgniteClosure;
import org.apache.ignite.plugin.security.SecurityCredentials;
import org.apache.ignite.plugin.security.SecurityCredentialsBasicProvider;

import static org.apache.ignite.internal.IgniteVersionUtils.ACK_VER_STR;
import static org.apache.ignite.internal.IgniteVersionUtils.COPYRIGHT;
import static org.apache.ignite.internal.commandline.Command.ACTIVATE;
import static org.apache.ignite.internal.commandline.Command.BASELINE;
import static org.apache.ignite.internal.commandline.Command.DEACTIVATE;
import static org.apache.ignite.internal.commandline.Command.STATE;
import static org.apache.ignite.internal.commandline.Command.TX;
import static org.apache.ignite.internal.visor.baseline.VisorBaselineOperation.ADD;
import static org.apache.ignite.internal.visor.baseline.VisorBaselineOperation.COLLECT;
import static org.apache.ignite.internal.visor.baseline.VisorBaselineOperation.REMOVE;
import static org.apache.ignite.internal.visor.baseline.VisorBaselineOperation.SET;
import static org.apache.ignite.internal.visor.baseline.VisorBaselineOperation.VERSION;

/**
 * Class that execute several commands passed via command line.
 */
public class CommandHandler {
    /** Logger. */
    private static final Logger log = Logger.getLogger(CommandHandler.class.getName());

    /** */
    static final String DFLT_HOST = "127.0.0.1";

    /** */
    static final String DFLT_PORT = "11211";

    /** */
    private static final String CMD_HELP = "--help";

    /** */
    private static final String CMD_HOST = "--host";

    /** */
    private static final String CMD_PORT = "--port";

    /** */
    private static final String CMD_PASSWORD = "--password";

    /** */
    private static final String CMD_USER = "--user";

    /** */
    public static final String CONFIRM_MSG = "yes";

    /** */
    private static final String BASELINE_ADD = "add";

    /** */
    private static final String BASELINE_REMOVE = "remove";

    /** */
    private static final String BASELINE_COLLECT = "collect";

    /** */
    private static final String BASELINE_SET = "set";

    /** */
    private static final String BASELINE_SET_VERSION = "version";

    /** */
    private static final String DELIM = "--------------------------------------------------------------------------------";

    /** Force option is used for auto confirmation. */
    private static final String CMD_FORCE = "--force";

    /** */
    public static final int EXIT_CODE_OK = 0;

    /** */
    public static final int EXIT_CODE_INVALID_ARGUMENTS = 1;

    /** */
    public static final int EXIT_CODE_CONNECTION_FAILED = 2;

    /** */
    public static final int ERR_AUTHENTICATION_FAILED = 3;

    /** */
    public static final int EXIT_CODE_UNEXPECTED_ERROR = 4;

    /** */
    private static final Scanner IN = new Scanner(System.in);

    /** */
    private static final String TX_LIMIT = "limit";

    /** */
    private static final String TX_ORDER = "order";

    /** */
    private static final String TX_SERVERS = "servers";

    /** */
    private static final String TX_CLIENTS = "clients";

    /** */
    private static final String TX_DURATION = "minDuration";

    /** */
    private static final String TX_SIZE = "minSize";

    /** */
    private static final String TX_LABEL = "label";

    /** */
    private static final String TX_NODES = "nodes";

    /** */
    private static final String TX_XID = "xid";

    /** */
    private static final String TX_KILL = "kill";

    /** */
    private Iterator<String> argsIt;

    /** */
    private String peekedArg;

    /** */
    private Object lastOperationResult;

    /**
     * Output specified string to console.
     *
     * @param s String to output.
     */
    private void log(String s) {
        System.out.println(s);
    }

    /**
     * Provides a prompt, then reads a single line of text from the console.
     *
     * @param prompt text
     * @return A string containing the line read from the console
     */
    private String readLine(String prompt) {
        System.out.print(prompt);

        return IN.nextLine();
    }

    /**
     * Output empty line.
     */
    private void nl() {
        System.out.println("");
    }

    /**
     * Print error to console.
     *
     * @param errCode Error code to return.
     * @param s Optional message.
     * @param e Error to print.
     */
    private int error(int errCode, String s, Throwable e) {
        if (!F.isEmpty(s))
            log(s);

        String msg = e.getMessage();

        if (F.isEmpty(msg))
            msg = e.getClass().getName();

        if (msg.startsWith("Failed to handle request")) {
            int p = msg.indexOf("err=");

            msg = msg.substring(p + 4, msg.length() - 1);
        }

        log("Error: " + msg);

        return errCode;
    }

    /**
     * Requests interactive user confirmation if forthcoming operation is dangerous.
     *
     * @param args Arguments.
     * @return {@code true} if operation confirmed (or not needed), {@code false} otherwise.
     */
    private boolean confirm(Arguments args) {
        String prompt = confirmationPrompt(args);

        if (prompt == null)
            return true;

        return CONFIRM_MSG.equalsIgnoreCase(readLine(prompt));
    }

    /**
     * @param args Arguments.
     * @return Prompt text if confirmation needed, otherwise {@code null}.
     */
    private String confirmationPrompt(Arguments args) {
        if (args.force())
            return null;

        String str = null;

        switch (args.command()) {
            case DEACTIVATE:
                str = "Warning: the command will deactivate a cluster.";
                break;

            case BASELINE:
                if (!BASELINE_COLLECT.equals(args.baselineAction()))
                    str = "Warning: the command will perform changes in baseline.";
                break;

            case TX:
                if (args.transactionArguments().getOperation() == VisorTxOperation.KILL)
                    str = "Warning: the command will kill some transactions.";
                break;
        }

        return str == null ? null : str + "\nPress 'yes' to continue . . . ";
    }

    /**
     * @param rawArgs Arguments.
     */
    private void initArgIterator(List<String> rawArgs) {
        argsIt = rawArgs.iterator();
        peekedArg = null;
    }

    /**
     * @return Returns {@code true} if the iteration has more elements.
     */
    private boolean hasNextArg() {
        return peekedArg != null || argsIt.hasNext();
    }

    /**
     * Activate cluster.
     *
     * @param client Client.
     * @throws GridClientException If failed to activate.
     */
    private void activate(GridClient client) throws Throwable {
        try {
            GridClientClusterState state = client.state();

            state.active(true);

            log("Cluster activated");
        }
        catch (Throwable e) {
            log("Failed to activate cluster.");

            throw e;
        }
    }

    /**
     * Deactivate cluster.
     *
     * @param client Client.
     * @throws Throwable If failed to deactivate.
     */
    private void deactivate(GridClient client) throws Throwable {
        try {
            GridClientClusterState state = client.state();

            state.active(false);

            log("Cluster deactivated");
        }
        catch (Throwable e) {
            log("Failed to deactivate cluster.");

            throw e;
        }
    }

    /**
     * Print cluster state.
     *
     * @param client Client.
     * @throws Throwable If failed to print state.
     */
    private void state(GridClient client) throws Throwable {
        try {
            GridClientClusterState state = client.state();

            log("Cluster is " + (state.active() ? "active" : "inactive"));
        }
        catch (Throwable e) {
            log("Failed to get cluster state.");

            throw e;
        }
    }

    /**
     * @param client Client.
     * @param arg Task argument.
     * @return Task result.
     * @throws GridClientException If failed to execute task.
     */
    private Map<UUID, VisorTxTaskResult> executeTransactionsTask(GridClient client,
        VisorTxTaskArg arg) throws GridClientException {

        return executeTask(client, VisorTxTask.class, arg);
    }

    /**
     *
     * @param client Client.
     * @param taskCls Task class.
     * @param taskArgs Task arguments.
     * @return Task result.
     * @throws GridClientException If failed to execute task.
     */
    private <R> R executeTask(GridClient client, Class<?> taskCls, Object taskArgs) throws GridClientException {
        GridClientCompute compute = client.compute();

        GridClientNode node = getBalancedNode(compute);

        return compute.execute(taskCls.getName(),
            new VisorTaskArgument<>(node.nodeId(), taskArgs, false));
    }

    /**
     * @param compute instance
     * @return balanced node
     */
    private GridClientNode getBalancedNode(GridClientCompute compute) throws GridClientException {
        List<GridClientNode> nodes = new ArrayList<>();

        for (GridClientNode node : compute.nodes())
            if (node.connectable())
                nodes.add(node);

        if (F.isEmpty(nodes))
            throw new GridClientDisconnectedException("Connectable node not found", null);

        return compute.balancer().balancedNode(nodes);
    }

    /**
     * Change baseline.
     *
     * @param client Client.
     * @param baselineAct Baseline action to execute.  @throws GridClientException If failed to execute baseline action.
     * @param baselineArgs Baseline action arguments.
     * @throws Throwable If failed to execute baseline action.
     */
    private void baseline(GridClient client, String baselineAct, String baselineArgs) throws Throwable {
        switch (baselineAct) {
            case BASELINE_ADD:
                baselineAdd(client, baselineArgs);
                break;

            case BASELINE_REMOVE:
                baselineRemove(client, baselineArgs);
                break;

            case BASELINE_SET:
                baselineSet(client, baselineArgs);
                break;

            case BASELINE_SET_VERSION:
                baselineVersion(client, baselineArgs);
                break;

            case BASELINE_COLLECT:
                baselinePrint(client);
                break;
        }
    }

    /**
     * Prepare task argument.
     *
     * @param op Operation.
     * @param s Argument from command line.
     * @return Task argument.
     */
    private VisorBaselineTaskArg arg(VisorBaselineOperation op, String s) {
        switch (op) {
            case ADD:
            case REMOVE:
            case SET:
                List<String> consistentIds = getConsistentIds(s);

                return new VisorBaselineTaskArg(op, -1, consistentIds);

            case VERSION:
                try {
                    long topVer = Long.parseLong(s);

                    return new VisorBaselineTaskArg(op, topVer, null);
                }
                catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Invalid topology version: " + s, e);
                }

            default:
                return new VisorBaselineTaskArg(op, -1, null);
        }
    }

    /**
     * @param s String of consisted ids delimited by comma.
     * @return List of consistent ids.
     */
    private List<String> getConsistentIds(String s) {
        if (F.isEmpty(s))
            throw new IllegalArgumentException("Empty list of consistent IDs");

        List<String> consistentIds = new ArrayList<>();

        for (String consistentId : s.split(","))
            consistentIds.add(consistentId.trim());

        return consistentIds;
    }

    /**
     * Print baseline topology.
     *
     * @param res Task result with baseline topology.
     */
    private void baselinePrint0(VisorBaselineTaskResult res) {
        log("Cluster state: " + (res.isActive() ? "active" : "inactive"));
        log("Current topology version: " + res.getTopologyVersion());
        nl();

        Map<String, VisorBaselineNode> baseline = res.getBaseline();

        Map<String, VisorBaselineNode> servers = res.getServers();

        if (F.isEmpty(baseline))
            log("Baseline nodes not found.");
        else {
            log("Baseline nodes:");

            for(VisorBaselineNode node : baseline.values()) {
                log("    ConsistentID=" + node.getConsistentId() + ", STATE=" +
                    (servers.containsKey(node.getConsistentId()) ? "ONLINE" : "OFFLINE"));
            }

            log(DELIM);
            log("Number of baseline nodes: " + baseline.size());

            nl();

            List<VisorBaselineNode> others = new ArrayList<>();

            for (VisorBaselineNode node : servers.values()) {
                if (!baseline.containsKey(node.getConsistentId()))
                    others.add(node);
            }

            if (F.isEmpty(others))
                log("Other nodes not found.");
            else {
                log("Other nodes:");

                for(VisorBaselineNode node : others)
                    log("    ConsistentID=" + node.getConsistentId());

                log("Number of other nodes: " + others.size());
            }
        }
    }

    /**
     * Print current baseline.
     *
     * @param client Client.
     */
    private void baselinePrint(GridClient client) throws GridClientException {
        VisorBaselineTaskResult res = executeTask(client, VisorBaselineTask.class, arg(COLLECT, ""));

        baselinePrint0(res);
    }

    /**
     * Add nodes to baseline.
     *
     * @param client Client.
     * @param baselineArgs Baseline action arguments.
     * @throws Throwable If failed to add nodes to baseline.
     */
    private void baselineAdd(GridClient client, String baselineArgs) throws Throwable {
        try {
            VisorBaselineTaskResult res = executeTask(client, VisorBaselineTask.class, arg(ADD, baselineArgs));

            baselinePrint0(res);
        }
        catch (Throwable e) {
            log("Failed to add nodes to baseline.");

            throw e;
        }
    }

    /**
     * Remove nodes from baseline.
     *
     * @param client Client.
     * @param consistentIds Consistent IDs.
     * @throws Throwable If failed to remove nodes from baseline.
     */
    private void baselineRemove(GridClient client, String consistentIds) throws Throwable {
        try {
            VisorBaselineTaskResult res = executeTask(client, VisorBaselineTask.class, arg(REMOVE, consistentIds));

            baselinePrint0(res);
        }
        catch (Throwable e) {
            log("Failed to remove nodes from baseline.");

            throw e;
        }
    }

    /**
     * Set baseline.
     *
     * @param client Client.
     * @param consistentIds Consistent IDs.
     * @throws Throwable If failed to set baseline.
     */
    private void baselineSet(GridClient client, String consistentIds) throws Throwable {
        try {
            VisorBaselineTaskResult res = executeTask(client, VisorBaselineTask.class, arg(SET, consistentIds));

            baselinePrint0(res);
        }
        catch (Throwable e) {
            log("Failed to set baseline.");

            throw e;
        }
    }

    /**
     * Set baseline by topology version.
     *
     * @param client Client.
     * @param arg Argument from command line.
     */
    private void baselineVersion(GridClient client, String arg) throws GridClientException {
        try {
            VisorBaselineTaskResult res = executeTask(client, VisorBaselineTask.class, arg(VERSION, arg));

            baselinePrint0(res);
        }
        catch (Throwable e) {
            log("Failed to set baseline with specified topology version.");

            throw e;
        }
    }

    /**
     * Dump transactions information.
     *
     * @param client Client.
     * @param arg Transaction search arguments
     */
    private void transactions(GridClient client, VisorTxTaskArg arg) throws GridClientException {
        try {
            Map<ClusterNode, VisorTxTaskResult> res = executeTask(client, VisorTxTask.class, arg);

            lastOperationResult = res;

            if (res.isEmpty())
                log("Nothing found.");
            else if (arg.getOperation() == VisorTxOperation.KILL)
                log("Killed transactions:");
            else
                log("Matching transactions:");

            for (Map.Entry<ClusterNode, VisorTxTaskResult> entry : res.entrySet()) {
                if (entry.getValue().getInfos().isEmpty())
                    continue;

                ClusterNode key = entry.getKey();

                log(key.toString());

                for (VisorTxInfo info : entry.getValue().getInfos())
                    log("    Tx: [xid=" + info.getXid() +
                        ", label=" + info.getLabel() +
                        ", state=" + info.getState() +
                        ", duration=" + info.getDuration() / 1000 +
                        ", isolation=" + info.getIsolation() +
                        ", concurrency=" + info.getConcurrency() +
                        ", timeout=" + info.getTimeout() +
                        ", size=" + info.getSize() +
                        ", dhtNodes=" + F.transform(info.getPrimaryNodes(), new IgniteClosure<UUID, String>() {
                        @Override public String apply(UUID id) {
                            return U.id8(id);
                        }
                    }) +
                        ']');
            }
        }
        catch (Throwable e) {
            log("Failed to perform operation.");

            throw e;
        }
    }

    /**
     * @param e Exception to check.
     * @return {@code true} if specified exception is {@link GridClientAuthenticationException}.
     */
    private boolean isAuthError(Throwable e) {
        return X.hasCause(e, GridClientAuthenticationException.class);
    }

    /**
     * @param e Exception to check.
     * @return {@code true} if specified exception is a connection error.
     */
    private boolean isConnectionError(Throwable e) {
        return e instanceof GridClientClosedException ||
            e instanceof GridClientConnectionResetException ||
            e instanceof GridClientDisconnectedException ||
            e instanceof GridClientHandshakeException ||
            e instanceof GridServerUnreachableException;
    }

    /**
     * Print command usage.
     *
     * @param desc Command description.
     * @param args Arguments.
     */
    private void usage(String desc, Command cmd, String... args) {
        log(desc);
        log("    control.sh [--host HOST_OR_IP] [--port PORT] [--user USER] [--password PASSWORD] " + cmd.text() + String.join("", args));
        nl();
    }

    /**
     * Extract next argument.
     *
     * @param err Error message.
     * @return Next argument value.
     */
    private String nextArg(String err) {
        if (peekedArg != null) {
            String res = peekedArg;

            peekedArg = null;

            return res;
        }

        if (argsIt.hasNext())
            return argsIt.next();

        throw new IllegalArgumentException(err);
    }

    /**
     * Returns the next argument in the iteration, without advancing the iteration.
     *
     * @return Next argument value or {@code null} if no next argument.
     */
    private String peekNextArg() {
        if (peekedArg == null && argsIt.hasNext())
            peekedArg = argsIt.next();

        return peekedArg;
    }

    /**
     * Parses and validates arguments.
     *
     * @param rawArgs Array of arguments.
     * @return Arguments bean.
     * @throws IllegalArgumentException In case arguments aren't valid.
     */
    Arguments parseAndValidate(List<String> rawArgs) {
        String host = DFLT_HOST;

        String port = DFLT_PORT;

        String user = null;

        String pwd = null;

        String baselineAct = "";

        String baselineArgs = "";

        boolean force = false;

        List<Command> commands = new ArrayList<>();

        initArgIterator(rawArgs);

        VisorTxTaskArg txArgs = null;

        while (hasNextArg()) {
            String str = nextArg("").toLowerCase();

            Command cmd = Command.of(str);

            if (cmd != null) {
                switch (cmd) {
                    case ACTIVATE:
                    case DEACTIVATE:
                    case STATE:
                        commands.add(cmd);
                        break;

                    case TX:
                        commands.add(TX);

                        txArgs = parseTransactionArguments();

                        break;

                    case BASELINE:
                        commands.add(BASELINE);

                        baselineAct = BASELINE_COLLECT; //default baseline action

                        str = peekNextArg();

                        if (str != null) {
                            str = str.toLowerCase();

                            if (BASELINE_ADD.equals(str) || BASELINE_REMOVE.equals(str) ||
                                BASELINE_SET.equals(str) || BASELINE_SET_VERSION.equals(str)) {
                                baselineAct = nextArg("Expected baseline action");

                                baselineArgs = nextArg("Expected baseline arguments");
                            }
                        }

                        break;

                    default:
                        throw new IllegalArgumentException("Unexpected command: " + str);
                }
            }
            else {
                switch (str) {
                    case CMD_HOST:
                        host = nextArg("Expected host name");
                        break;

                    case CMD_PORT:
                        port = nextArg("Expected port number");

                        try {
                            int p = Integer.parseInt(port);

                            if (p <= 0 || p > 65535)
                                throw new IllegalArgumentException("Invalid value for port: " + port);
                        }
                        catch (NumberFormatException ignored) {
                            throw new IllegalArgumentException("Invalid value for port: " + port);
                        }
                        break;

                    case CMD_USER:
                        user = nextArg("Expected user name");
                        break;

                    case CMD_PASSWORD:
                        pwd = nextArg("Expected password");
                        break;

                    case CMD_FORCE:
                        force = true;
                        break;

                    default:
                        throw new IllegalArgumentException("Unexpected argument: " + str);
                }
            }
        }

        int sz = commands.size();

        if (sz < 1)
            throw new IllegalArgumentException("No action was specified");

        if (sz > 1)
            throw new IllegalArgumentException("Only one action can be specified, but found: " + sz);

        Command cmd = commands.get(0);

        boolean hasUsr = F.isEmpty(user);
        boolean hasPwd = F.isEmpty(pwd);

        if (hasUsr != hasPwd)
            throw new IllegalArgumentException("Both user and password should be specified");

        return new Arguments(cmd, host, port, user, pwd, baselineAct, baselineArgs, txArgs, force);
    }

    /**
     * @return Transaction arguments.
     */
    private VisorTxTaskArg parseTransactionArguments() {
        VisorTxProjection proj = null;

        Integer limit = null;

        VisorTxSortOrder sortOrder = null;

        Long duration = null;

        Integer size = null;

        String lbRegex = null;

        List<String> consistentIds = null;

        VisorTxOperation op = VisorTxOperation.LIST;

        String xid = null;

        boolean end = false;

        do {
            String str = peekNextArg();

            if (str == null)
                break;

            switch (str) {
                case TX_LIMIT:
                    nextArg("");

                    limit = (int) nextLongArg(TX_LIMIT);
                    break;

                case TX_ORDER:
                    nextArg("");

                    sortOrder = VisorTxSortOrder.fromString(nextArg(TX_ORDER));

                    break;

                case TX_SERVERS:
                    nextArg("");

                    proj = VisorTxProjection.SERVER;
                    break;

                case TX_CLIENTS:
                    nextArg("");

                    proj = VisorTxProjection.CLIENT;
                    break;

                case TX_NODES:
                    nextArg("");

                    consistentIds = getConsistentIds(nextArg(TX_NODES));
                    break;

                case TX_DURATION:
                    nextArg("");

                    duration = nextLongArg(TX_DURATION) * 1000L;
                    break;

                case TX_SIZE:
                    nextArg("");

                    size = (int) nextLongArg(TX_SIZE);
                    break;

                case TX_LABEL:
                    nextArg("");

                    lbRegex = nextArg(TX_LABEL);

                    try {
                        Pattern.compile(lbRegex);
                    }
                    catch (PatternSyntaxException e) {
                        throw new IllegalArgumentException("Illegal regex syntax");
                    }

                    break;

                case TX_XID:
                    nextArg("");

                    xid = nextArg(TX_XID);
                    break;

                case TX_KILL:
                    nextArg("");

                    op = VisorTxOperation.KILL;
                    break;

                default:
                    end = true;
            }
        }
        while (!end);

        if (proj != null && consistentIds != null)
            throw new IllegalArgumentException("Projection can't be used together with list of consistent ids.");

        return new VisorTxTaskArg(op, limit, duration, size, null, proj, consistentIds, xid, lbRegex, sortOrder);
    }

    /**
     * @return Numeric value.
     */
    private long nextLongArg(String lb) {
        String str = nextArg("Expecting " + lb);

        try {
            long val = Long.parseLong(str);

            if (val < 0)
                throw new IllegalArgumentException("Invalid value for " + lb + ": " + val);

            return val;
        }
        catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid value for " + lb + ": " + str);
        }
    }

    /**
     * Parse and execute command.
     *
     * @param rawArgs Arguments to parse and execute.
     * @return Exit code.
     */
    public int execute(List<String> rawArgs) {
        log("Control utility [ver. " + ACK_VER_STR + "]");
        log(COPYRIGHT);
        log("User: " + System.getProperty("user.name"));
        log(DELIM);

        try {
            if (F.isEmpty(rawArgs) || (rawArgs.size() == 1 && CMD_HELP.equalsIgnoreCase(rawArgs.get(0)))) {
                log("This utility can do the following commands:");

                usage("  Activate cluster:", ACTIVATE);
                usage("  Deactivate cluster:", DEACTIVATE, " [--force]");
                usage("  Print current cluster state:", STATE);
                usage("  Print cluster baseline topology:", BASELINE);
                usage("  Add nodes into baseline topology:", BASELINE, " add consistentId1[,consistentId2,....,consistentIdN] [--force]");
                usage("  Remove nodes from baseline topology:", BASELINE, " remove consistentId1[,consistentId2,....,consistentIdN] [--force]");
                usage("  Set baseline topology:", BASELINE, " set consistentId1[,consistentId2,....,consistentIdN] [--force]");
                usage("  Set baseline topology based on version:", BASELINE, " version topologyVersion [--force]");
                usage("  List or kill transactions:", TX, " [xid XID] [minDuration SECONDS] " +
                    "[minSize SIZE] [label PATTERN_REGEX] [servers|clients] " +
                    "[nodes consistentId1[,consistentId2,....,consistentIdN] [limit NUMBER] [order DURATION|SIZE] [kill] [--force]");

                log("By default commands affecting the cluster require interactive confirmation. ");
                log("  --force option can be used to execute commands without prompting for confirmation.");
                nl();

                log("Default values:");
                log("    HOST_OR_IP=" + DFLT_HOST);
                log("    PORT=" + DFLT_PORT);
                nl();

                log("Exit codes:");
                log("    " + EXIT_CODE_OK + " - successful execution.");
                log("    " + EXIT_CODE_INVALID_ARGUMENTS + " - invalid arguments.");
                log("    " + EXIT_CODE_CONNECTION_FAILED + " - connection failed.");
                log("    " + ERR_AUTHENTICATION_FAILED + " - authentication failed.");
                log("    " + EXIT_CODE_UNEXPECTED_ERROR + " - unexpected error.");

                return EXIT_CODE_OK;
            }

            Arguments args = parseAndValidate(rawArgs);

            if (!confirm(args)) {
                log("Operation cancelled.");

                return EXIT_CODE_OK;
            }

            GridClientConfiguration cfg = new GridClientConfiguration();

            cfg.setServers(Collections.singletonList(args.host() + ":" + args.port()));

            if (!F.isEmpty(args.user())) {
                cfg.setSecurityCredentialsProvider(
                    new SecurityCredentialsBasicProvider(new SecurityCredentials(args.user(), args.password())));
            }

            try (GridClient client = GridClientFactory.start(cfg)) {

                switch (args.command()) {
                    case ACTIVATE:
                        activate(client);
                        break;

                    case DEACTIVATE:
                        deactivate(client);
                        break;

                    case STATE:
                        state(client);
                        break;

                    case BASELINE:
                        baseline(client, args.baselineAction(), args.baselineArguments());
                        break;

                    case TX:
                        transactions(client, args.transactionArguments());
                        break;
                }
            }

            return 0;
        }
        catch (IllegalArgumentException e) {
            return error(EXIT_CODE_INVALID_ARGUMENTS, "Check arguments.", e);
        }
        catch (Throwable e) {
            if (isAuthError(e))
                return error(ERR_AUTHENTICATION_FAILED, "Authentication error.", e);

            if (isConnectionError(e))
                return error(EXIT_CODE_CONNECTION_FAILED, "Connection to cluster failed.", e);

            return error(EXIT_CODE_UNEXPECTED_ERROR, "", e);
        }
    }

    /**
     * @param args Arguments to parse and apply.
     */
    public static void main(String[] args) {
        CommandHandler hnd = new CommandHandler();

        System.exit(hnd.execute(Arrays.asList(args)));
    }

    /**
     * Used for tests.
     * @return Last operation result;
     */
    @SuppressWarnings("unchecked")
    public <T> T getLastOperationResult() {
        return (T)lastOperationResult;
    }
}

