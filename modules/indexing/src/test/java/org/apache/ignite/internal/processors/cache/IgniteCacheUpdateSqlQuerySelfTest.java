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

package org.apache.ignite.internal.processors.cache;

import java.io.Serializable;
import java.util.Arrays;
import java.util.List;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.cache.CacheAtomicityMode;
import org.apache.ignite.cache.CacheMode;
import org.apache.ignite.cache.query.QueryCursor;
import org.apache.ignite.cache.query.SqlFieldsQuery;
import org.apache.ignite.cache.query.annotations.QuerySqlField;
import org.apache.ignite.configuration.CacheConfiguration;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.spi.discovery.tcp.TcpDiscoverySpi;
import org.apache.ignite.spi.discovery.tcp.ipfinder.TcpDiscoveryIpFinder;
import org.apache.ignite.spi.discovery.tcp.ipfinder.vm.TcpDiscoveryVmIpFinder;
import org.apache.ignite.testframework.junits.common.GridCommonAbstractTest;

/**
 *
 */
@SuppressWarnings("unchecked")
public class IgniteCacheUpdateSqlQuerySelfTest extends GridCommonAbstractTest {
    /** */
    private static final TcpDiscoveryIpFinder ipFinder = new TcpDiscoveryVmIpFinder(true);

    /** {@inheritDoc} */
    @Override protected IgniteConfiguration getConfiguration(String gridName) throws Exception {
        IgniteConfiguration cfg = super.getConfiguration(gridName);

        cfg.setPeerClassLoadingEnabled(false);

        TcpDiscoverySpi disco = new TcpDiscoverySpi();

        disco.setIpFinder(ipFinder);

        cfg.setDiscoverySpi(disco);

        return cfg;
    }

    /** {@inheritDoc} */
    @Override protected void beforeTestsStarted() throws Exception {
        startGridsMultiThreaded(3, true);

        ignite(0).createCache(cacheConfig("S2P", true, false, String.class, Person.class));
    }

    /** {@inheritDoc} */
    @Override protected void beforeTest() throws Exception {
        super.beforeTest();
        ignite(0).cache("S2P").put("FirstKey", new Person(1, "John", "White"));
        ignite(0).cache("S2P").put("SecondKey", new Person(2, "Joe", "Black"));
        ignite(0).cache("S2P").put("k3", new Person(3, "Sylvia", "Green"));
        ignite(0).cache("S2P").put("f0u4thk3y", new Person(4, "Jane", "Silver"));
    }

    /** {@inheritDoc} */
    @Override protected void afterTestsStopped() throws Exception {
        stopAllGrids();
    }

    /**
     * @param name Cache name.
     * @param partitioned Partition or replicated cache.
     * @param escapeSql whether identifiers should be quoted - see {@link CacheConfiguration#setSqlEscapeAll}
     * @param idxTypes Indexed types.
     * @return Cache configuration.
     */
    private static CacheConfiguration cacheConfig(String name, boolean partitioned, boolean escapeSql, Class<?>... idxTypes) {
        return new CacheConfiguration()
            .setName(name)
            .setCacheMode(partitioned ? CacheMode.PARTITIONED : CacheMode.REPLICATED)
            .setAtomicityMode(CacheAtomicityMode.ATOMIC)
            .setBackups(1)
            .setSqlEscapeAll(escapeSql)
            .setIndexedTypes(idxTypes);
    }

