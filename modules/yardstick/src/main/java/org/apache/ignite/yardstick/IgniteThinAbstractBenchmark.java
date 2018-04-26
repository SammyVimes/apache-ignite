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

package org.apache.ignite.yardstick;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.apache.ignite.client.ClientCache;
import org.apache.ignite.client.IgniteClient;
import org.apache.ignite.yardstick.thin.cache.IgniteThinBenchmarkUtils;
import org.yardstickframework.BenchmarkConfiguration;
import org.yardstickframework.BenchmarkDriverAdapter;
import org.yardstickframework.BenchmarkUtils;

import static org.yardstickframework.BenchmarkUtils.jcommander;
import static org.yardstickframework.BenchmarkUtils.println;

/**
 * Abstract class for Ignite benchmarks.
 */
public abstract class IgniteThinAbstractBenchmark extends BenchmarkDriverAdapter {
    /** Arguments. */
    protected final IgniteBenchmarkArguments args = new IgniteBenchmarkArguments();

    /** Client. */
    private IgniteClient client;

    /** {@inheritDoc} */
    @Override public void setUp(BenchmarkConfiguration cfg) throws Exception {
        super.setUp(cfg);

        jcommander(cfg.commandLineArguments(), args, "<ignite-driver>");

        String locIp = IgniteThinBenchmarkUtils.getLocalIp(cfg);

        client = new IgniteThinClient().start(cfg);

        System.out.println("localIp = " + locIp);

        ClientCache<String, String> utilCache = client.getOrCreateCache("start-util-cache");

        System.out.println("utilCache = " + utilCache);

        System.out.println("before = " + utilCache.get(locIp));

        utilCache.put(locIp, "started");

        List<String> hostList = IgniteThinBenchmarkUtils.drvHostList(cfg);

        int cnt = 0;

        while(!checkIfAllClientsStarted(hostList) && cnt++ < 600)
            Thread.sleep(500L);
    }

    /**
     *
     * @param hostList
     * @return
     */
    private boolean checkIfAllClientsStarted(List<String> hostList){
        ClientCache<String, String> utilCache = client.getOrCreateCache("start-util-cache");

        for(String host : hostList){
            System.out.println("host = " + host);

            if(host.equals("localhost"))
                host = "127.0.0.1";

            String res = utilCache.get(host);

            System.out.println(res);

            if (res == null || !res.equals("started"))
                return false;
        }

        return true;
    }

    /** {@inheritDoc} */
    @Override public void tearDown() throws Exception {
        if (client != null)
            client.close();
    }

    /** {@inheritDoc} */
    @Override public String description() {
        String desc = BenchmarkUtils.description(cfg, this);

        return desc.isEmpty() ?
            getClass().getSimpleName() + args.description() + cfg.defaultDescription() : desc;
    }

    /**
     * @return Client.
     */
    protected IgniteClient client() {
        return client;
    }





    /**
     * @param max Key range.
     * @return Next key.
     */
    public static int nextRandom(int max) {
        return ThreadLocalRandom.current().nextInt(max);
    }

    /**
     * @param min Minimum key in range.
     * @param max Maximum key in range.
     * @return Next key.
     */
    protected int nextRandom(int min, int max) {
        return ThreadLocalRandom.current().nextInt(max - min) + min;
    }
}
