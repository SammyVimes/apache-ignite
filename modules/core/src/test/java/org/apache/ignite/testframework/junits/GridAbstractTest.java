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

package org.apache.ignite.testframework.junits;

import java.io.ObjectStreamException;
import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import javax.cache.configuration.Factory;
import javax.cache.configuration.FactoryBuilder;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.IgniteCheckedException;
import org.apache.ignite.IgniteClientDisconnectedException;
import org.apache.ignite.IgniteException;
import org.apache.ignite.IgniteLogger;
import org.apache.ignite.IgniteSystemProperties;
import org.apache.ignite.Ignition;
import org.apache.ignite.cluster.ClusterNode;
import org.apache.ignite.configuration.CacheConfiguration;
import org.apache.ignite.configuration.DataRegionConfiguration;
import org.apache.ignite.configuration.DataStorageConfiguration;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.configuration.NearCacheConfiguration;
import org.apache.ignite.failure.FailureHandler;
import org.apache.ignite.failure.NoOpFailureHandler;
import org.apache.ignite.internal.IgniteEx;
import org.apache.ignite.internal.IgniteInternalFuture;
import org.apache.ignite.internal.IgniteKernal;
import org.apache.ignite.internal.IgnitionEx;
import org.apache.ignite.internal.binary.BinaryCachingMetadataHandler;
import org.apache.ignite.internal.binary.BinaryContext;
import org.apache.ignite.internal.binary.BinaryEnumCache;
import org.apache.ignite.internal.binary.BinaryMarshaller;
import org.apache.ignite.internal.processors.resource.GridSpringResourceContext;
import org.apache.ignite.internal.util.GridClassLoaderCache;
import org.apache.ignite.internal.util.GridTestClockTimer;
import org.apache.ignite.internal.util.IgniteUtils;
import org.apache.ignite.internal.util.lang.GridAbsPredicate;
import org.apache.ignite.internal.util.typedef.F;
import org.apache.ignite.internal.util.typedef.G;
import org.apache.ignite.internal.util.typedef.T2;
import org.apache.ignite.internal.util.typedef.internal.LT;
import org.apache.ignite.internal.util.typedef.internal.U;
import org.apache.ignite.lang.IgniteCallable;
import org.apache.ignite.lang.IgniteClosure;
import org.apache.ignite.lang.IgniteFuture;
import org.apache.ignite.logger.NullLogger;
import org.apache.ignite.marshaller.MarshallerContextTestImpl;
import org.apache.ignite.marshaller.MarshallerExclusions;
import org.apache.ignite.resources.IgniteInstanceResource;
import org.apache.ignite.spi.discovery.DiscoverySpiCustomMessage;
import org.apache.ignite.spi.discovery.tcp.TcpDiscoverySpi;
import org.apache.ignite.spi.discovery.tcp.ipfinder.TcpDiscoveryIpFinder;
import org.apache.ignite.spi.discovery.tcp.ipfinder.vm.TcpDiscoveryVmIpFinder;
import org.apache.ignite.testframework.GridTestUtils;
import org.apache.ignite.testframework.MvccFeatureChecker;
import org.apache.ignite.testframework.config.GridTestProperties;
import org.apache.ignite.testframework.configvariations.VariationsTestsConfig;
import org.apache.ignite.testframework.junits.logger.GridTestLog4jLogger;
import org.apache.ignite.testframework.junits.multijvm.IgniteCacheProcessProxy;
import org.apache.ignite.testframework.junits.multijvm.IgniteNodeRunner;
import org.apache.ignite.testframework.junits.multijvm.IgniteProcessProxy;
import org.apache.ignite.thread.IgniteThread;
import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.PatternLayout;
import org.apache.log4j.Priority;
import org.apache.log4j.RollingFileAppender;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.FileSystemXmlApplicationContext;

import static org.apache.ignite.IgniteSystemProperties.IGNITE_CLIENT_CACHE_CHANGE_MESSAGE_TIMEOUT;
import static org.apache.ignite.IgniteSystemProperties.IGNITE_DISCO_FAILED_CLIENT_RECONNECT_DELAY;
import static org.apache.ignite.cache.CacheAtomicityMode.TRANSACTIONAL;
import static org.apache.ignite.cache.CacheAtomicityMode.TRANSACTIONAL_SNAPSHOT;
import static org.apache.ignite.cache.CacheWriteSynchronizationMode.FULL_SYNC;

/**
 * Common abstract test for Ignite tests.
 */
@SuppressWarnings({
    "TransientFieldInNonSerializableClass",
    "ProhibitedExceptionDeclared"
})
public abstract class GridAbstractTest extends JUnit3TestLegacySupport {
    /**************************************************************
     * DO NOT REMOVE TRANSIENT - THIS OBJECT MIGHT BE TRANSFERRED *
     *                  TO ANOTHER NODE.                          *
     **************************************************************/
    /** Null name for execution map. */
    private static final String NULL_NAME = UUID.randomUUID().toString();

    /** Shared static IP finder which is used in configuration at nodes startup <b>for all test methods in class</b>. */
    protected static TcpDiscoveryIpFinder sharedStaticIpFinder;

    /** */
    private static final transient Map<Class<?>, IgniteTestResources> tests = new ConcurrentHashMap<>();

    /** */
    protected static final String DEFAULT_CACHE_NAME = "default";

    /** Sustains {@link #beforeTestsStarted()} and {@link #afterTestsStopped()} methods execution.*/
    @ClassRule public static final TestRule firstLastTestRule = new BeforeFirstAndAfterLastTestRule();

    /** Manages test execution and reporting. */
    @Rule public transient TestRule runRule = (base, desc) -> new Statement() {
        @Override public void evaluate() throws Throwable {
            assert getName() != null : "getName returned null";

            runTestCase(base);
        }
    };

    /** */
    private static transient boolean startGrid;

    /** */
    protected static transient IgniteLogger log;

    /** */
    private static transient ClassLoader clsLdr;

    /** */
    private static transient boolean stopGridErr;

    /** Timestamp for tests. */
    private static long ts = System.currentTimeMillis();

    /** Lazily initialized current test method. */
    private volatile Method currTestMtd;

    /** List of system properties to set when all tests in class are finished. */
    private final List<T2<String, String>> clsSysProps = new LinkedList<>();

    /** List of system properties to set when test is finished. */
    private final List<T2<String, String>> testSysProps = new LinkedList<>();

    private final TestIgniteInstanceNameProvider testIgniteInstanceNameProvider = new TestIgniteInstanceNameProvider(getClass());

    private final TestConfigurationProvider configProvider = new TestConfigurationProvider(testIgniteInstanceNameProvider, getTestTimeout(), !isMultiJvm());

    private final TestClusterManager clusterManager = new TestClusterManager(log,
        configProvider, testIgniteInstanceNameProvider, isMultiJvm());



    /** */
    static {
        System.setProperty(IgniteSystemProperties.IGNITE_ALLOW_ATOMIC_OPS_IN_TX, "false");
        System.setProperty(IgniteSystemProperties.IGNITE_ATOMIC_CACHE_DELETE_HISTORY_SIZE, "10000");
        System.setProperty(IgniteSystemProperties.IGNITE_UPDATE_NOTIFIER, "false");
        System.setProperty(IGNITE_DISCO_FAILED_CLIENT_RECONNECT_DELAY, "1");
        System.setProperty(IGNITE_CLIENT_CACHE_CHANGE_MESSAGE_TIMEOUT, "1000");

        if (GridTestClockTimer.startTestTimer()) {
            Thread timer = new Thread(new GridTestClockTimer(), "ignite-clock-for-tests");

            timer.setDaemon(true);

            timer.setPriority(10);

            timer.start();
        }
    }

