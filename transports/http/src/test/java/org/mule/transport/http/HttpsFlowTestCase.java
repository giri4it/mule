/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.transport.http;

import static org.junit.Assert.assertEquals;

import org.mule.tck.junit4.FunctionalTestCase;
import org.mule.tck.junit4.rule.DynamicPort;

import javax.net.ssl.HostnameVerifier;

import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.junit.Rule;
import org.junit.Test;

public class HttpsFlowTestCase extends FunctionalTestCase
{
    @Rule
    public DynamicPort dynamicPort = new DynamicPort("port1");

    @Override
    protected String getConfigFile()
    {
        return "https-flow-config.xml";
    }

    @Test
    public void testSecureFlow() throws Exception
    {
        String url = String.format("https://localhost:%1d/?message=Hello", dynamicPort.getNumber());

        HttpGet method = new HttpGet(url);
        HttpClient client = HttpClients.custom()
            .setHostnameVerifier(SSLConnectionSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER)
            .build();

        org.apache.http.HttpResponse response = client.execute(method);

        assertEquals(HttpConstants.SC_OK, response.getStatusLine().getStatusCode());

        String result = EntityUtils.toString(response.getEntity());
        assertEquals("/?message=Hello received", result);
    }
}


