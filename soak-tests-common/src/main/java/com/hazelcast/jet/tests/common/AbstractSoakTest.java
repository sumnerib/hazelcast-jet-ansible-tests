/*
 * Copyright (c) 2008-2018, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.jet.tests.common;

import com.hazelcast.client.HazelcastClient;
import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.client.config.YamlClientConfigBuilder;
import com.hazelcast.config.CacheSimpleConfig;
import com.hazelcast.config.Config;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.jet.config.JetConfig;
import com.hazelcast.logging.ILogger;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.hazelcast.jet.tests.common.Util.parseArguments;
import static com.hazelcast.jet.tests.common.Util.sleepSeconds;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.MINUTES;

public abstract class AbstractSoakTest {

    public static final String STABLE_CLUSTER = "Stable";
    public static final String DYNAMIC_CLUSTER = "Dynamic";

    private static final int DEFAULT_DURATION_MINUTES = 30;
    private static final int CACHE_EVICTION_SIZE = 2000000;
    private static final double WAIT_TIMEOUT_FACTOR = 1.1;
    private static final int DELAY_BETWEEN_INIT_AND_TEST_SECONDS = 15;

    protected transient ClientConfig stableClusterClientConfig;
    protected transient HazelcastInstance stableClusterClient;
    protected transient ILogger logger;
    protected long durationInMillis;

    private transient HazelcastInstance hz;

    protected final void run(String[] args) throws Exception {
        parseArguments(args);

        HazelcastInstance[] instances = null;
        if (isRunLocal()) {
            Config config = new Config();
            CacheSimpleConfig cacheConfig = new CacheSimpleConfig()
                    .setName("CooperativeMapCacheSourceTest_SourceCache");
            cacheConfig.getEvictionConfig().setSize(CACHE_EVICTION_SIZE);
            config.addCacheConfig(cacheConfig);
            config.setJetConfig(new JetConfig().setEnabled(true));

            instances = new HazelcastInstance[]{
                Hazelcast.newHazelcastInstance(config), Hazelcast.newHazelcastInstance(config)};
            hz = HazelcastClient.newHazelcastClient();
        } else {
            hz = Hazelcast.bootstrappedInstance();
        }
        logger = getLogger(getClass());

        logger.info("Initializing...");
        try {
            durationInMillis = durationInMillis();
            init(hz);
            sleepSeconds(DELAY_BETWEEN_INIT_AND_TEST_SECONDS);
        } catch (Throwable t) {
            t.printStackTrace();
            logger.severe(t);
            teardown(t);
            logger.info("Finished with failure at init");
            System.exit(1);
        }
        logger.info("Running...");
        try {
            testInternal();
        } catch (Throwable t) {
            t.printStackTrace();
            logger.severe(t);
            teardown(t);
            logger.info("Finished with failure at test");
            System.exit(1);
        }
        logger.info("Teardown...");
        teardown(null);
        if (hz != null) {
            hz.shutdown();
        }
        if (stableClusterClient != null) {
            stableClusterClient.shutdown();
        }
        if (instances != null) {
            Hazelcast.shutdownAll();
        }
        logger.info("Finished OK");
        System.exit(0);
    }

    protected abstract void init(HazelcastInstance client) throws Exception;

    protected abstract void test(HazelcastInstance client, String name) throws Throwable;

    protected abstract void teardown(Throwable t) throws Exception;

    /**
     * If {@code true} then {@link #test(HazelcastInstance, String)} method will be
     * called with the dynamic cluster client (which should be the bootstrapped
     * instance) and stable cluster client (which needs a `remoteClusterYaml`
     * defined).
     */
    protected boolean runOnBothClusters() {
        return false;
    }

    protected boolean runOnlyAsClient() {
        return false;
    }

    protected String property(String name, String defaultValue) {
        return System.getProperty(name, defaultValue);
    }

    protected long durationInMillis() {
        return MINUTES.toMillis(propertyInt("durationInMinutes", DEFAULT_DURATION_MINUTES));
    }

    protected ClientConfig remoteClusterClientConfig() throws IOException {
        if (isRunLocal()) {
            return new ClientConfig();
        }
        String remoteClusterYaml = property("remoteClusterYaml", null);
        if (remoteClusterYaml == null) {
            throw new IllegalArgumentException("Remote cluster yaml should be set, use -DremoteClusterYaml to specify it");
        }

        return new YamlClientConfigBuilder(remoteClusterYaml).build();
    }

    private void testInternal() throws Throwable {
        if (!runOnBothClusters() && !runOnlyAsClient()) {
            test(hz, getClass().getSimpleName());
            return;
        }

        stableClusterClientConfig = remoteClusterClientConfig();
        stableClusterClient = HazelcastClient.newHazelcastClient(stableClusterClientConfig);

        if (runOnlyAsClient()) {
            test(stableClusterClient, getClass().getSimpleName());
            return;
        }

        Throwable[] exceptions = new Throwable[2];
        String dynamicName = DYNAMIC_CLUSTER + "-" + getClass().getSimpleName();
        String stableName = STABLE_CLUSTER + "-" + getClass().getSimpleName();
        ExecutorService executorService = Executors.newFixedThreadPool(2);
        executorService.execute(() -> {
            try {
                test(hz, dynamicName);
            } catch (Throwable t) {
                logger.severe("Exception in " + dynamicName, t);
                exceptions[0] = t;
            }
        });
        executorService.execute(() -> {
            try {
                test(stableClusterClient, stableName);
            } catch (Throwable t) {
                logger.severe("Exception in " + stableName, t);
                exceptions[1] = t;
            }
        });
        executorService.shutdown();
        executorService.awaitTermination((long) (durationInMillis * WAIT_TIMEOUT_FACTOR), MILLISECONDS);

        if (exceptions[0] != null) {
            logger.severe("Exception in " + dynamicName, exceptions[0]);
        }
        if (exceptions[1] != null) {
            logger.severe("Exception in " + stableName, exceptions[1]);
        }
        if (exceptions[0] != null) {
            throw exceptions[0];
        }
        if (exceptions[1] != null) {
            throw exceptions[1];
        }
    }

    protected int propertyInt(String name, int defaultValue) {
        String value = System.getProperty(name);
        if (value != null) {
            return Integer.parseInt(value);
        }
        return defaultValue;
    }

    protected boolean propertyBoolean(String name, boolean defaultValue) {
        String value = System.getProperty(name);
        if (value != null) {
            return Boolean.parseBoolean(value);
        }
        return defaultValue;
    }

    protected ILogger getLogger(Class clazz) {
        return hz.getLoggingService().getLogger(clazz);
    }

    private static boolean isRunLocal() {
        return System.getProperty("runLocal") != null;
    }

    protected static void setRunLocal() {
        System.setProperty("runLocal", "true");
    }

    protected static ILogger getLogger(HazelcastInstance instance, Class clazz) {
        return instance.getLoggingService().getLogger(clazz);
    }

    protected static void assertEquals(int expected, int actual) {
        assertEquals("expected: " + expected + ", actual: " + actual, expected, actual);
    }

    protected static void assertEquals(String message, int expected, int actual) {
        if (expected != actual) {
            throw new AssertionError(message);
        }
    }

    protected static void assertEquals(long expected, long actual) {
        assertEquals("expected: " + expected + ", actual: " + actual, expected, actual);
    }

    protected static void assertEquals(String message, long expected, long actual) {
        if (expected != actual) {
            throw new AssertionError(message);
        }
    }

    protected static void assertEquals(Object expected, Object actual) {
        assertEquals("expected: " + expected + ", actual: " + actual, expected, actual);
    }

    protected static void assertEquals(String message, Object expected, Object actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message);
        }
    }

    protected static void assertNotEquals(Object expected, Object actual) {
        assertNotEquals("not expected: " + expected + ", actual: " + actual, expected, actual);
    }

    protected static void assertNotEquals(String message, Object expected, Object actual) {
        if (expected.equals(actual)) {
            throw new AssertionError(message);
        }
    }

    protected static void assertTrue(boolean actual) {
        assertTrue("expected: true, actual: " + actual, actual);
    }

    protected static void assertTrue(String message, boolean actual) {
        if (!actual) {
            throw new AssertionError(message);
        }
    }

    protected static void assertFalse(boolean actual) {
        assertFalse("expected: false, actual: " + actual, actual);
    }

    protected static void assertFalse(String message, boolean actual) {
        if (actual) {
            throw new AssertionError(message);
        }
    }

    protected static void assertNotNull(Object actual) {
        assertNotNull("expected: not null, actual: null", actual);
    }

    protected static void assertNotNull(String message, Object actual) {
        if (actual == null) {
            throw new AssertionError(message);
        }
    }

    protected static void assertNull(Object actual) {
        assertNull(String.format("expected: null, actual: %s", actual), actual);
    }

    protected static void assertNull(String message, Object actual) {
        if (actual != null) {
            throw new AssertionError(message);
        }
    }
}
