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

package org.apache.ignite.internal.processors.cluster;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.util.Collection;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.ignite.IgniteCheckedException;
import org.apache.ignite.IgniteLogger;
import org.apache.ignite.IgniteSystemProperties;
import org.apache.ignite.internal.GridKernalGateway;
import org.apache.ignite.internal.IgniteKernal;
import org.apache.ignite.internal.IgniteProperties;
import org.apache.ignite.internal.util.typedef.F;
import org.apache.ignite.internal.util.typedef.internal.SB;
import org.apache.ignite.internal.util.typedef.internal.U;
import org.apache.ignite.internal.util.worker.GridWorker;
import org.apache.ignite.plugin.PluginProvider;
import org.jetbrains.annotations.Nullable;

import static java.net.URLEncoder.encode;

/**
 * This class is responsible for notification about new version availability.
 * <p>
 * Note also that this connectivity is not necessary to successfully start the system as it will
 * gracefully ignore any errors occurred during notification and verification process.</p>
 * <p>
 * TODO GG-14736 rework this for GridGain Community versions.</p>
 */
class GridUpdateNotifier {
    /** Default encoding. */
    private static final String CHARSET = "UTF-8";

    /** Access URL to be used to access latest version data. */
    private final String updStatusParams = IgniteProperties.get("ignite.update.status.params");

    /** Throttling for logging out. */
    private static final long THROTTLE_PERIOD = 24 * 60 * 60 * 1000; // 1 day.

    /** Sleep milliseconds time for worker thread. */
    private static final int WORKER_THREAD_SLEEP_TIME = 5000;

    /** Default url for request GridGain updates. */
    private static final String DEFAULT_GRIDGAIN_UPDATES_URL = "https://ignite.run/update_status_ignite-plain-text.php";

    /** Grid version. */
    private final String ver;

    /** Error during obtaining data. */
    private volatile Exception err;

    /** Latest version. */
    private volatile String latestVer;

    /** Download url for latest version. */
    private volatile String downloadUrl;

    /** Ignite instance name. */
    private final String igniteInstanceName;

    /** Whether or not to report only new version. */
    private volatile boolean reportOnlyNew;

    /** */
    private volatile int topSize;

    /** System properties */
    private final String vmProps;

    /** Plugins information for request */
    private final String pluginsVers;

    /** Kernal gateway */
    private final GridKernalGateway gw;

    /** */
    private long lastLog = -1;

    /** Command for worker thread. */
    private final AtomicReference<Runnable> cmd = new AtomicReference<>();

    /** Worker thread to process http request. */
    private final Thread workerThread;

    /** Http client for getting Ignite updates */
    private final HttpIgniteUpdatesChecker updatesChecker;