    /** */
    private static final ConcurrentMap<UUID, Object> serializedObj = new ConcurrentHashMap<>();

    /** */
    protected GridAbstractTest() throws IgniteCheckedException {
        this(false);

        log = getTestResources().getLogger().getLogger(getClass());
    }

    /**
     * @param startGrid Start grid flag.
     */
    @SuppressWarnings({"OverriddenMethodCallDuringObjectConstruction"})
    protected GridAbstractTest(boolean startGrid) {
        assert isJunitFrameworkClass() : "GridAbstractTest class cannot be extended directly " +
            "(use GridCommonAbstractTest class instead).";

        // Initialize properties. Logger initialized here.
        GridTestProperties.init();

        log = new GridTestLog4jLogger();

        GridAbstractTest.startGrid = startGrid;
    }

    /**
     * @return Flag to check if class is Junit framework class.
     */
    protected boolean isJunitFrameworkClass() {
        return false;
    }

    /**
     * @return Test resources.
     */
    protected IgniteTestResources getTestResources() throws IgniteCheckedException {
        synchronized (this) {
            IgniteTestResources rsrcs = tests.get(getClass());

            if (rsrcs == null)
                tests.put(getClass(), rsrcs = new IgniteTestResources());

            return rsrcs;
        }
    }

    /**
     * @param msg Message to print.
     */
    protected void info(String msg) {
        if (log().isInfoEnabled())
            log().info(msg);
    }

    /**
     * @param msg Message to print.
     */
    protected void error(String msg) {
        log().error(msg);
    }

    /**
     * @param msg Message to print.
     * @param t Error to print.
     */
    protected void error(String msg, Throwable t) {
        log().error(msg, t);
    }

    /**
     * @return logger.
     */
    protected IgniteLogger log() {
        if (GridTestUtils.isCurrentJvmRemote())
            return IgniteNodeRunner.startedInstance().log();

        return log;
    }

    /**
     * Resets log4j programmatically.
     *
     * @param log4jLevel Level.
     * @param logToFile If {@code true}, then log to file under "work/log" folder.
     * @param cat Category.
     * @param cats Additional categories.
     */
    @SuppressWarnings({"deprecation"})
    protected void resetLog4j(Level log4jLevel, boolean logToFile, String cat, String... cats)
        throws IgniteCheckedException {
        for (String c : F.concat(false, cat, F.asList(cats)))
            Logger.getLogger(c).setLevel(log4jLevel);

        if (logToFile) {
            Logger log4j = Logger.getRootLogger();

            log4j.removeAllAppenders();

            // Console appender.
            ConsoleAppender c = new ConsoleAppender();

            c.setName("CONSOLE_ERR");
            c.setTarget("System.err");
            c.setThreshold(Priority.WARN);
            c.setLayout(new PatternLayout("[%d{ISO8601}][%-5p][%t][%c{1}] %m%n"));

            c.activateOptions();

            log4j.addAppender(c);

            // File appender.
            RollingFileAppender file = new RollingFileAppender();

            file.setName("FILE");
            file.setThreshold(log4jLevel);
            file.setFile(home() + "/work/log/ignite.log");
            file.setAppend(false);
            file.setMaxFileSize("10MB");
            file.setMaxBackupIndex(10);
            file.setLayout(new PatternLayout("[%d{ISO8601}][%-5p][%t][%c{1}] %m%n"));

            file.activateOptions();

            log4j.addAppender(file);
        }
    }

    /**
     * Runs given code in multiple threads. Returns future that ends upon
     * threads completion. If any thread failed, exception will be thrown
     * out of this method.
     *
     * @param r Runnable.
     * @param threadNum Thread number.
     * @throws Exception If failed.
     * @return Future.
     */
    protected IgniteInternalFuture<?> multithreadedAsync(Runnable r, int threadNum) throws Exception {
        return multithreadedAsync(r, threadNum, getTestIgniteInstanceName());
    }

    /**
     * Runs given code in multiple threads. Returns future that ends upon
     * threads completion. If any thread failed, exception will be thrown
     * out of this method.
     *
     * @param r Runnable.
     * @param threadNum Thread number.
     * @param threadName Thread name.
     * @throws Exception If failed.
     * @return Future.
     */
    protected IgniteInternalFuture<?> multithreadedAsync(Runnable r, int threadNum, String threadName) throws Exception {
        return GridTestUtils.runMultiThreadedAsync(r, threadNum, threadName);
    }

    /**
     * Runs given code in multiple threads and synchronously waits for all threads to complete.
     * If any thread failed, exception will be thrown out of this method.
     *
     * @param c Callable.
     * @param threadNum Thread number.
     * @throws Exception If failed.
     */
    protected void multithreaded(Callable<?> c, int threadNum) throws Exception {
        multithreaded(c, threadNum, getTestIgniteInstanceName());
    }

    /**
     * Runs given code in multiple threads and synchronously waits for all threads to complete.
     * If any thread failed, exception will be thrown out of this method.
     *
     * @param c Callable.
     * @param threadNum Thread number.
     * @param threadName Thread name.
     * @throws Exception If failed.
     */
    protected void multithreaded(Callable<?> c, int threadNum, String threadName) throws Exception {
        GridTestUtils.runMultiThreaded(c, threadNum, threadName);
    }

    /**
     * Runs given code in multiple threads and asynchronously waits for all threads to complete.
     * If any thread failed, exception will be thrown out of this method.
     *
     * @param c Callable.
     * @param threadNum Thread number.
     * @throws Exception If failed.
     * @return Future.
     */
    protected IgniteInternalFuture<?> multithreadedAsync(Callable<?> c, int threadNum) throws Exception {
        return multithreadedAsync(c, threadNum, getTestIgniteInstanceName());
    }

    /**
     * Runs given code in multiple threads and asynchronously waits for all threads to complete.
     * If any thread failed, exception will be thrown out of this method.
     *
     * @param c Callable.
     * @param threadNum Thread number.
     * @param threadName Thread name.
     * @throws Exception If failed.
     * @return Future.
     */
    protected IgniteInternalFuture<?> multithreadedAsync(Callable<?> c, int threadNum,
        String threadName) throws Exception {
        return GridTestUtils.runMultiThreadedAsync(c, threadNum, threadName);
    }

    /**
     * @return Test kernal context.
     */
    protected GridTestKernalContext newContext() throws IgniteCheckedException {
        IgniteConfiguration cfg = new IgniteConfiguration();

        cfg.setClientMode(false);
        cfg.setDiscoverySpi(new TcpDiscoverySpi() {
            @Override public void sendCustomEvent(DiscoverySpiCustomMessage msg) throws IgniteException {
                //No-op
            }
        });

        GridTestKernalContext ctx = new GridTestKernalContext(log(), cfg);
        return ctx;
    }

    /**
     * @param cfg Configuration to use in Test
     * @return Test kernal context.
     */
    protected GridTestKernalContext newContext(IgniteConfiguration cfg) throws IgniteCheckedException {
        return new GridTestKernalContext(log(), cfg);
    }

