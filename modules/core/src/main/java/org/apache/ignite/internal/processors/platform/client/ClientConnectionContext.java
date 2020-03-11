/*
 * Copyright 2019 GridGain Systems, Inc. and Contributors.
 *
 * Licensed under the GridGain Community Edition License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.gridgain.com/products/software/community-edition/gridgain-community-edition-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.platform.client;

import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.ignite.IgniteCheckedException;
import org.apache.ignite.internal.GridKernalContext;
import org.apache.ignite.internal.binary.BinaryReaderExImpl;
import org.apache.ignite.internal.processors.affinity.AffinityTopologyVersion;
import org.apache.ignite.internal.processors.authentication.AuthorizationContext;
import org.apache.ignite.internal.processors.odbc.ClientListenerAbstractConnectionContext;
import org.apache.ignite.internal.processors.odbc.ClientListenerMessageParser;
import org.apache.ignite.internal.processors.odbc.ClientListenerProtocolVersion;
import org.apache.ignite.internal.processors.odbc.ClientListenerRequestHandler;
import org.apache.ignite.internal.util.nio.GridNioSession;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thin Client connection context.
 */
public class ClientConnectionContext extends ClientListenerAbstractConnectionContext {
    /** Version 1.0.0. */
    public static final ClientListenerProtocolVersion VER_1_0_0 = ClientListenerProtocolVersion.create(1, 0, 0);

    /** Version 1.1.0. */
    public static final ClientListenerProtocolVersion VER_1_1_0 = ClientListenerProtocolVersion.create(1, 1, 0);

    /** Version 1.2.0. */
    public static final ClientListenerProtocolVersion VER_1_2_0 = ClientListenerProtocolVersion.create(1, 2, 0);

    /** Version 1.3.0. */
    public static final ClientListenerProtocolVersion VER_1_3_0 = ClientListenerProtocolVersion.create(1, 3, 0);

    /** Version 1.4.0. Added: Partition Awareness, IEP-23. */
    public static final ClientListenerProtocolVersion VER_1_4_0 = ClientListenerProtocolVersion.create(1, 4, 0);

    /** Version 1.5.0. Added: Expiration Policy configuration. */
    public static final ClientListenerProtocolVersion VER_1_5_0 = ClientListenerProtocolVersion.create(1, 5, 0);

    /**
     * Version 2.0.0. Added: protocol features.
     * ATTENTION! Do not add any new protocol versions unless totally necessary. Use {@link ClientFeature} instead.
     */
    public static final ClientListenerProtocolVersion VER_2_0_0 = ClientListenerProtocolVersion.create(2, 0, 0);

    /** Default version. */
    public static final ClientListenerProtocolVersion DEFAULT_VER = VER_2_0_0;

    /** Default protocol context. */
    public static final ClientProtocolContext DEFAULT_PROTOCOL_CONTEXT =
        new ClientProtocolContext(DEFAULT_VER, ClientFeature.allFeaturesAsEnumSet());

    /** Supported versions. */
    private static final Collection<ClientListenerProtocolVersion> SUPPORTED_VERS = Arrays.asList(
        VER_2_0_0,
        VER_1_5_0,
        VER_1_4_0,
        VER_1_3_0,
        VER_1_2_0,
        VER_1_1_0,
        VER_1_0_0
    );

    /** Message parser. */
    private ClientMessageParser parser;

    /** Request handler. */
    private ClientRequestHandler handler;

    /** Handle registry. */
    private final ClientResourceRegistry resReg = new ClientResourceRegistry();

    /** Max cursors. */
    private final int maxCursors;

    /** Current protocol context. */
    private ClientProtocolContext currentProtocolContext;

    /** Last reported affinity topology version. */
    private AtomicReference<AffinityTopologyVersion> lastAffinityTopologyVersion = new AtomicReference<>();

    /** Cursor counter. */
    private final AtomicLong curCnt = new AtomicLong();

