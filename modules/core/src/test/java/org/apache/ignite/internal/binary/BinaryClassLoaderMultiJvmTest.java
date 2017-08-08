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

package org.apache.ignite.internal.binary;

import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.IgniteException;
import org.apache.ignite.Ignition;
import org.apache.ignite.binary.BinaryObject;
import org.apache.ignite.binary.BinaryObjectException;
import org.apache.ignite.cache.CacheAtomicityMode;
import org.apache.ignite.cache.CachePeekMode;
import org.apache.ignite.cache.CacheWriteSynchronizationMode;
import org.apache.ignite.configuration.CacheConfiguration;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.internal.IgniteInterruptedCheckedException;
import org.apache.ignite.internal.util.IgniteUtils;
import org.apache.ignite.internal.util.typedef.X;
import org.apache.ignite.lang.IgniteRunnable;
import org.apache.ignite.testframework.GridTestExternalClassLoader;
import org.apache.ignite.testframework.config.GridTestProperties;
import org.apache.ignite.testframework.junits.common.GridCommonAbstractTest;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URL;

/**
 */
public class BinaryClassLoaderMultiJvmTest extends GridCommonAbstractTest {
    /** */
    private static final String PERSON_CLASS_NAME = "org.apache.ignite.tests.p2p.cache.Person";
    private static final String ENUM_CLASS_NAME = "org.apache.ignite.tests.p2p.cache.Color";
    private static final String ORGANIZATION_CLASS_NAME = "org.apache.ignite.tests.p2p.cache.Organization";
    private static final String ADDRESS_CLASS_NAME = "org.apache.ignite.tests.p2p.cache.Address";

    private static final String[] enumVals = {"GREY", "RED", "GREEN", "PURPLE", "LIGHTBLUE"};
    public static final int CLIENT_ID = 1;

    /** {@inheritDoc} */
    @Override protected boolean isMultiJvm() {
        return true;
    }

    /** {@inheritDoc} */
    @Override protected IgniteConfiguration getConfiguration(String igniteInstanceName) throws Exception {
        return super.getConfiguration(igniteInstanceName)
            .setNetworkTimeout(10000)
            .setClientMode(getTestIgniteInstanceName(CLIENT_ID).equals(igniteInstanceName))
            .setCacheConfiguration(
                new CacheConfiguration("SomeCache")
                    .setAtomicityMode(CacheAtomicityMode.ATOMIC)
                    .setWriteSynchronizationMode(CacheWriteSynchronizationMode.FULL_SYNC),
                new CacheConfiguration("SomeCacheEnum")
                    .setAtomicityMode(CacheAtomicityMode.ATOMIC)
                    .setWriteSynchronizationMode(CacheWriteSynchronizationMode.FULL_SYNC),
                new CacheConfiguration("OrganizationCache")
                    .setAtomicityMode(CacheAtomicityMode.ATOMIC)
                    .setWriteSynchronizationMode(CacheWriteSynchronizationMode.FULL_SYNC));
    }

    /**
     *
     */
    public void testLoadClassFromBinary() throws Exception {
        ClassLoader testClassLoader = new GridTestExternalClassLoader(new URL[] {
            new URL(GridTestProperties.getProperty("p2p.uri.cls"))});

        WeakReference<ClassLoader> clsLdrRef = new WeakReference<ClassLoader>(testClassLoader);

        try {
            Ignite ign = startGrids(2);

            ign.compute(ign.cluster().forRemotes()).broadcast(new IgniteRunnable() {
                @Override public void run() {

                    Ignite client = Ignition.ignite(getTestIgniteInstanceName(CLIENT_ID));

                    ClassLoader clientCestClassLoader = null;

                    try {
                        clientCestClassLoader = new GridTestExternalClassLoader(new URL[] {
                            new URL(GridTestProperties.getProperty("p2p.uri.cls"))});

                        info("Client name: " + client.name());

                        loadItems(clientCestClassLoader, client);
                        loadOrganization(clientCestClassLoader, client);
                        loadEnumItems(clientCestClassLoader, client);
                    }
                    catch (Exception e) {
                        e.printStackTrace();
                    }

                }
            });

            checkItems(testClassLoader, "SomeCache", PERSON_CLASS_NAME, ign);

            checkItems(testClassLoader, "SomeCacheEnum", ENUM_CLASS_NAME, ign);

            checkItems(testClassLoader, "OrganizationCache", ORGANIZATION_CLASS_NAME, ign);

            testClassLoader = null;

            checkClassCacheEmpty(clsLdrRef);
        }
        finally {
            stopAllGrids();
        }
    }