    /**
     * Will clean and re-create marshaller directory from scratch.
     */
    private void resolveWorkDirectory() throws Exception {
        U.resolveWorkDirectory(U.defaultWorkDirectory(), "marshaller", true);
        U.resolveWorkDirectory(U.defaultWorkDirectory(), "binary_meta", true);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Do not annotate with Before in overriding methods.</p>
     * @deprecated This method is deprecated. Instead of invoking or overriding it, it is recommended to make your own
     * method with {@code @Before} annotation.
     */
    @Deprecated
    @Override protected void setUp() throws Exception {
        stopGridErr = false;

        clsLdr = Thread.currentThread().getContextClassLoader();

        // Change it to the class one.
        Thread.currentThread().setContextClassLoader(getClass().getClassLoader());

        // Clear log throttle.
        LT.clear();

        info(">>> Starting test: " + testDescription() + " <<<");

        try {
            beforeTest();
        }
        catch (Exception | Error t) {
            try {
                tearDown();
            }
            catch (Exception e) {
                log.error("Failed to tear down test after exception was thrown in beforeTest (will ignore)", e);
            }

            throw t;
        }

        ts = System.currentTimeMillis();
    }

    /** */
    private void beforeFirstTest() throws Exception {
        sharedStaticIpFinder = new TcpDiscoveryVmIpFinder(true);

        info(">>> Starting test class: " + testClassDescription() + " <<<");

        if (isSafeTopology())
            assert G.allGrids().isEmpty() : "Not all Ignite instances stopped before tests execution:" + G.allGrids();

        if (startGrid) {
            clusterManager.startGrid();
        }

        try {
            List<Integer> jvmIds = IgniteNodeRunner.killAll();

            if (!jvmIds.isEmpty())
                log.info("Next processes of IgniteNodeRunner were killed: " + jvmIds);

            resolveWorkDirectory();

            beforeTestsStarted();
        }
        catch (Exception | Error t) {
            t.printStackTrace();

            try {
                tearDown();
            }
            catch (Exception e) {
                log.error("Failed to tear down test after exception was thrown in beforeTestsStarted (will " +
                    "ignore)", e);
            }

            throw t;
        }
    }

    /** */
    private void setSystemPropertiesBeforeClass() {
        List<WithSystemProperty[]> allProps = new LinkedList<>();

        for (Class<?> clazz = getClass(); clazz != GridAbstractTest.class; clazz = clazz.getSuperclass()) {
            SystemPropertiesList clsProps = clazz.getAnnotation(SystemPropertiesList.class);

            if (clsProps != null)
                allProps.add(0, clsProps.value());
            else {
                WithSystemProperty clsProp = clazz.getAnnotation(WithSystemProperty.class);

                if (clsProp != null)
                    allProps.add(0, new WithSystemProperty[] {clsProp});
            }
        }

        for (WithSystemProperty[] props : allProps) {
            for (WithSystemProperty prop : props) {
                String oldVal = System.setProperty(prop.key(), prop.value());

                clsSysProps.add(0, new T2<>(prop.key(), oldVal));
            }
        }
    }

    /** */
    private void clearSystemPropertiesAfterClass() {
        for (T2<String, String> t2 : clsSysProps) {
            if (t2.getValue() == null)
                System.clearProperty(t2.getKey());
            else
                System.setProperty(t2.getKey(), t2.getValue());
        }

        clsSysProps.clear();
    }

    /** */
    @Before
    public void setSystemPropertiesBeforeTest() {
        WithSystemProperty[] allProps = null;

        SystemPropertiesList testProps = currentTestAnnotation(SystemPropertiesList.class);

        if (testProps != null)
            allProps = testProps.value();
        else {
            WithSystemProperty testProp = currentTestAnnotation(WithSystemProperty.class);

            if (testProp != null)
                allProps = new WithSystemProperty[] {testProp};
        }

        if (allProps != null) {
            for (WithSystemProperty prop : allProps) {
                String oldVal = System.setProperty(prop.key(), prop.value());

                testSysProps.add(0, new T2<>(prop.key(), oldVal));
            }
        }
    }

    /** */
    @After
    public void clearSystemPropertiesAfterTest() {
        for (T2<String, String> t2 : testSysProps) {
            if (t2.getValue() == null)
                System.clearProperty(t2.getKey());
            else
                System.setProperty(t2.getKey(), t2.getValue());
        }

        testSysProps.clear();
    }

    /**
     * @return Test description.
     */
    protected String testDescription() {
        return GridTestUtils.fullSimpleName(getClass()) + "#" + getName();
    }

    /**
     * @return Test class description.
     */
    protected String testClassDescription() {
        return GridTestUtils.fullSimpleName(getClass());
    }

    /**
     * @return Current test method.
     * @throws NoSuchMethodError If method wasn't found for some reason.
     */
    @NotNull protected Method currentTestMethod() {
        if (currTestMtd == null) {
            try {
                String testName = getName();

                int bracketIdx = testName.indexOf('[');

                String mtdName = bracketIdx >= 0 ? testName.substring(0, bracketIdx) : testName;

                currTestMtd = getClass().getMethod(mtdName);
            }
            catch (NoSuchMethodException e) {
                throw new NoSuchMethodError("Current test method is not found: " + getName());
            }
        }

        return currTestMtd;
    }

    /**
     * Search for the annotation of the given type in current test method.
     *
     * @param annotationCls Type of annotation to look for.
     * @param <A> Annotation type.
     * @return Instance of annotation if it is present in test method.
     */
    @Nullable protected <A extends Annotation> A currentTestAnnotation(Class<A> annotationCls) {
        return currentTestMethod().getAnnotation(annotationCls);
    }

    /**
     * Check or not topology after grids start
     */
    protected boolean checkTopology() {
        return true;
    }

    /**
     * @param cnt Grid count
     * @throws Exception If an error occurs.
     */
    @SuppressWarnings({"BusyWait"})
    protected void checkTopology(int cnt) throws Exception {
        clusterManager.checkTopology(cnt);
    }

    /**
     * @param regionCfg Region config.
     */
    private void validateDataRegion(DataRegionConfiguration regionCfg) {
        if (regionCfg.isPersistenceEnabled() && regionCfg.getMaxSize() == DataStorageConfiguration.DFLT_DATA_REGION_MAX_SIZE)
            throw new AssertionError("Max size of data region should be set explicitly to avoid memory over usage");
    }

    /**
     * @param cfg Config.
     */
    private void validateConfiguration(IgniteConfiguration cfg) {
        if (cfg.getDataStorageConfiguration() != null) {
            validateDataRegion(cfg.getDataStorageConfiguration().getDefaultDataRegionConfiguration());

            if (cfg.getDataStorageConfiguration().getDataRegionConfigurations() != null) {
                for (DataRegionConfiguration reg : cfg.getDataStorageConfiguration().getDataRegionConfigurations())
                    validateDataRegion(reg);
            }
        }
    }

    /**
     * @return Additional JVM args for remote instances.
     */
    protected List<String> additionalRemoteJvmArgs() {
        return Collections.emptyList();
    }

    /**
     * @param ignite Grid
     * @param cnt Count
     * @throws IgniteCheckedException If failed.
     */
    @SuppressWarnings({"BusyWait"})
    protected void waitForRemoteNodes(Ignite ignite, int cnt) throws IgniteCheckedException {
        while (true) {
            Collection<ClusterNode> nodes = ignite.cluster().forRemotes().nodes();

            if (nodes != null && nodes.size() >= cnt)
                return;

            try {
                Thread.sleep(100);
            }
            catch (InterruptedException ignored) {
                throw new IgniteCheckedException("Interrupted while waiting for remote nodes [igniteInstanceName=" +
                    ignite.name() + ", count=" + cnt + ']');
            }
        }
    }

    /**
     * @param ignites Grids
     * @throws IgniteCheckedException If failed.
     */
    protected void waitForDiscovery(Ignite... ignites) throws IgniteCheckedException {
        assert ignites != null;
        assert ignites.length > 1;

        for (Ignite ignite : ignites)
            waitForRemoteNodes(ignite, ignites.length - 1);
    }

    /**
     * Create instance of {@link BinaryMarshaller} suitable for use
     * without starting a grid upon an empty {@link IgniteConfiguration}.
     *
     * @return Binary marshaller.
     * @throws IgniteCheckedException if failed.
     */
    protected BinaryMarshaller createStandaloneBinaryMarshaller() throws IgniteCheckedException {
        return createStandaloneBinaryMarshaller(new IgniteConfiguration());
    }

    /**
     * Create instance of {@link BinaryMarshaller} suitable for use
     * without starting a grid upon given {@link IgniteConfiguration}.
     *
     * @return Binary marshaller.
     * @throws IgniteCheckedException if failed.
     */
    protected BinaryMarshaller createStandaloneBinaryMarshaller(IgniteConfiguration cfg) throws IgniteCheckedException {
        BinaryMarshaller marsh = new BinaryMarshaller();

        BinaryContext ctx = new BinaryContext(BinaryCachingMetadataHandler.create(), cfg, new NullLogger());

        marsh.setContext(new MarshallerContextTestImpl());

        IgniteUtils.invoke(BinaryMarshaller.class, marsh, "setBinaryContext", ctx, cfg);

        return marsh;
    }

    /**
     * @return Generated unique test Ignite instance name.
     */
    public String getTestIgniteInstanceName() {
        return testIgniteInstanceNameProvider.getTestIgniteInstanceName();
    }

    /**
     * @param idx Index of the Ignite instance.
     * @return Indexed Ignite instance name.
     */
    public String getTestIgniteInstanceName(int idx) {
        return testIgniteInstanceNameProvider.getTestIgniteInstanceName(idx);
    }

    /**
     * Parses test Ignite instance index from test Ignite instance name.
     *
     * @param testIgniteInstanceName Test Ignite instance name, returned by {@link #getTestIgniteInstanceName(int)}.
     * @return Test Ignite instance index.
     */
    public int getTestIgniteInstanceIndex(String testIgniteInstanceName) {
        return testIgniteInstanceNameProvider.getTestIgniteInstanceIndex(testIgniteInstanceName);
    }

    /**
     * @param name Name to mask.
     * @return Masked name.
     */
    private static String maskNull(String name) {
        return name == null ? NULL_NAME : name;
    }

    /**
     * @return Ignite home.
     */
    protected String home() throws IgniteCheckedException {
        return getTestResources().getIgniteHome();
    }

    /**
     * This method should be overridden by subclasses to change failure handler implementation.
     *
     * @param igniteInstanceName Ignite instance name.
     * @return Failure handler implementation.
     */
    protected FailureHandler getFailureHandler(String igniteInstanceName) {
        return new NoOpFailureHandler();
    }

    /**
     * @return New cache configuration with modified defaults.
     */
    @SuppressWarnings("unchecked")
    public static CacheConfiguration defaultCacheConfiguration() {
        CacheConfiguration cfg = new CacheConfiguration(DEFAULT_CACHE_NAME);

        if (MvccFeatureChecker.forcedMvcc())
            cfg.setAtomicityMode(TRANSACTIONAL_SNAPSHOT);
        else
            cfg.setAtomicityMode(TRANSACTIONAL).setNearConfiguration(new NearCacheConfiguration<>());
        cfg.setWriteSynchronizationMode(FULL_SYNC);
        cfg.setEvictionPolicy(null);

        return cfg;
    }

    /**
     * Gets external class loader.
     *
     * @return External class loader.
     */
    protected static ClassLoader getExternalClassLoader() {
        String path = GridTestProperties.getProperty("p2p.uri.cls");

        try {
            return new URLClassLoader(new URL[] {new URL(path)}, U.gridClassLoader());
        }
        catch (MalformedURLException e) {
            throw new RuntimeException("Failed to create URL: " + path, e);
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * Do not annotate with After in overriding methods.</p>
     * @deprecated This method is deprecated. Instead of invoking or overriding it, it is recommended to make your own
     * method with {@code @After} annotation.
     */
    @Deprecated
    @Override protected void tearDown() throws Exception {
        long dur = System.currentTimeMillis() - ts;

        info(">>> Stopping test: " + testDescription() + " in " + dur + " ms <<<");

        try {
            afterTest();
        }
        finally {
            serializedObj.clear();

            Thread.currentThread().setContextClassLoader(clsLdr);

            clsLdr = null;

            cleanReferences();
        }
    }

    /** */
    private void afterLastTest() throws Exception {
        info(">>> Stopping test class: " + testClassDescription() + " <<<");

        Exception err = null;

        // Stop all threads started by runMultithreaded() methods.
        GridTestUtils.stopThreads(log);

        // Safety.
        getTestResources().stopThreads();

        try {
            afterTestsStopped();
        }
        catch (Exception e) {
            err = e;
        }

        if (isSafeTopology()) {
            stopAllGrids(true);

            if (stopGridErr) {
                err = new RuntimeException("Not all Ignite instances has been stopped. " +
                    "Please, see log for details.", err);
            }
        }

        // Remove resources.
        tests.remove(getClass());

        // Remove resources cached in static, if any.
        GridClassLoaderCache.clear();
        U.clearClassCache();
        MarshallerExclusions.clearCache();
        BinaryEnumCache.clear();

        if (err!= null)
            throw err;
    }

    /**
     *
     */
    protected void cleanReferences() {
        Class cls = getClass();

        while (cls != null) {
            Field[] fields = getClass().getDeclaredFields();

            for (Field f : fields) {
                if (Modifier.isStatic(f.getModifiers()))
                    continue;

                f.setAccessible(true);

                try {
                    f.set(this, null);
                }
                catch (Exception ignored) {
                }
            }

            cls = cls.getSuperclass();
        }
    }

    /**
     * Gets flag whether nodes will run in one JVM or in separate JVMs.
     *
     * @return <code>True</code> to run nodes in separate JVMs.
     * @see IgniteNodeRunner
     * @see IgniteProcessProxy
     * @see GridTestUtils#isCurrentJvmRemote()
     * @see #isRemoteJvm(int)
     * @see #isRemoteJvm(String)
     * @see #executeOnLocalOrRemoteJvm(int, TestIgniteIdxCallable)
     * @see #executeOnLocalOrRemoteJvm(Ignite, GridTestUtils.TestIgniteCallable)
     * @see #executeOnLocalOrRemoteJvm(IgniteCache, TestCacheCallable)
     */
    protected boolean isMultiJvm() {
        return false;
    }

    /**
     * By default, test would started only if there is no alive Ignite instances and after {@link #afterTestsStopped()}
     * all started Ignite instances would be stopped. Should return <code>false</code> if alive Ingite instances
     * after test execution is correct behavior.
     *
     * @return <code>True</code> by default.
     * @see VariationsTestsConfig#isStopNodes() Example of why instances should not be stopped.
     */
    protected boolean isSafeTopology() {
        return true;
    }

    /**
     * @param igniteInstanceName Ignite instance name.
     * @return {@code True} if the name of the grid indicates that it was the first started (on this JVM).
     */
    protected boolean isFirstGrid(String igniteInstanceName) {
        return clusterManager.isFirstGrid(igniteInstanceName);
    }

    /**
     * @param igniteInstanceName Ignite instance name.
     * @return <code>True</code> if test was run in multi-JVM mode and grid with this name was started at another JVM.
     */
    protected boolean isRemoteJvm(String igniteInstanceName) {
        return clusterManager.isRemoteJvm(igniteInstanceName);
    }

    /**
     * @param idx Grid index.
     * @return <code>True</code> if test was run in multi-JVM mode and grid with this ID was started at another JVM.
     */
    protected boolean isRemoteJvm(int idx) {
        return isMultiJvm() && idx != 0;
    }

    /**
     * Calls job on local JVM or on remote JVM in multi-JVM case.
     *
     * @param idx Grid index.
     * @param job Job.
     */
    public <R> R executeOnLocalOrRemoteJvm(final int idx, final TestIgniteIdxCallable<R> job) {
        IgniteEx ignite = ignite(idx);

        if (!GridTestUtils.isMultiJvmObject(ignite))
            try {
                job.setIgnite(ignite);

                return job.call(idx);
            }
            catch (Exception e) {
                throw new IgniteException(e);
            }
        else
            return executeRemotely(idx, job);
    }

    /**
     * Calls job on local JVM or on remote JVM in multi-JVM case.
     *
     * @param ignite Ignite.
     * @param job Job.
     */
    public static <R> R executeOnLocalOrRemoteJvm(Ignite ignite, final GridTestUtils.TestIgniteCallable<R> job) {
        if (!GridTestUtils.isMultiJvmObject(ignite))
            try {
                return job.call(ignite);
            }
            catch (Exception e) {
                throw new IgniteException(e);
            }
        else
            return GridTestUtils.executeRemotely((IgniteProcessProxy)ignite, job);
    }

    /**
     * Calls job on local JVM or on remote JVM in multi-JVM case.
     *
     * @param cache Cache.
     * @param job Job.
     */
    public static <K, V, R> R executeOnLocalOrRemoteJvm(IgniteCache<K, V> cache, TestCacheCallable<K, V, R> job) {
        Ignite ignite = cache.unwrap(Ignite.class);

        if (!GridTestUtils.isMultiJvmObject(ignite))
            try {
                return job.call(ignite, cache);
            }
            catch (Exception e) {
                throw new IgniteException(e);
            }
        else
            return executeRemotely((IgniteCacheProcessProxy<K, V>)cache, job);
    }

    /**
     * Calls job on remote JVM.
     *
     * @param idx Grid index.
     * @param job Job.
     */
    public <R> R executeRemotely(final int idx, final TestIgniteIdxCallable<R> job) {
        return executeRemotely(ignite(idx), job, idx);
    }

    /**
     * Calls job on remote JVM.
     *
     * @param ignite Ignite instance.
     * @param job Job.
     */
    public static <R> R executeRemotely(IgniteEx ignite, final TestIgniteIdxCallable<R> job, int idx) {
        if (!GridTestUtils.isMultiJvmObject(ignite))
            throw new IllegalArgumentException("Ignite have to be process proxy.");

        IgniteProcessProxy proxy = (IgniteProcessProxy)ignite;

        return proxy.remoteCompute().call(new ExecuteRemotelyTask<>(job, idx));
    }

    /**
     * Runs job on remote JVM.
     *
     * @param cache Cache.
     * @param job Job.
     */
    public static <K, V, R> R executeRemotely(IgniteCacheProcessProxy<K, V> cache,
        final TestCacheCallable<K, V, R> job) {
        IgniteProcessProxy proxy = (IgniteProcessProxy)cache.unwrap(Ignite.class);

        final UUID id = proxy.getId();
        final String cacheName = cache.getName();

        return proxy.remoteCompute().call(new IgniteCallable<R>() {
            private static final long serialVersionUID = -3868429485920845137L;

            @Override public R call() throws Exception {
                Ignite ignite = Ignition.ignite(id);
                IgniteCache<K, V> cache = ignite.cache(cacheName);

                return job.call(ignite, cache);
            }
        });
    }

    /** {@inheritDoc} */
    @Override void runTest(Statement testRoutine) throws Throwable {
        final AtomicReference<Throwable> ex = new AtomicReference<>();

        Thread runner = new IgniteThread(getTestIgniteInstanceName(), "test-runner", new Runnable() {
            @Override public void run() {
                try {
                    testRoutine.evaluate();
                }
                catch (Throwable e) {
                    IgniteClosure<Throwable, Throwable> hnd = errorHandler();

                    ex.set(hnd != null ? hnd.apply(e) : e);
                }
            }
        });

        runner.start();

        runner.join(GridTestUtils.isDebugMode() ? 0 : getTestTimeout());

        if (runner.isAlive()) {
            U.error(log,
                "Test has been timed out and will be interrupted (threads dump will be taken before interruption) [" +
                    "test=" + getName() + ", timeout=" + getTestTimeout() + ']');

            List<Ignite> nodes = IgnitionEx.allGridsx();

            for (Ignite node : nodes)
                ((IgniteKernal)node).dumpDebugInfo();

            // We dump threads to stdout, because we can loose logs in case
            // the build is cancelled on TeamCity.
            U.dumpThreads(null);

            U.dumpThreads(log);

            U.interrupt(runner);

            U.join(runner, log);

            throw new TimeoutException("Test has been timed out [test=" + getName() + ", timeout=" +
                getTestTimeout() + ']');
        }

        Throwable t = ex.get();

        if (t != null) {
            U.error(log, "Test failed.", t);

            throw t;
        }

        assert !stopGridErr : "Error occurred on grid stop (see log for more details).";
    }

    /**
     * @return Error handler to process all uncaught exceptions of the test run ({@code null} by default).
     */
    protected IgniteClosure<Throwable, Throwable> errorHandler() {
        return null;
    }

    /**
     * @return Test case timeout.
     */
    protected long getTestTimeout() {
        return getDefaultTestTimeout();
    }

    /**
     * @return Default test case timeout.
     */
    private long getDefaultTestTimeout() {
        String timeout = GridTestProperties.getProperty("test.timeout");

        if (timeout != null)
            return Long.parseLong(timeout);

        return GridTestUtils.DFLT_TEST_TIMEOUT;
    }

    /**
     * @param store Store.
     */
    protected static <T> Factory<T> singletonFactory(T store) {
        return notSerializableProxy(new FactoryBuilder.SingletonFactory<>(store), Factory.class);
    }

    /**
     * @param obj Object that should be wrap proxy
     * @return Created proxy.
     */
    protected static <T> T notSerializableProxy(final T obj) {
        Class<T> cls = (Class<T>)obj.getClass();

        Class<T>[] interfaces = (Class<T>[])cls.getInterfaces();

        assert interfaces.length > 0;

        Class<T> lastItf = interfaces[interfaces.length - 1];

        return notSerializableProxy(obj, lastItf, Arrays.copyOf(interfaces, interfaces.length - 1));
    }

    /**
     * @param obj Object that should be wrap proxy
     * @param itfCls Interface that should be implemented by proxy
     * @param itfClses Interfaces that should be implemented by proxy (vararg parameter)
     * @return Created proxy.
     */
    protected static <T> T notSerializableProxy(final T obj, Class<? super T> itfCls, Class<? super T>... itfClses) {
        Class<?>[] itfs = Arrays.copyOf(itfClses, itfClses.length + 3);

        itfs[itfClses.length] = itfCls;
        itfs[itfClses.length + 1] = Serializable.class;
        itfs[itfClses.length + 2] = WriteReplaceOwner.class;

        return (T)Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(), itfs, new InvocationHandler() {
            @Override public Object invoke(Object proxy, Method mtd, Object[] args) throws Throwable {
                if ("writeReplace".equals(mtd.getName()) && mtd.getParameterTypes().length == 0)
                    return supressSerialization(proxy);

                return mtd.invoke(obj, args);
            }
        });
    }

    /**
     * Returns an object that should be returned from writeReplace() method.
     *
     * @param obj Object that must not be changed after serialization/deserialization.
     * @return An object to return from writeReplace()
     */
    private static Object supressSerialization(Object obj) {
        SerializableProxy res = new SerializableProxy(UUID.randomUUID());

        serializedObj.put(res.uuid, obj);

        return res;
    }

    /**
     * @param expSize Expected nodes number.
     * @throws Exception If failed.
     */
    protected void waitForTopology(final int expSize) throws Exception {
        assertTrue(GridTestUtils.waitForCondition(new GridAbsPredicate() {
            @Override public boolean apply() {
                List<Ignite> nodes = G.allGrids();

                if (nodes.size() != expSize) {
                    info("Wait all nodes [size=" + nodes.size() + ", exp=" + expSize + ']');

                    return false;
                }

                for (Ignite node : nodes) {
                    try {
                        IgniteFuture<?> reconnectFut = node.cluster().clientReconnectFuture();

                        if (reconnectFut != null && !reconnectFut.isDone()) {
                            info("Wait for size on node, reconnect is in progress [node=" + node.name() + ']');

                            return false;
                        }

                        int sizeOnNode = node.cluster().nodes().size();

                        if (sizeOnNode != expSize) {
                            info("Wait for size on node [node=" + node.name() + ", size=" + sizeOnNode + ", exp=" + expSize + ']');

                            return false;
                        }
                    }
                    catch (IgniteClientDisconnectedException e) {
                        info("Wait for size on node, node disconnected [node=" + node.name() + ']');

                        return false;
                    }
                }

                return true;
            }
        }, 30_000));
    }

    /**
     * Optimizes configuration to achieve better test performance.
     *
     * @param cfg Configuration.
     * @return Optimized configuration (by modifying passed in one).
     * @throws IgniteCheckedException On error.
     */
    protected IgniteConfiguration optimize(IgniteConfiguration cfg) throws IgniteCheckedException {
        return configProvider.optimize(cfg);
    }

    /**
     * Loads configuration from the given Spring XML file.
     *
     * @param springCfgPath Path to file.
     * @return Grid configuration.
     * @throws IgniteCheckedException If load failed.
     */
    @SuppressWarnings("deprecation")
    protected IgniteConfiguration loadConfiguration(String springCfgPath) throws IgniteCheckedException {
        URL cfgLocation = U.resolveIgniteUrl(springCfgPath);

        if (cfgLocation == null)
            cfgLocation = U.resolveIgniteUrl(springCfgPath, false);

        assert cfgLocation != null;

        ApplicationContext springCtx;

        try {
            springCtx = new FileSystemXmlApplicationContext(cfgLocation.toString());
        }
        catch (BeansException e) {
            throw new IgniteCheckedException("Failed to instantiate Spring XML application context.", e);
        }

        Map cfgMap;

        try {
            // Note: Spring is not generics-friendly.
            cfgMap = springCtx.getBeansOfType(IgniteConfiguration.class);
        }
        catch (BeansException e) {
            throw new IgniteCheckedException("Failed to instantiate bean [type=" + IgniteConfiguration.class + ", err=" +
                e.getMessage() + ']', e);
        }

        if (cfgMap == null)
            throw new IgniteCheckedException("Failed to find a single grid factory configuration in: " + springCfgPath);

        if (cfgMap.isEmpty())
            throw new IgniteCheckedException("Can't find grid factory configuration in: " + springCfgPath);
        else if (cfgMap.size() > 1)
            throw new IgniteCheckedException("More than one configuration provided for cache load test: " + cfgMap.values());

        IgniteConfiguration cfg = (IgniteConfiguration)cfgMap.values().iterator().next();

        cfg.setNodeId(UUID.randomUUID());

        return cfg;
    }

    /**
     * This method should be overridden by subclasses to change configuration parameters.
     *
     * @param igniteInstanceName Ignite instance name.
     * @return Grid configuration used for starting of grid.
     * @throws Exception If failed.
     */
    @SuppressWarnings("deprecation")
    protected IgniteConfiguration getConfiguration(String igniteInstanceName) throws Exception {
        return configProvider.getConfiguration(igniteInstanceName);
    }

    /**
     * @param nodeIdx Node index.
     * @return Node ID.
     */
    protected final UUID nodeId(int nodeIdx) {
        return ignite(nodeIdx).cluster().localNode().id();
    }

    /**
     * Gets grid for given test.
     *
     * @return Grid for given test.
     */
    protected IgniteEx ignite() {
        return clusterManager.ignite();
    }

    /**
     * Gets grid for given index.
     *
     * @param idx Index.
     * @return Grid instance.
     */
    protected IgniteEx ignite(int idx) {
        return clusterManager.ignite(idx);
    }

    /**
     * Gets grid for given name.
     *
     * @param name Name.
     * @return Grid instance.
     */
    protected IgniteEx ignite(String name) {
        return clusterManager.ignite(name);
    }

    /**
     * @param node Node.
     * @return Ignite instance with given local node.
     */
    protected final IgniteEx ignite(ClusterNode node) {
        if (!isMultiJvm())
            return (IgniteEx)G.ignite(node.id());
        else {
            try {
                return (IgniteEx)IgniteProcessProxy.ignite(node.id());
            }
            catch (Exception ignore) {
                // A hack if it is local grid.
                return (IgniteEx)G.ignite(node.id());
            }
        }
    }

    /**
     * @return Started grid.
     * @throws Exception If anything failed.
     */
    protected IgniteEx clusterManager__startGrid() throws Exception {
        return clusterManager.startGrid();
    }

    /**
     * Starts new grid with given index.
     *
     * @param idx Index of the grid to start.
     * @return Started grid.
     * @throws Exception If anything failed.
     */
    protected IgniteEx startGrid(int idx) throws Exception {
        return clusterManager.startGrid(idx);
    }

    /**
     * Starts new grid with given name.
     *
     * @param igniteInstanceName Ignite instance name.
     * @return Started grid.
     * @throws Exception If failed.
     */
    protected IgniteEx startGrid(String igniteInstanceName) throws Exception {
        return clusterManager.startGrid(igniteInstanceName);
    }

    /**
     * Starts new grid with given configuration.
     *
     * @param cfg Ignite configuration.
     * @return Started grid.
     * @throws Exception If anything failed.
     */
    protected IgniteEx startGrid(IgniteConfiguration cfg) throws Exception {
        return clusterManager.startGrid(cfg);
    }


    /**
     * Starts new grid with given configuration.
     *
     * @param igniteInstanceName Ignite instance name (would be used for generation configuration).
     * @param cfgTransformer Configuration transformer.
     * @return Started grid.
     * @throws Exception If anything failed.
     */
    protected IgniteEx startGrid(String igniteInstanceName, Function<IgniteConfiguration, IgniteConfiguration> cfgTransformer) throws Exception {
        return clusterManager.startGrid(igniteInstanceName, cfgTransformer);
    }

    /**
     * Starts new grid with given configuration.
     *
     * @param cfgTransformer Ignite configuration.
     * @return Started grid.
     * @throws Exception If anything failed.
     */
    protected IgniteEx startGrid(Function<IgniteConfiguration, IgniteConfiguration> cfgTransformer) throws Exception {
        return clusterManager.startGrid(cfgTransformer);
    }

    /**
     * Starts grid using provided Ignite instance name and spring config location.
     * <p>
     * Note that grids started this way should be stopped with {@code G.stop(..)} methods.
     *
     * @param igniteInstanceName Ignite instance name.
     * @param springCfgPath Path to config file.
     * @return Grid Started grid.
     * @throws Exception If failed.
     */
    protected Ignite startGrid(String igniteInstanceName, String springCfgPath) throws Exception {
        IgniteConfiguration cfg = loadConfiguration(springCfgPath);

        cfg.setFailureHandler(getFailureHandler(igniteInstanceName));

        cfg.setGridLogger(getTestResources().getLogger());

        return startGrid(igniteInstanceName, cfg);
    }

    /**
     * Starts grid using provided Ignite instance name and config.
     * <p>
     * Note that grids started this way should be stopped with {@code G.stop(..)} methods.
     *
     * @param igniteInstanceName Ignite instance name.
     * @param cfg Config.
     * @return Grid Started grid.
     * @throws Exception If failed.
     */
    protected Ignite startGrid(String igniteInstanceName, IgniteConfiguration cfg) throws Exception {
        cfg.setIgniteInstanceName(igniteInstanceName);

        if (!isRemoteJvm(igniteInstanceName))
            return G.start(cfg);
        else
            return startRemoteGrid(igniteInstanceName, cfg);
    }


    /**
     * Starts new grid with given index and Spring application context.
     *
     * @param idx Index of the grid to start.
     * @param ctx Spring context.
     * @return Started grid.
     * @throws Exception If anything failed.
     */
    protected Ignite startGrid(int idx, GridSpringResourceContext ctx) throws Exception {
        return clusterManager.startGrid(idx, ctx);
    }

    /**
     * Starts new grid with given name.
     *
     * @param igniteInstanceName Ignite instance name.
     * @param ctx Spring context.
     * @return Started grid.
     * @throws Exception If failed.
     */
    protected IgniteEx startGrid(String igniteInstanceName, GridSpringResourceContext ctx) throws Exception {
        return clusterManager.startGrid(igniteInstanceName, optimize(getConfiguration(igniteInstanceName)), ctx);
    }

    protected IgniteEx startGrid(String igniteInstanceName, IgniteConfiguration cfg, GridSpringResourceContext ctx)
        throws Exception {
        return clusterManager.startGrid(igniteInstanceName, optimize(getConfiguration(igniteInstanceName)), ctx);
    }

    /**
     * Starts new grid with given name.
     *
     * @param igniteInstanceName Ignite instance name.
     * @param ctx Spring context.
     * @return Started grid.
     * @throws Exception If failed.
     */
    protected IgniteEx clusterManager__startGrid(
        String igniteInstanceName,
        IgniteConfiguration cfg,
        GridSpringResourceContext ctx
    ) throws Exception {
        return clusterManager.startGrid(igniteInstanceName, cfg, ctx);
    }

    /**
     * Starts new grid at another JVM with given name.
     *
     * @param igniteInstanceName Ignite instance name.
     * @param cfg Ignite configuration.
     * @return Started grid.
     * @throws Exception If failed.
     */
    protected IgniteEx startRemoteGrid(
        String igniteInstanceName,
        IgniteConfiguration cfg
    ) throws Exception {
        return clusterManager.startRemoteGrid(igniteInstanceName, cfg);
    }

    /**
     * Starts new grid at another JVM with given name.
     *
     * @param igniteInstanceName Ignite instance name.
     * @param cfg Ignite configuration.
     * @param locNode Local node.
     * @param resetDiscovery Reset DiscoverySpi.
     * @return Started grid.
     * @throws Exception If failed.
     */
    protected IgniteEx startRemoteGrid(String igniteInstanceName, IgniteConfiguration cfg,
        IgniteEx locNode, boolean resetDiscovery)
        throws Exception {

        if (cfg == null)
            cfg = optimize(getConfiguration(igniteInstanceName));

        return clusterManager.startRemoteGrid(igniteInstanceName, cfg, locNode, resetDiscovery, additionalRemoteJvmArgs());
    }

    /**
     * Starts new grid with given name.
     *
     * @param gridName Grid name.
     * @param client Client mode.
     * @param cfgUrl Config URL.
     * @return Started grid.
     * @throws Exception If failed.
     */
    protected Ignite startGridWithSpringCtx(String gridName, boolean client, String cfgUrl) throws Exception {
        return clusterManager.startGridWithSpringCtx(gridName, client, cfgUrl);
    }

    /**
     * @param cnt Grid count.
     * @return First started grid.
     * @throws Exception If failed.
     */
    protected final Ignite startGrids(int cnt) throws Exception {
        assert cnt > 0;

        Ignite ignite = null;

        for (int i = 0; i < cnt; i++)
            if (ignite == null)
                ignite = startGrid(i);
            else
                startGrid(i);

        if (checkTopology())
            checkTopology(cnt);

        assert ignite != null;

        return ignite;
    }

    /**
     * @param cnt Grid count.
     * @return First started grid.
     * @throws Exception If failed.
     */
    protected Ignite startGridsMultiThreaded(int cnt) throws Exception {
        assert cnt > 0 : "Number of grids must be a positive number";

        Ignite ignite = startGrids(1);

        if (cnt > 1) {
            startGridsMultiThreaded(1, cnt - 1);

            if (checkTopology())
                checkTopology(cnt);
        }

        return ignite;
    }

    /**
     * @param init Start grid index.
     * @param cnt Grid count.
     * @return First started grid.
     * @throws Exception If failed.
     */
    protected final Ignite startGridsMultiThreaded(int init, int cnt) throws Exception {
        assert init >= 0;
        assert cnt > 0;

        info("Starting grids: " + cnt);

        final AtomicInteger gridIdx = new AtomicInteger(init);

        GridTestUtils.runMultiThreaded(
            new Callable<Object>() {
                @Nullable @Override public Object call() throws Exception {
                    startGrid(gridIdx.getAndIncrement());

                    return null;
                }
            },
            cnt,
            "grid-starter-" + getName()
        );

        assert gridIdx.get() - init == cnt;

        return ignite(init);
    }

    /**
     * Start specified amount of nodes.
     *
     * @param cnt Nodes count.
     * @param client Client mode.
     * @param cfgUrl Config URL.
     * @return First started node.
     * @throws Exception If failed.
     */
    protected Ignite startGridsWithSpringCtx(int cnt, boolean client, String cfgUrl) throws Exception {
        assert cnt > 0;

        Ignite ignite = null;

        for (int i = 0; i < cnt; i++) {
            if (ignite == null)
                ignite = startGridWithSpringCtx(getTestIgniteInstanceName(i), client, cfgUrl);
            else
                startGridWithSpringCtx(getTestIgniteInstanceName(i), client, cfgUrl);
        }

        checkTopology(cnt);

        assert ignite != null;

        return ignite;
    }

    /** */
    protected void stopGrid() {
        stopGrid(getTestIgniteInstanceName());
    }

    /**
     * @param idx Index of the grid to stop.
     */
    protected void stopGrid(int idx) {
        stopGrid(getTestIgniteInstanceName(idx), false);
    }


    /**
     * Stop Ignite instance using index.
     *
     * @param idx Grid index.
     * @param cancel Cancel flag.
     */
    protected void stopGrid(int idx, boolean cancel) {
        stopGrid(getTestIgniteInstanceName(idx), cancel, false);
    }

    /**
     * @param igniteInstanceName Ignite instance name.
     */
    protected void stopGrid(@Nullable String igniteInstanceName) {
        stopGrid(igniteInstanceName, true);
    }

    /**
     * @param igniteInstanceName Ignite instance name.
     * @param cancel Cancel flag.
     */
    protected void stopGrid(@Nullable String igniteInstanceName, boolean cancel) {
        stopGrid(igniteInstanceName, cancel, true);
    }

    /**
     * @param igniteInstanceName Ignite instance name.
     * @param cancel Cancel flag.
     * @param awaitTop Await topology change flag.
     */
    protected void stopGrid(@Nullable String igniteInstanceName, boolean cancel, boolean awaitTop) {
       clusterManager.stopGrid(igniteInstanceName, cancel, awaitTop);
    }

    /**
     *
     */
    protected void stopAllGrids() {
        stopAllGrids(true);
    }

    /**
     * @param cancel Cancel flag.
     */
    protected void stopAllGrids(boolean cancel) {
        try {
            Collection<Ignite> clients = new ArrayList<>();
            Collection<Ignite> srvs = new ArrayList<>();

            for (Ignite g : G.allGrids()) {
                if (g.configuration().getDiscoverySpi().isClientMode())
                    clients.add(g);
                else
                    srvs.add(g);
            }

            for (Ignite g : clients)
                stopGrid(g.name(), cancel, false);

            for (Ignite g : srvs)
                stopGrid(g.name(), cancel, false);

            List<Ignite> nodes = G.allGrids();

            assert nodes.isEmpty() : nodes;
        }
        finally {
            IgniteProcessProxy.killAll(); // In multi-JVM case.
        }
    }

    /**
     * @param cancel Cancel flag.
     */
    protected void stopAllClients(boolean cancel) {
        List<Ignite> ignites = G.allGrids();

        for (Ignite g : ignites) {
            if (g.configuration().getDiscoverySpi().isClientMode())
                stopGrid(g.name(), cancel);
        }
    }

    /**
     * @param cancel Cancel flag.
     */
    protected void stopAllServers(boolean cancel) {
        List<Ignite> ignites = G.allGrids();

        for (Ignite g : ignites) {
            if (!g.configuration().getDiscoverySpi().isClientMode())
                stopGrid(g.name(), cancel);
        }
    }

    /**
     * @param idx Index of the grid to stop.
     */
    protected void stopAndCancelGrid(int idx) {
        stopGrid(getTestIgniteInstanceName(idx), true);
    }

    /**
     * @return {@code True} if nodes use {@link TcpDiscoverySpi}.
     */
    protected static boolean tcpDiscovery() {
        List<Ignite> nodes = G.allGrids();

        assertFalse("There are no nodes", nodes.isEmpty());

        return nodes.get(0).configuration().getDiscoverySpi() instanceof TcpDiscoverySpi;
    }

    /**
     *
     */
    private static interface WriteReplaceOwner {
        /**
         *
         */
        Object writeReplace();
    }

    /**
     *
     */
    private static class SerializableProxy implements Serializable {
        /** */
        private final UUID uuid;

        /**
         * @param uuid Uuid.
         */
        private SerializableProxy(UUID uuid) {
            this.uuid = uuid;
        }

        /**
         *
         */
        protected Object readResolve() throws ObjectStreamException {
            Object res = serializedObj.get(uuid);

            assert res != null;

            return res;
        }
    }

    /**
     *
     */
    private static class ExecuteRemotelyTask<R> implements IgniteCallable<R> {
        /** Ignite. */
        @IgniteInstanceResource
        protected Ignite ignite;

        /** Job. */
        private final TestIgniteIdxCallable<R> job;

        /** Index. */
        private final int idx;

        /**
         * @param job Job.
         * @param idx Index.
         */
        public ExecuteRemotelyTask(TestIgniteIdxCallable<R> job, int idx) {
            this.job = job;
            this.idx = idx;
        }

        /** {@inheritDoc} */
        @Override public R call() throws Exception {
            job.setIgnite(ignite);

            return job.call(idx);
        }
    }

    /** */
    public abstract static class TestIgniteIdxCallable<R> implements Serializable {
        /** */
        @IgniteInstanceResource
        protected Ignite ignite;

        /**
         * @param ignite Ignite.
         */
        public void setIgnite(Ignite ignite) {
            this.ignite = ignite;
        }

        /**
         * @param idx Grid index.
         */
        protected abstract R call(int idx) throws Exception;
    }

    /** */
    public abstract static class TestIgniteIdxRunnable extends TestIgniteIdxCallable<Void> {
        /** {@inheritDoc} */
        @Override public Void call(int idx) throws Exception {
            run(idx);

            return null;
        }

        /**
         * @param idx Index.
         */
        public abstract void run(int idx) throws Exception;
    }

    /** */
    public static interface TestCacheCallable<K, V, R> extends Serializable {
        /**
         * @param ignite Ignite.
         * @param cache Cache.
         */
        R call(Ignite ignite, IgniteCache<K, V> cache) throws Exception;
    }

    /** */
    public abstract static class TestCacheRunnable<K, V> implements TestCacheCallable<K, V, Object> {
        /** {@inheritDoc} */
        @Override public Object call(Ignite ignite, IgniteCache cache) throws Exception {
            run(ignite, cache);

            return null;
        }

        /**
         * @param ignite Ignite.
         * @param cache Cache.
         */
        public abstract void run(Ignite ignite, IgniteCache<K, V> cache) throws Exception;
    }

    /**
     *  Calls {@link #beforeFirstTest()} and {@link #afterLastTest()} methods
     *  in order to support {@link #beforeTestsStarted()} and {@link #afterTestsStopped()}.
     *  <p>
     *  Processes {@link WithSystemProperty} annotations as well.
     */
    private static class BeforeFirstAndAfterLastTestRule implements TestRule {
        /** {@inheritDoc} */
        @Override public Statement apply(Statement base, Description desc) {
            return new Statement() {
                @Override public void evaluate() throws Throwable {
                    GridAbstractTest fixtureInstance = (GridAbstractTest)desc.getTestClass().newInstance();

                    fixtureInstance.evaluateInsideFixture(base);
                }
            };
        }
    }

    /**
     * Executes a statement inside a fixture calling GridAbstractTest specific methods which
     * should be executed before and after a test class execution.
     *
     * @param stmt Statement to execute.
     * @throws Throwable In case of failure.
     */
    private void evaluateInsideFixture(Statement stmt) throws Throwable {
        try {
            setSystemPropertiesBeforeClass();

            try {
                beforeFirstTest();

                stmt.evaluate();
            }
            finally {
                afterLastTest();
            }
        }
        finally {
            clearSystemPropertiesAfterClass();
        }
    }
}
