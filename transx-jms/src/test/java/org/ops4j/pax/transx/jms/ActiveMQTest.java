/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.ops4j.pax.transx.jms;

import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.ActiveMQXAConnectionFactory;
import org.apache.aries.transaction.internal.AriesPlatformTransactionManager;
import org.apache.geronimo.transaction.manager.GeronimoTransactionManager;
import org.junit.Before;
import org.junit.Test;
import org.ops4j.pax.transx.connector.ConnectionManagerFactory;
import org.springframework.jms.connection.JmsTransactionManager;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.JMSConsumer;
import javax.jms.JMSContext;
import javax.jms.JMSException;
import javax.jms.Queue;
import javax.jms.Session;
import javax.jms.XAConnectionFactory;
import javax.resource.ResourceException;
import javax.resource.spi.ConnectionManager;
import javax.resource.spi.ManagedConnectionFactory;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;
import static org.ops4j.pax.transx.connector.ConnectionManagerFactory.partitionedPool;
import static org.ops4j.pax.transx.connector.ConnectionManagerFactory.xaTransactions;

public class ActiveMQTest {

    public static final String BROKER_URL = "vm://broker?marshal=false&broker.persistent=false";
    public static final String QUEUE = "myqueue";


    GeronimoTransactionManager tm;

    @Before
    public void setUp() throws Exception {
        tm = new AriesPlatformTransactionManager();
    }

    @Test
    public void testContextWithXaTx() throws Exception {
        ConnectionFactory cf = createCF(BROKER_URL);

        assertEquals(0, consumeMessages(cf, QUEUE).size());

        tm.begin();
        try (JMSContext context = cf.createContext()) {
            Queue queue = context.createQueue(QUEUE);
            context.createProducer().send(queue, "Hello");
        }
        tm.rollback();
        assertEquals(0, consumeMessages(cf, QUEUE).size());

        tm.begin();
        try (JMSContext context = cf.createContext()) {
            Queue queue = context.createQueue(QUEUE);
            context.createProducer().send(queue, "Hello");
        }
        tm.commit();
        assertEquals(1, consumeMessages(cf, QUEUE).size());

        try (JMSContext context = cf.createContext()) {
            Queue queue = context.createQueue(QUEUE);
            context.createProducer().send(queue, "Hello");
        }
        assertEquals(1, consumeMessages(cf, QUEUE).size());
    }

    @Test
    public void testSessionWithXaTx() throws Exception {
        ConnectionFactory cf = createCF(BROKER_URL);

        assertEquals(0, consumeMessages(cf, QUEUE).size());

        tm.begin();
        try (Connection con = cf.createConnection()) {
            try (Session s = con.createSession()) {
                Queue queue = s.createQueue(QUEUE);
                s.createProducer(queue).send(s.createTextMessage("Hello"));
            }
        }
        tm.rollback();
        assertEquals(0, consumeMessages(cf, QUEUE).size());

        tm.begin();
        try (Connection con = cf.createConnection()) {
            try (Session s = con.createSession()) {
                Queue queue = s.createQueue(QUEUE);
                s.createProducer(queue).send(s.createTextMessage("Hello"));
            }
        }
        tm.commit();
        assertEquals(1, consumeMessages(cf, QUEUE).size());

        try (Connection con = cf.createConnection()) {
            try (Session s = con.createSession()) {
                Queue queue = s.createQueue(QUEUE);
                s.createProducer(queue).send(s.createTextMessage("Hello"));
                try {
                    s.rollback();
                    fail("Should have thrown an exception");
                } catch (JMSException e) {
                    // expected
                }
            }
        }
        assertEquals(1, consumeMessages(cf, QUEUE).size());

        try (Connection con = cf.createConnection()) {
            try (Session s = con.createSession(true, Session.SESSION_TRANSACTED)) {
                Queue queue = s.createQueue(QUEUE);
                s.createProducer(queue).send(s.createTextMessage("Hello"));
                s.rollback();
            }
        }
        assertEquals(0, consumeMessages(cf, QUEUE).size());

        try (Connection con = cf.createConnection()) {
            try (Session s = con.createSession(false, Session.SESSION_TRANSACTED)) {
                Queue queue = s.createQueue(QUEUE);
                s.createProducer(queue).send(s.createTextMessage("Hello"));
                try {
                    s.rollback();
                    fail("Should have thrown an exception");
                } catch (JMSException e) {
                    // expected
                }
            }
        }
        assertEquals(1, consumeMessages(cf, QUEUE).size());
    }