    /**
     *
     */
    public void testClientLoadClassFromBinary() throws Exception {
        ClassLoader testClassLoader = new GridTestExternalClassLoader(new URL[] {
            new URL(GridTestProperties.getProperty("p2p.uri.cls"))});

        WeakReference<ClassLoader> clsLdrRef = new WeakReference<ClassLoader>(testClassLoader);

        try {
            Ignite ign = startGrids(2);

            ign.compute(ign.cluster().forClients()).broadcast(new IgniteRunnable() {
                @Override public void run() {

                    Ignite client = Ignition.ignite(getTestIgniteInstanceName(CLIENT_ID));

                    ClassLoader clientCestClassLoader = null;

                    try {
                        clientCestClassLoader = new GridTestExternalClassLoader(new URL[] {
                            new URL(GridTestProperties.getProperty("p2p.uri.cls"))});

                        info("Client name: " + client.name());

                        loadItems(clientCestClassLoader, client);
                        loadOrganization(clientCestClassLoader, client);
                        loadEnumItems(clientCestClassLoader, client);
                    }
                    catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            });

            checkItems(testClassLoader, "SomeCache", PERSON_CLASS_NAME, ign);

            checkItems(testClassLoader, "SomeCacheEnum", ENUM_CLASS_NAME, ign);

            checkItems(testClassLoader, "OrganizationCache", ORGANIZATION_CLASS_NAME, ign);

            testClassLoader = null;

            checkClassCacheEmpty(clsLdrRef);
        }
        finally {
            stopAllGrids();
        }
    }

    /**
     * @param clsLdrRef Weak reference to testclassLoader.
     */
    private void checkClassCacheEmpty(WeakReference<ClassLoader> clsLdrRef) throws IgniteInterruptedCheckedException {
        System.gc();
        System.runFinalization();

        IgniteUtils.sleep(1_000);

        assertNull(clsLdrRef.get());
    }

    /**
     * @param testClassLoader Test class loader.
     * @param ignite Ignite.
     */
    private void checkItems(ClassLoader testClassLoader, String cacheName, String valClassName, Ignite ignite) {
        IgniteCache cache = ignite.cache(cacheName);

        IgniteCache<Integer, BinaryObject> binaryCache = cache.withKeepBinary();

        for (int i = 0; i < 100; i++) {

            BinaryObject binaryVal = binaryCache.get(i);

            if (i % 50 == 0)
                try {
                    info("Val: " + binaryVal.toString());
                }
                catch (IgniteException e) {
                    info("Can not execute toString() on class " + binaryVal.type().typeName());
                }

            assertEquals(binaryVal.type().typeName(), valClassName);

            boolean catchEx = false;

            try {
                binaryVal.deserialize();
            }
            catch (BinaryObjectException e) {
                ClassNotFoundException cause = X.cause(e, ClassNotFoundException.class);

                if (cause != null && cause.getMessage().contains(valClassName))
                    catchEx = true;
                else
                    throw e;
            }

            assertTrue(catchEx);

            Object personVal = binaryVal.deserialize(testClassLoader, false);

            assertTrue(personVal != null && personVal.getClass().getName().equals(valClassName));
        }
    }

    /**
     * @param testClassLoader Test class loader.
     * @param ignite Ignite.
     */
    private void loadItems(ClassLoader testClassLoader, Ignite ignite) throws Exception {
        Constructor personConstructor = testClassLoader.loadClass(PERSON_CLASS_NAME).getConstructor(String.class);

        IgniteCache cache = ignite.cache("SomeCache");

        for (int i = 0; i < 100; i++)
            cache.put(i, personConstructor.newInstance("Persone name " + i));

        assertEquals(cache.size(CachePeekMode.PRIMARY), 100);
    }

    /**
     * @param testClassLoader Test class loader.
     * @param ignite Ignite.
     */
    private void loadOrganization(ClassLoader testClassLoader, Ignite ignite) throws Exception {
        Class personClass = testClassLoader.loadClass(PERSON_CLASS_NAME);
        Class addressClass = testClassLoader.loadClass(ADDRESS_CLASS_NAME);

        Constructor personConstructor = testClassLoader.loadClass(PERSON_CLASS_NAME).getConstructor(String.class);
        Constructor addressConstructor = testClassLoader.loadClass(ADDRESS_CLASS_NAME).getConstructor(String.class, Integer.TYPE);
        Constructor organizationConstructor = testClassLoader.loadClass(ORGANIZATION_CLASS_NAME).getConstructor(String.class, personClass, addressClass);

        IgniteCache cache = ignite.cache("OrganizationCache");

        for (int i = 0; i < 100; i++)
            cache.put(i, organizationConstructor.newInstance("Organization " + i,
                personConstructor.newInstance("Persone name " + i),
                addressConstructor.newInstance("Street " + i, i)));

        assertEquals(cache.size(CachePeekMode.PRIMARY), 100);
    }

    /**
     * @param testClassLoader Test class loader.
     * @param ignite Ignite.
     */
    private void loadEnumItems(ClassLoader testClassLoader, Ignite ignite) throws Exception {
        Method factoryMethod = testClassLoader.loadClass(ENUM_CLASS_NAME).getMethod("valueOf", String.class);

        IgniteCache cache = ignite.cache("SomeCacheEnum");

        for (int i = 0; i < 100; i++)
            cache.put(i, factoryMethod.invoke(null, enumVals[i % enumVals.length]));

        assertEquals(cache.size(CachePeekMode.PRIMARY), 100);
    }

}