    /**
     * Ctor.
     *
     * @param ctx Kernal context.
     * @param connId Connection ID.
     * @param maxCursors Max active cursors.
     */
    public ClientConnectionContext(GridKernalContext ctx, long connId, int maxCursors) {
        super(ctx, connId);

        this.maxCursors = maxCursors;
    }

    /**
     * Gets the handle registry.
     *
     * @return Handle registry.
     */
    public ClientResourceRegistry resources() {
        return resReg;
    }

    /** {@inheritDoc} */
    @Override public boolean isVersionSupported(ClientListenerProtocolVersion ver) {
        return SUPPORTED_VERS.contains(ver);
    }

    /** {@inheritDoc} */
    @Override public ClientListenerProtocolVersion defaultVersion() {
        return DEFAULT_VER;
    }

    /**
     * @return Currently used protocol context.
     */
    public ClientProtocolContext currentProtocolContext() {
        return currentProtocolContext;
    }

    /** {@inheritDoc} */
    @Override public void initializeFromHandshake(GridNioSession ses,
        ClientListenerProtocolVersion ver, BinaryReaderExImpl reader)
        throws IgniteCheckedException {

        EnumSet<ClientFeature> features = null;

        if (ver.compareTo(VER_2_0_0) >= 0) {
            byte [] cliFeatures = reader.readByteArray();

            features = ClientFeature.enumSet(cliFeatures);

            features.retainAll(ClientFeature.allFeaturesAsEnumSet());
        }

        currentProtocolContext = new ClientProtocolContext(ver, features);

        String user = null;
        String pwd = null;

        if (currentProtocolContext.isAuthorizationSupported()) {
            boolean hasMore;
            try {
                hasMore = reader.available() > 0;
            }
            catch (IOException e) {
                throw new IgniteCheckedException("Handshake error: " + e.getMessage(), e);
            }

            if (hasMore) {
                user = reader.readString();
                pwd = reader.readString();
            }
        }

        AuthorizationContext authCtx = authenticate(ses.certificates(), user, pwd);

        handler = new ClientRequestHandler(this, authCtx, currentProtocolContext);
        parser = new ClientMessageParser(this, currentProtocolContext);
    }

    /** {@inheritDoc} */
    @Override public ClientListenerRequestHandler handler() {
        return handler;
    }

    /** {@inheritDoc} */
    @Override public ClientListenerMessageParser parser() {
        return parser;
    }

    /** {@inheritDoc} */
    @Override public void onDisconnected() {
        resReg.clean();

        super.onDisconnected();
    }

    /**
     * Increments the cursor count.
     */
    public void incrementCursors() {
        long curCnt0 = curCnt.get();

        if (curCnt0 >= maxCursors) {
            throw new IgniteClientException(ClientStatus.TOO_MANY_CURSORS,
                "Too many open cursors (either close other open cursors or increase the " +
                "limit through ClientConnectorConfiguration.maxOpenCursorsPerConnection) [maximum=" + maxCursors +
                ", current=" + curCnt0 + ']');
        }

        curCnt.incrementAndGet();
    }

    /**
     * Increments the cursor count.
     */
    public void decrementCursors() {
        curCnt.decrementAndGet();
    }

    /**
     * Atomically check whether affinity topology version has changed since the last call and sets new version as a last.
     * @return New version, if it has changed since the last call.
     */
    public ClientAffinityTopologyVersion checkAffinityTopologyVersion() {
        while (true) {
            AffinityTopologyVersion oldVer = lastAffinityTopologyVersion.get();
            AffinityTopologyVersion newVer = ctx.cache().context().exchange().readyAffinityVersion();

            boolean changed = oldVer == null || oldVer.compareTo(newVer) < 0;

            if (changed) {
                boolean success = lastAffinityTopologyVersion.compareAndSet(oldVer, newVer);

                if (!success)
                    continue;
            }

            return new ClientAffinityTopologyVersion(newVer, changed);
        }
    }
}