    @Test
    public void testSpring() throws ResourceException {
        ConnectionFactory cf = createCF(BROKER_URL);
        JmsTemplate jms = new JmsTemplate(cf);
        jms.setDefaultDestinationName(QUEUE);
        jms.setReceiveTimeout(100);

        jms.convertAndSend("Hello");
        Object msg = jms.receiveAndConvert();
        assertEquals("Hello", msg);
    }

    @Test
    public void testSpringXa() throws ResourceException {
        ConnectionFactory cf = createCF(BROKER_URL);
        JmsTemplate jms = new JmsTemplate(cf);
        jms.setDefaultDestinationName(QUEUE);
        jms.setReceiveTimeout(100);
        TransactionTemplate xaTx = new TransactionTemplate((PlatformTransactionManager) tm);

        xaTx.execute(ts -> {
            jms.convertAndSend("Hello");
            return null;
        });
        Object msg = xaTx.execute(ts -> jms.receiveAndConvert());
        assertEquals("Hello", msg);

        xaTx = new TransactionTemplate((PlatformTransactionManager) tm);
        xaTx.execute(ts -> {
            jms.convertAndSend("Hello");
            ts.setRollbackOnly();
            return null;
        });
        msg = xaTx.execute(ts -> jms.receiveAndConvert());
        assertNull(msg);
    }

    @Test
    public void testSpringLocalTx() throws ResourceException {
        ConnectionFactory cf = createCF(BROKER_URL);
        JmsTemplate jms = new JmsTemplate(cf);
        jms.setDefaultDestinationName(QUEUE);
        jms.setReceiveTimeout(100);
        PlatformTransactionManager tm = new JmsTransactionManager(cf);
        TransactionTemplate localTx = new TransactionTemplate(tm);

        localTx.execute(ts -> {
            jms.convertAndSend("Hello");
            return null;
        });
        Object msg = localTx.execute(ts -> jms.receiveAndConvert());
        assertEquals("Hello", msg);

        localTx.execute(ts -> {
            jms.convertAndSend("Hello");
            ts.setRollbackOnly();
            return null;
        });
        msg = localTx.execute(ts -> jms.receiveAndConvert());
        assertNull(msg);
    }

    private List<String> consumeMessages(ConnectionFactory cf, String queue) {
        try (JMSContext ctx = cf.createContext()) {
            return consumeMessages(ctx, ctx.createQueue(queue));
        }
    }

    private List<String> consumeMessages(JMSContext context, Queue queue) {
        List<String> messages = new ArrayList<>();
        try (JMSConsumer consumer = context.createConsumer(queue)) {
            while (true) {
                String msg = consumer.receiveBody(String.class, 100);
                if (msg != null) {
                    messages.add(msg);
                } else {
                    return messages;
                }
            }
        }
    }

    private ConnectionFactory createCF(String brokerUrl) throws ResourceException {
        ManagedConnectionFactory mcf = ManagedConnectionFactoryFactory.create(
                new ActiveMQConnectionFactory(brokerUrl),
                new ActiveMQXAConnectionFactory(brokerUrl));
        ConnectionManager cm = ConnectionManagerFactory.create(
                xaTransactions(true, false),
                partitionedPool(8, 1, 5000, 15, true, false, false, true, false),
                null,
                tm,
                tm,
                mcf,
                "vmbroker",
                null
        );
        return (ConnectionFactory) mcf.createConnectionFactory(cm);
    }
}
