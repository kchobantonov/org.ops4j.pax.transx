/*
 * Copyright 2021 OPS4J.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.ops4j.pax.transx.itests;

import java.sql.Connection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.sql.DataSource;
import javax.sql.XAConnection;
import javax.sql.XADataSource;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.ops4j.pax.exam.Configuration;
import org.ops4j.pax.exam.Option;
import org.ops4j.pax.exam.junit.PaxExam;
import org.ops4j.pax.transx.jdbc.ManagedDataSourceBuilder;
import org.ops4j.pax.transx.tm.TransactionManager;

import static javax.transaction.xa.XAResource.TMENDRSCAN;
import static javax.transaction.xa.XAResource.TMSTARTRSCAN;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.ops4j.pax.exam.CoreOptions.mavenBundle;
import static org.ops4j.pax.exam.OptionUtils.combine;

@RunWith(PaxExam.class)
public class GeronimoRecoveryTest extends AbstractControlledTestBase {

    @Inject
    private TransactionManager tm;

    @Configuration
    public Option[] config() throws Exception {
        return combine(baseConfigure(),
                mavenBundle("javax.transaction", "javax.transaction-api").versionAsInProject(),
                mavenBundle("javax.interceptor", "javax.interceptor-api").versionAsInProject(),
                mavenBundle("jakarta.el", "jakarta.el-api").versionAsInProject(),
                mavenBundle("javax.enterprise", "cdi-api").versionAsInProject(),
                jcaApiBundle(),
                mavenBundle("javax.jms", "javax.jms-api").versionAsInProject(),
                mavenBundle("org.ops4j.pax.transx", "pax-transx-tm-api").versionAsInProject(),
                mavenBundle("org.ops4j.pax.transx", "pax-transx-tm-geronimo").versionAsInProject(),
                mavenBundle("org.ops4j.pax.transx", "pax-transx-connector").versionAsInProject(),
                mavenBundle("org.ops4j.pax.transx", "pax-transx-jms").versionAsInProject(),
                mavenBundle("org.ops4j.pax.transx", "pax-transx-jdbc").versionAsInProject(),
                mavenBundle("org.osgi", "org.osgi.service.jdbc").versionAsInProject()
        );
    }

    @Test
    public void testRecovery() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);

        XADataSource xaDs = Mockito.mock(XADataSource.class);
        XAConnection xaCon = Mockito.mock(XAConnection.class);
        XAResource xaRes = Mockito.mock(XAResource.class);
        Connection con = Mockito.mock(Connection.class);

        Mockito.when(xaDs.getXAConnection()).thenReturn(xaCon);
        Mockito.when(xaCon.getXAResource()).thenReturn(xaRes);
        Mockito.when(xaCon.getConnection()).thenReturn(con);

        Mockito.when(xaRes.recover(TMSTARTRSCAN + TMENDRSCAN)).thenAnswer(invocation -> {
            latch.countDown();
            return new Xid[0];
        });
        Mockito.when(con.getAutoCommit()).thenReturn(true);
        Mockito.when(con.isValid(anyInt())).thenReturn(true);

        DataSource ds = ManagedDataSourceBuilder.builder()
                .dataSource(xaDs)
                .transactionManager(tm)
                .name("h2")
                .build();
        assertNotNull(ds);
        assertTrue(latch.await(5, TimeUnit.SECONDS));

        ((AutoCloseable) ds).close();
    }

}
