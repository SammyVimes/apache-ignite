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

package org.apache.ignite.spi.discovery.tcp;

import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.testframework.GridTestUtils;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * We emulate that client receive message about joining to topology earlier than some server nodes in topology.
 * And make this client connect to such servers.
 * To emulate this we connect client to second node in topology and pause sending message about joining finishing to
 * third node.
 */
public class IgniteClientConnectSslTest extends IgniteClientConnectTest {
    /** {@inheritDoc} */
    @Override protected IgniteConfiguration getConfiguration(String igniteInstanceName) throws Exception {
        IgniteConfiguration cfg = super.getConfiguration(igniteInstanceName);

        cfg.setSslContextFactory(GridTestUtils.sslFactory());

        return cfg;
    }
}
