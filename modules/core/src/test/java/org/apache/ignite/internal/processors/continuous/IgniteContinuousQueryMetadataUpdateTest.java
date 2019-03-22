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

package org.apache.ignite.internal.processors.continuous;

import org.apache.ignite.Ignite;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.events.Event;
import org.apache.ignite.internal.IgniteInternalFuture;
import org.apache.ignite.internal.IgniteInterruptedCheckedException;
import org.apache.ignite.internal.managers.discovery.DiscoveryCustomMessage;
import org.apache.ignite.internal.processors.cache.binary.MetadataUpdateAcceptedMessage;
import org.apache.ignite.internal.util.IgniteUtils;
import org.apache.ignite.internal.util.typedef.internal.U;
import org.apache.ignite.lang.IgniteCallable;
import org.apache.ignite.lang.IgnitePredicate;
import org.apache.ignite.spi.discovery.DiscoverySpiCustomMessage;
import org.apache.ignite.spi.discovery.DiscoverySpiListener;
import org.apache.ignite.spi.discovery.tcp.TestTcpDiscoverySpi;
import org.apache.ignite.spi.discovery.tcp.ipfinder.TcpDiscoveryIpFinder;
import org.apache.ignite.spi.discovery.tcp.ipfinder.vm.TcpDiscoveryVmIpFinder;
import org.apache.ignite.testframework.GridTestUtils;
import org.apache.ignite.testframework.GridTestUtils.DiscoveryHook;
import org.apache.ignite.testframework.junits.common.GridCommonAbstractTest;
import org.jetbrains.annotations.Nullable;
import org.junit.Assert;
import org.junit.Test;

/**
 * Test for the start continuous query process. Processing the {@link StartRoutineDiscoveryMessage} message should not
 * awaiting metadata update. Otherwise, it may lead to possible deadlock in discovery thread in the case of mutable
 * discovery messages and peer class loading enabled. See IGNITE-10238, IGNITE-6668 for details.
 */
public class IgniteContinuousQueryMetadataUpdateTest extends GridCommonAbstractTest {
    /** */
    private static final TcpDiscoveryIpFinder IP_FINDER = new TcpDiscoveryVmIpFinder(true);

    /** {@inheritDoc} */
    @Override protected long getTestTimeout() {
        return 30 * 1000;
    }

    /** {@inheritDoc} */
    @Override protected IgniteConfiguration getConfiguration(String igniteInstanceName) throws Exception {
        IgniteConfiguration cfg = super.getConfiguration(igniteInstanceName);

        TestDiscoverySpi discoSpi = new TestDiscoverySpi();

        discoSpi.setIpFinder(IP_FINDER);

        cfg.setDiscoverySpi(discoSpi);

        cfg.setPeerClassLoadingEnabled(true);

        return cfg;
    }

    /**
     * Checks that starting query not blocking by updating metadata.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testStartQueryOnMetadataUpdate() throws Exception {
        Ignite srv1 = startGrid(0);
        Ignite srv2 = startGrid(1);

        IgniteInternalFuture fut1 = GridTestUtils.runAsync(() -> {
            for (int i = 0; i < 5; i++)
                srv1.events().remoteListen(null, new IgnitePredicate<Event>() {
                    @Override public boolean apply(Event evt) {
                        return true;
                    }
                });
        });

        IgniteInternalFuture fut2 = GridTestUtils.runAsync(() -> {
            for (int i = 0; i < 5; i++)
                srv2.compute().call(new IgniteCallable<Object>() {
                    @Override public Object call() {
                        return null;
                    }
                });
        });

        fut1.get();
        fut2.get();
    }

    /**
     * Test discovery SPI.
     */
    private static class TestDiscoverySpi extends TestTcpDiscoverySpi {
        /** {@inheritDoc} */
        @Override public void setListener(@Nullable DiscoverySpiListener lsnr) {
            super.setListener(GridTestUtils.DiscoverySpiListenerWrapper.wrap(lsnr, new DiscoveryHook() {
                @Override public void handleDiscoveryMessage(DiscoverySpiCustomMessage msg) {
                    DiscoveryCustomMessage customMsg = msg == null ? null
                        : (DiscoveryCustomMessage)IgniteUtils.field(msg, "delegate");

                    if (customMsg instanceof MetadataUpdateAcceptedMessage) {
                        try {
                            U.sleep(50);
                        }
                        catch (IgniteInterruptedCheckedException e) {
                            Assert.fail("Unexpected error:" + e);
                        }
                    }
                }
            }));
        }
    }
}