    /**
     * Creates new notifier with default values.
     *
     * @param igniteInstanceName igniteInstanceName
     * @param ver Compound Ignite version.
     * @param gw Kernal gateway.
     * @param pluginProviders Kernal gateway.
     * @param reportOnlyNew Whether or not to report only new version.
     * @param updatesChecker Service for getting Ignite updates
     * @throws IgniteCheckedException If failed.
     */
    GridUpdateNotifier(String igniteInstanceName, String ver, GridKernalGateway gw, Collection<PluginProvider> pluginProviders,
        boolean reportOnlyNew, HttpIgniteUpdatesChecker updatesChecker) throws IgniteCheckedException {
        try {
            this.ver = ver;
            this.igniteInstanceName = igniteInstanceName == null ? "null" : igniteInstanceName;
            this.gw = gw;
            this.updatesChecker = updatesChecker;

            SB pluginsBuilder = new SB();

            for (PluginProvider provider : pluginProviders)
                pluginsBuilder.a("&").a(provider.name() + "-plugin-version").a("=").
                    a(encode(provider.version(), CHARSET));

            pluginsVers = pluginsBuilder.toString();

            this.reportOnlyNew = reportOnlyNew;

            vmProps = getSystemProperties();

            workerThread = new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        while(!Thread.currentThread().isInterrupted()) {
                            Runnable cmd0 = cmd.getAndSet(null);

                            if (cmd0 != null)
                                cmd0.run();
                            else
                                //noinspection BusyWait
                                Thread.sleep(WORKER_THREAD_SLEEP_TIME);
                        }
                    }
                    catch (InterruptedException ignore) {
                        // No-op.
                    }
                }
            }, "upd-ver-checker");

            workerThread.setDaemon(true);

            workerThread.start();
        }
        catch (UnsupportedEncodingException e) {
            throw new IgniteCheckedException("Failed to encode.", e);
        }
    }

    /**
     * Creates new notifier with default GridGain updates URL
     */
    GridUpdateNotifier(String igniteInstanceName, String ver, GridKernalGateway gw, Collection<PluginProvider> pluginProviders,
        boolean reportOnlyNew) throws IgniteCheckedException {
        this(igniteInstanceName, ver, gw, pluginProviders, reportOnlyNew, new HttpIgniteUpdatesChecker(DEFAULT_GRIDGAIN_UPDATES_URL, CHARSET));
    }

    /**
     * Gets system properties.
     *
     * @return System properties.
     */
    private static String getSystemProperties() {
        try {
            StringWriter sw = new StringWriter();

            try {
                IgniteSystemProperties.snapshot().store(new PrintWriter(sw), "");
            }
            catch (IOException ignore) {
                return null;
            }

            return sw.toString();
        }
        catch (SecurityException ignore) {
            return null;
        }
    }

    /**
     * @param reportOnlyNew Whether or not to report only new version.
     */
    void reportOnlyNew(boolean reportOnlyNew) {
        this.reportOnlyNew = reportOnlyNew;
    }

    /**
     * @param topSize Size of topology for license verification purpose.
     */
    void topologySize(int topSize) {
        this.topSize = topSize;
    }

    /**
     * @return Latest version.
     */
    String latestVersion() {
        return latestVer;
    }

    /**
     * @return Error.
     */
    Exception error() {
        return err;
    }

    /**
     * Starts asynchronous process for retrieving latest version data.
     *
     * @param log Logger.
     */
    void checkForNewVersion(IgniteLogger log) {
        assert log != null;

        log = log.getLogger(getClass());

        try {
            cmd.set(new UpdateChecker(log));
        }
        catch (RejectedExecutionException e) {
            U.error(log, "Failed to schedule a thread due to execution rejection (safely ignoring): " +
                e.getMessage());
        }
    }

    /**
     * Logs out latest version notification if such was received and available.
     *
     * @param log Logger.
     */
    void reportStatus(IgniteLogger log) {
        assert log != null;

        log = log.getLogger(getClass());

        String latestVer = this.latestVer;
        String downloadUrl = this.downloadUrl;

        downloadUrl = downloadUrl != null ? downloadUrl : IgniteKernal.SITE;

        if (latestVer != null)
            if (latestVer.equals(ver)) {
                if (!reportOnlyNew)
                    throttle(log, false, "Your version is up to date.");
            }
            else
                throttle(log, true, "New version is available at " + downloadUrl + ": " + latestVer);
        else
        if (!reportOnlyNew)
            throttle(log, false, "Update status is not available.");
    }

    /**
     *
     * @param log Logger to use.
     * @param warn Whether or not this is a warning.
     * @param msg Message to log.
     */
    private void throttle(IgniteLogger log, boolean warn, String msg) {
        assert(log != null);
        assert(msg != null);

        long now = U.currentTimeMillis();

        if (now - lastLog > THROTTLE_PERIOD) {
            if (!warn)
                U.log(log, msg);
            else {
                U.quiet(true, msg);

                if (log.isInfoEnabled())
                    log.warning(msg);
            }

            lastLog = now;
        }
    }

    /**
     * Stops update notifier.
     */
    public void stop() {
        workerThread.interrupt();
    }

    /**
     * Asynchronous checker of the latest version available.
     */
    private class UpdateChecker extends GridWorker {
        /** Logger. */
        private final IgniteLogger log;

        /**
         * Creates checked with given logger.
         *
         * @param log Logger.
         */
        UpdateChecker(IgniteLogger log) {
            super(igniteInstanceName, "grid-version-checker", log);

            this.log = log.getLogger(getClass());
        }

        /** {@inheritDoc} */
        @Override protected void body() throws InterruptedException {
            try {
                String stackTrace = gw != null ? gw.userStackTrace() : null;

                String postParams =
                    "igniteInstanceName=" + encode(igniteInstanceName, CHARSET) +
                        (!F.isEmpty(updStatusParams) ? "&" + updStatusParams : "") +
                        (topSize > 0 ? "&topSize=" + topSize : "") +
                        (!F.isEmpty(stackTrace) ? "&stackTrace=" + encode(stackTrace, CHARSET) : "") +
                        (!F.isEmpty(vmProps) ? "&vmProps=" + encode(vmProps, CHARSET) : "") +
                        pluginsVers;

                if (!isCancelled()) {
                    try {
                        String updatesRes = updatesChecker.getUpdates(postParams);

                        String[] lines = updatesRes.split("\n");

                        for (String line : lines) {
                            if (line.contains("version"))
                                latestVer = obtainVersionFrom(line);
                            else if (line.contains("downloadUrl"))
                                downloadUrl = obtainDownloadUrlFrom(line);
                        }

                        err = null;
                    }
                    catch (IOException e) {
                        err = e;

                        if (log.isDebugEnabled())
                            log.debug("Failed to connect to Ignite update server. " + e.getMessage());
                    }
                }
            }
            catch (Exception e) {
                err = e;

                if (log.isDebugEnabled())
                    log.debug("Unexpected exception in update checker. " + e.getMessage());
            }
        }

        /**
         * Gets the version from the current {@code node}, if one exists.
         *
         * @param  line Line which contains value for extract.
         * @param  metaName Name for extract.
         * @return Version or {@code null} if one's not found.
         */
        @Nullable private String obtainMeta(String metaName, String line) {
            assert line.contains(metaName);

            return line.substring(line.indexOf(metaName) + metaName.length()).trim();

        }

        /**
         * Gets the version from the current {@code node}, if one exists.
         *
         * @param  line Line which contains value for extract.
         * @return Version or {@code null} if one's not found.
         */
        @Nullable private String obtainVersionFrom(String line) {
            return obtainMeta("version=", line);
        }

        /**
         * Gets the download url from the current {@code node}, if one exists.
         *
         * @param line Which contains value for extract.
         * @return download url or {@code null} if one's not found.
         */
        @Nullable private String obtainDownloadUrlFrom(String line) {
            return obtainMeta("downloadUrl=", line);
        }
    }
}