    /**
     *
     */
    public void testUpdateSimple() {
        IgniteCache<String, Person> p = ignite(0).cache("S2P");

        QueryCursor<List<?>> c = p.query(new SqlFieldsQuery("update Person p set p.id = p.id * 2, p.name = " +
            "substring(p.name, 0, 2) where length(p._key) = ? or p.secondName like ?").setArgs(2, "%ite"));

        c.iterator();

        c = p.query(new SqlFieldsQuery("select * from Person order by _key, id"));

        List<List<?>> leftovers = c.getAll();

        assertEquals(4, leftovers.size());

        assertEqualsCollections(Arrays.asList("FirstKey", new Person(2, "Jo", "White"), 2, "Jo", "White"),
            leftovers.get(0));

        assertEqualsCollections(Arrays.asList("SecondKey", new Person(2, "Joe", "Black"), 2, "Joe", "Black"),
            leftovers.get(1));

        assertEqualsCollections(Arrays.asList("f0u4thk3y", new Person(4, "Jane", "Silver"), 4, "Jane", "Silver"),
            leftovers.get(2));

        assertEqualsCollections(Arrays.asList("k3", new Person(6, "Sy", "Green"), 6, "Sy", "Green"),
            leftovers.get(3));
    }

    /**
     *
     */
    public void testUpdateSingle() {
        IgniteCache<String, Person> p = ignite(0).cache("S2P");

        QueryCursor<List<?>> c = p.query(new SqlFieldsQuery("update Person p set _val = ? where _key = ?")
            .setArgs(new Person(2, "Jo", "White"), "FirstKey"));

        c.iterator();

        c = p.query(new SqlFieldsQuery("select * from Person order by id, _key"));

        List<List<?>> leftovers = c.getAll();

        assertEquals(4, leftovers.size());

        assertEqualsCollections(Arrays.asList("FirstKey", new Person(2, "Jo", "White"), 2, "Jo", "White"),
            leftovers.get(0));

        assertEqualsCollections(Arrays.asList("SecondKey", new Person(2, "Joe", "Black"), 2, "Joe", "Black"),
            leftovers.get(1));

        assertEqualsCollections(Arrays.asList("k3", new Person(3, "Sylvia", "Green"), 3, "Sylvia", "Green"),
            leftovers.get(2));

        assertEqualsCollections(Arrays.asList("f0u4thk3y", new Person(4, "Jane", "Silver"), 4, "Jane", "Silver"),
            leftovers.get(3));
    }

    /**
     *
     */
    public void testUpdateValueAndFields() {
        IgniteCache<String, Person> p = ignite(0).cache("S2P");

        QueryCursor<List<?>> c = p.query(new SqlFieldsQuery("update Person p set id = ?, _val = ? where _key = ?")
            .setArgs(44, new Person(2, "Jo", "Woo"), "FirstKey"));

        c.iterator();

        c = p.query(new SqlFieldsQuery("select * from Person order by _key, id"));

        List<List<?>> leftovers = c.getAll();

        assertEquals(4, leftovers.size());

        assertEqualsCollections(Arrays.asList("FirstKey", new Person(44, "Jo", "Woo"), 44, "Jo", "Woo"),
            leftovers.get(0));

        assertEqualsCollections(Arrays.asList("SecondKey", new Person(2, "Joe", "Black"), 2, "Joe", "Black"),
            leftovers.get(1));

        assertEqualsCollections(Arrays.asList("f0u4thk3y", new Person(4, "Jane", "Silver"), 4, "Jane", "Silver"),
            leftovers.get(2));

        assertEqualsCollections(Arrays.asList("k3", new Person(3, "Sylvia", "Green"), 3, "Sylvia", "Green"),
            leftovers.get(3));


    }

    /**
     *
     */
    private static class Person implements Serializable {
        /** */
        public Person(int id, String name, String secondName) {
            this.id = id;
            this.name = name;
            this.secondName = secondName;
        }

        /** */
        @QuerySqlField
        protected int id;

        /** */
        @QuerySqlField
        protected final String name;

        /** */
        @QuerySqlField
        final String secondName;

        /** {@inheritDoc} */
        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Person person = (Person) o;

            return id == person.id && name.equals(person.name) && secondName.equals(person.secondName);

        }

        /** {@inheritDoc} */
        @Override public int hashCode() {
            int res = id;
            res = 31 * res + name.hashCode();
            res = 31 * res + secondName.hashCode();
            return res;
        }
    }
}
