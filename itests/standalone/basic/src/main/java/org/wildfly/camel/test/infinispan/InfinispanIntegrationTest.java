/*
 * #%L
 * Wildfly Camel :: Testsuite
 * %%
 * Copyright (C) 2013 - 2015 RedHat
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

package org.wildfly.camel.test.infinispan;

import java.util.HashMap;
import java.util.Map;

import javax.annotation.Resource;
import javax.ejb.Stateless;

import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.infinispan.InfinispanConstants;
import org.apache.camel.component.mock.MockEndpoint;
import org.apache.camel.impl.DefaultCamelContext;
import org.infinispan.manager.CacheContainer;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.as.arquillian.api.ServerSetup;
import org.jboss.as.arquillian.api.ServerSetupTask;
import org.jboss.as.arquillian.container.ManagementClient;
import org.jboss.as.controller.descriptions.ModelDescriptionConstants;
import org.jboss.dmr.ModelNode;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.EmptyAsset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.wildfly.camel.test.common.DMRUtils;
import org.wildfly.extension.camel.CamelAware;

@Stateless
@CamelAware
@ServerSetup({InfinispanIntegrationTest.InfinispanCacheSetupTask.class})
@RunWith(Arquillian.class)
public class InfinispanIntegrationTest {

    private static final String CACHE_KEY_NAME = "name";
    private static final String CACHE_KEY_AGE = "age";
    private static final String CACHE_VALUE_KERMIT = "Kermit";
    private static final String CACHE_VALUE_BOB = "Bob";
    private static final int CACHE_VALUE_AGE = 65;

    @Resource(lookup = "java:jboss/infinispan/container/test")
    private CacheContainer cacheContainer;

    static class InfinispanCacheSetupTask implements ServerSetupTask {

        @Override
        public void setup(final ManagementClient managementClient, String containerId) throws Exception {
            ModelNode cacheOpAdd = DMRUtils.createOpNode("subsystem=infinispan/cache-container=test", ModelDescriptionConstants.ADD);
            cacheOpAdd.get("default-cache").set("test");
            cacheOpAdd.get("jndi-name").set("java:jboss/infinispan/container/test");
            managementClient.getControllerClient().execute(DMRUtils.createCompositeNode(new ModelNode[]{cacheOpAdd}));
        }

        @Override
        public void tearDown(final ManagementClient managementClient, String containerId) throws Exception {
            ModelNode cacheOpRemove = DMRUtils.createOpNode("subsystem=infinispan/cache-container=test", ModelDescriptionConstants.REMOVE);
            managementClient.getControllerClient().execute(DMRUtils.createCompositeNode(new ModelNode[]{cacheOpRemove}));
        }
    }

    @Deployment
    public static JavaArchive deployment() {
        JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "camel-infinispan-test.jar");
        archive.addAsManifestResource(EmptyAsset.INSTANCE, "beans.xml");
        return archive;
    }

    @After
    public void tearDown() {
        cacheContainer.getCache().clear();
    }

    @Test
    public void testCacheGet() throws Exception {
        DefaultCamelContext camelctx = new DefaultCamelContext();
        camelctx.addRoutes(new RouteBuilder() {
            @Override
            public void configure() throws Exception {
                from("direct:get")
                .to("infinispan://localhost?cacheContainer=#java:jboss/infinispan/container/test&command=GET")
                .transform(simple("${headers.CamelInfinispanOperationResult}"))
                .to("mock:end");
            }
        });

        cacheContainer.getCache().put(CACHE_KEY_NAME, CACHE_VALUE_KERMIT);

        camelctx.start();
        try {
            Map<String, Object> headers = new HashMap<>();
            headers.put(InfinispanConstants.KEY, CACHE_KEY_NAME);

            ProducerTemplate template = camelctx.createProducerTemplate();
            String name = template.requestBodyAndHeaders("direct:get", null, headers, String.class);

            Assert.assertEquals("Kermit", name);
        } finally {
            camelctx.stop();
        }
    }

    @Test
    public void testCachePut() throws Exception {
        DefaultCamelContext camelctx = new DefaultCamelContext();
        camelctx.addRoutes(new RouteBuilder() {
            @Override
            public void configure() throws Exception {
                from("direct:put")
                .to("infinispan://localhost?cacheContainer=#java:jboss/infinispan/container/test&command=PUT");
            }
        });

        camelctx.start();
        try {
            Map<String, Object> headers = new HashMap<>();
            headers.put(InfinispanConstants.KEY, CACHE_KEY_NAME);
            headers.put(InfinispanConstants.VALUE, CACHE_VALUE_KERMIT);

            ProducerTemplate template = camelctx.createProducerTemplate();
            template.requestBodyAndHeaders("direct:put", null, headers);

            String name = (String) cacheContainer.getCache().get(CACHE_KEY_NAME);
            Assert.assertEquals("Kermit", name);
        } finally {
            camelctx.stop();
        }
    }

    @Test
    public void testCacheRemove() throws Exception {
        DefaultCamelContext camelctx = new DefaultCamelContext();
        camelctx.addRoutes(new RouteBuilder() {
            @Override
            public void configure() throws Exception {
                from("direct:remove")
                .to("infinispan://localhost?cacheContainer=#java:jboss/infinispan/container/test&command=REMOVE");
            }
        });

        cacheContainer.getCache().put(CACHE_KEY_NAME, CACHE_VALUE_KERMIT);

        camelctx.start();
        try {
            Assert.assertTrue(cacheContainer.getCache().containsKey(CACHE_KEY_NAME));

            Map<String, Object> headers = new HashMap<>();
            headers.put(InfinispanConstants.KEY, CACHE_KEY_NAME);

            ProducerTemplate template = camelctx.createProducerTemplate();
            template.requestBodyAndHeaders("direct:remove", null, headers);

            Assert.assertFalse(cacheContainer.getCache().containsKey(CACHE_KEY_NAME));
        } finally {
            camelctx.stop();
        }
    }

    @Test
    public void testCacheEntryCreatedEvent() throws Exception {
        DefaultCamelContext camelctx = new DefaultCamelContext();
        camelctx.addRoutes(new RouteBuilder() {
            @Override
            public void configure() throws Exception {
                from("infinispan://localhost?cacheContainer=#java:jboss/infinispan/container/test&sync=false&eventTypes=CACHE_ENTRY_CREATED")
                .to("mock:result");
            }
        });

        camelctx.start();
        try {
            cacheContainer.getCache().put(CACHE_KEY_NAME, CACHE_VALUE_KERMIT);
            cacheContainer.getCache().put(CACHE_KEY_AGE, CACHE_VALUE_AGE);

            MockEndpoint mockEndpoint = camelctx.getEndpoint("mock:result", MockEndpoint.class);
            mockEndpoint.expectedMessageCount(4);

            mockEndpoint.message(0).outHeader(InfinispanConstants.EVENT_TYPE).isEqualTo("CACHE_ENTRY_CREATED");
            mockEndpoint.message(0).outHeader(InfinispanConstants.IS_PRE).isEqualTo(true);
            mockEndpoint.message(0).outHeader(InfinispanConstants.KEY).isEqualTo(CACHE_KEY_NAME);

            mockEndpoint.message(1).outHeader(InfinispanConstants.EVENT_TYPE).isEqualTo("CACHE_ENTRY_CREATED");
            mockEndpoint.message(1).outHeader(InfinispanConstants.IS_PRE).isEqualTo(false);
            mockEndpoint.message(1).outHeader(InfinispanConstants.KEY).isEqualTo(CACHE_KEY_NAME);

            mockEndpoint.message(2).outHeader(InfinispanConstants.EVENT_TYPE).isEqualTo("CACHE_ENTRY_CREATED");
            mockEndpoint.message(2).outHeader(InfinispanConstants.IS_PRE).isEqualTo(true);
            mockEndpoint.message(2).outHeader(InfinispanConstants.KEY).isEqualTo(CACHE_KEY_AGE);

            mockEndpoint.message(3).outHeader(InfinispanConstants.EVENT_TYPE).isEqualTo("CACHE_ENTRY_CREATED");
            mockEndpoint.message(3).outHeader(InfinispanConstants.IS_PRE).isEqualTo(false);
            mockEndpoint.message(3).outHeader(InfinispanConstants.KEY).isEqualTo(CACHE_KEY_AGE);

            mockEndpoint.assertIsSatisfied();
        } finally {
            camelctx.stop();
        }
    }

    @Test
    public void testCacheEntryRemovedEvent() throws Exception {
        DefaultCamelContext camelctx = new DefaultCamelContext();
        camelctx.addRoutes(new RouteBuilder() {
            @Override
            public void configure() throws Exception {
                from("infinispan://localhost?cacheContainer=#java:jboss/infinispan/container/test&sync=false&eventTypes=CACHE_ENTRY_REMOVED")
                .to("mock:result");
            }
        });

        cacheContainer.getCache().put(CACHE_KEY_NAME, CACHE_VALUE_KERMIT);
        cacheContainer.getCache().put(CACHE_KEY_AGE, CACHE_VALUE_AGE);

        camelctx.start();
        try {
            MockEndpoint mockEndpoint = camelctx.getEndpoint("mock:result", MockEndpoint.class);
            mockEndpoint.expectedMessageCount(2);

            mockEndpoint.message(0).outHeader(InfinispanConstants.EVENT_TYPE).isEqualTo("CACHE_ENTRY_REMOVED");
            mockEndpoint.message(0).outHeader(InfinispanConstants.IS_PRE).isEqualTo(true);
            mockEndpoint.message(0).outHeader(InfinispanConstants.CACHE_NAME).isNotNull();
            mockEndpoint.message(0).outHeader(InfinispanConstants.KEY).isEqualTo(CACHE_KEY_NAME);

            mockEndpoint.message(1).outHeader(InfinispanConstants.EVENT_TYPE).isEqualTo("CACHE_ENTRY_REMOVED");
            mockEndpoint.message(1).outHeader(InfinispanConstants.IS_PRE).isEqualTo(false);
            mockEndpoint.message(1).outHeader(InfinispanConstants.CACHE_NAME).isNotNull();
            mockEndpoint.message(1).outHeader(InfinispanConstants.KEY).isEqualTo(CACHE_KEY_NAME);

            cacheContainer.getCache().remove("name");

            mockEndpoint.assertIsSatisfied();
        } finally {
            camelctx.stop();
        }
    }

    @Test
    public void testCacheEntryModifiedEvent() throws Exception {
        DefaultCamelContext camelctx = new DefaultCamelContext();
        camelctx.addRoutes(new RouteBuilder() {
            @Override
            public void configure() throws Exception {
                from("infinispan://localhost?cacheContainer=#java:jboss/infinispan/container/test&sync=false&eventTypes=CACHE_ENTRY_MODIFIED")
                .to("mock:result");
            }
        });

        cacheContainer.getCache().put(CACHE_KEY_NAME, CACHE_VALUE_KERMIT);

        camelctx.start();
        try {
            MockEndpoint mockEndpoint = camelctx.getEndpoint("mock:result", MockEndpoint.class);
            mockEndpoint.expectedMessageCount(2);

            mockEndpoint.message(0).outHeader(InfinispanConstants.EVENT_TYPE).isEqualTo("CACHE_ENTRY_MODIFIED");
            mockEndpoint.message(0).outHeader(InfinispanConstants.IS_PRE).isEqualTo(true);
            mockEndpoint.message(0).outHeader(InfinispanConstants.CACHE_NAME).isNotNull();
            mockEndpoint.message(0).outHeader(InfinispanConstants.KEY).isEqualTo(CACHE_KEY_NAME);

            mockEndpoint.message(1).outHeader(InfinispanConstants.EVENT_TYPE).isEqualTo("CACHE_ENTRY_MODIFIED");
            mockEndpoint.message(1).outHeader(InfinispanConstants.IS_PRE).isEqualTo(false);
            mockEndpoint.message(1).outHeader(InfinispanConstants.CACHE_NAME).isNotNull();
            mockEndpoint.message(1).outHeader(InfinispanConstants.KEY).isEqualTo(CACHE_KEY_NAME);

            cacheContainer.getCache().replace(CACHE_KEY_NAME, CACHE_VALUE_BOB);

            mockEndpoint.assertIsSatisfied();
        } finally {
            camelctx.stop();
        }
    }
}
