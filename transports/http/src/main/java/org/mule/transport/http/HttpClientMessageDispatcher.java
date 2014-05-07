/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.transport.http;

import org.mule.VoidMuleEvent;
import org.mule.api.ExceptionPayload;
import org.mule.api.MuleEvent;
import org.mule.api.MuleException;
import org.mule.api.MuleMessage;
import org.mule.api.config.MuleProperties;
import org.mule.api.endpoint.OutboundEndpoint;
import org.mule.api.lifecycle.CreateException;
import org.mule.api.lifecycle.InitialisationException;
import org.mule.api.transformer.Transformer;
import org.mule.api.transformer.TransformerException;
import org.mule.api.transport.DispatchException;
import org.mule.config.i18n.Message;
import org.mule.config.i18n.MessageFactory;
import org.mule.endpoint.EndpointURIEndpointBuilder;
import org.mule.message.DefaultExceptionPayload;
import org.mule.transformer.TransformerChain;
import org.mule.transport.AbstractMessageDispatcher;
import org.mule.transport.http.transformers.ObjectToHttpClientMethodRequest;
import org.mule.transport.tcp.TcpConnector;
import org.mule.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.util.List;

import org.apache.commons.lang.BooleanUtils;
import org.apache.http.Header;
import org.apache.http.HttpHost;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.message.BasicHeader;
import org.apache.http.util.EntityUtils;

/**
 * <code>HttpClientMessageDispatcher</code> dispatches Mule events over HTTP.
 */
public class HttpClientMessageDispatcher extends AbstractMessageDispatcher
{
    /**
     * Range start for http error status codes.
     */
    public static final int ERROR_STATUS_CODE_RANGE_START = 400;
    public static final int REDIRECT_STATUS_CODE_RANGE_START = 300;
    protected final HttpConnector httpConnector;
    private volatile HttpClient client = null;
    private final Transformer sendTransformer;

    public HttpClientMessageDispatcher(OutboundEndpoint endpoint)
    {
        super(endpoint);
        this.httpConnector = (HttpConnector) endpoint.getConnector();
        List<Transformer> ts = httpConnector.getDefaultOutboundTransformers(null);
        if (ts.size() == 1)
        {
            this.sendTransformer = ts.get(0);
        }
        else if (ts.size() == 0)
        {
            this.sendTransformer = new ObjectToHttpClientMethodRequest();
            this.sendTransformer.setMuleContext(getEndpoint().getMuleContext());
            this.sendTransformer.setEndpoint(endpoint);
        }
        else
        {
            this.sendTransformer = new TransformerChain(ts);
        }
    }

    @Override
    protected void doInitialise() throws InitialisationException
    {
        super.doInitialise();
        sendTransformer.initialise();
    }
    
    @Override
    protected void initializeMessageFactory() throws InitialisationException
    {
        HttpMuleMessageFactory messageFactory;
        try
        {
            messageFactory = (HttpMuleMessageFactory) super.createMuleMessageFactory();
            messageFactory.setUri(endpoint.getAddress());
            this.muleMessageFactory = messageFactory;
        }
        catch (CreateException ce)
        {
            Message message = MessageFactory.createStaticMessage(ce.getMessage());
            throw new InitialisationException(message, ce, this);
        }
    }

    @Override
    protected void doConnect() throws Exception
    {
        if (client == null)
        {
            client = httpConnector.doClientConnect();
        }
    }

    @Override
    protected void doDisconnect() throws Exception
    {
        client = null;
    }

    @Override
    protected void doDispatch(MuleEvent event) throws Exception
    {
        org.apache.http.HttpRequest httpMethod = getMethod(event);

        httpConnector.setupClientAuthorization(event, httpMethod, client, endpoint);

        org.apache.http.HttpResponse response = null;

        try
        {
            response = execute(event, httpMethod);

            if (returnException(event, response))
            {
                logger.error(response.getEntity().toString());

                Exception cause = new Exception(String.format("Http call returned a status of: %1d %1s",
                                                              response.getStatusLine().getStatusCode(), response.getStatusLine().getReasonPhrase()));
                throw new DispatchException(event, getEndpoint(), cause);
            }
            else if (response.getStatusLine().getStatusCode() >= REDIRECT_STATUS_CODE_RANGE_START)
            {
                if (logger.isInfoEnabled())
                {
                    logger.info("Received a redirect response code: " + response.getStatusLine().getStatusCode() + " " + response.getStatusLine().getReasonPhrase());
                }
            }
        }
        finally
        {
            if (response != null)
            {
                EntityUtils.consumeQuietly(response.getEntity());
            }
        }
    }

    protected org.apache.http.HttpResponse execute(MuleEvent event, org.apache.http.HttpRequest httpMethod) throws Exception
    {
        // TODO set connection timeout buffer etc
        try
        {

            URI uri = endpoint.getEndpointURI().getUri();

            this.processCookies(event);
            this.processMuleSession(event, httpMethod);

            // TODO can we use the return code for better reporting?
            return client.execute(getHostConfig(uri), httpMethod);
        }
        catch (IOException e)
        {
            // TODO employ dispatcher reconnection strategy at this point
            throw new DispatchException(event, getEndpoint(), e);
        }
        catch (Exception e)
        {
            throw new DispatchException(event, getEndpoint(), e);
        }
    }

    private void processMuleSession(MuleEvent event, org.apache.http.HttpRequest httpMethod)
    {
        String muleSession = event.getMessage().getOutboundProperty(MuleProperties.MULE_SESSION_PROPERTY);

        if (muleSession != null)
        {
            httpMethod.setHeader(new BasicHeader(HttpConstants.HEADER_MULE_SESSION, muleSession));
        }
    }

    protected void processCookies(MuleEvent event)
    {
        MuleMessage msg = event.getMessage();

        Object cookiesProperty = msg.getInboundProperty(HttpConnector.HTTP_COOKIES_PROPERTY);
        String cookieSpecProperty = msg.getInboundProperty(HttpConnector.HTTP_COOKIE_SPEC_PROPERTY);
        processCookies(cookiesProperty, cookieSpecProperty, event);

        cookiesProperty = msg.getOutboundProperty(HttpConnector.HTTP_COOKIES_PROPERTY);
        cookieSpecProperty = msg.getOutboundProperty(HttpConnector.HTTP_COOKIE_SPEC_PROPERTY);
        processCookies(cookiesProperty, cookieSpecProperty, event);

        cookiesProperty = endpoint.getProperty(HttpConnector.HTTP_COOKIES_PROPERTY);
        cookieSpecProperty = (String) endpoint.getProperty(HttpConnector.HTTP_COOKIE_SPEC_PROPERTY);
        processCookies(cookiesProperty, cookieSpecProperty, event);
    }

    private void processCookies(Object cookieObject, String policy, MuleEvent event)
    {
        URI uri = this.getEndpoint().getEndpointURI().getUri();
        CookieHelper.addCookiesToClient(this.client, cookieObject, policy, event, uri);
    }

    protected org.apache.http.HttpRequest getMethod(MuleEvent event) throws TransformerException
    {
        MuleMessage msg = event.getMessage();
        setPropertyFromEndpoint(event, msg, HttpConnector.HTTP_CUSTOM_HEADERS_MAP_PROPERTY);

        org.apache.http.HttpRequest httpMethod;
        Object body = event.getMessage().getPayload();

        if (body instanceof org.apache.http.HttpRequest)
        {
            httpMethod = (org.apache.http.HttpRequest) body;
        }
        else
        {
            httpMethod = (org.apache.http.HttpRequest) sendTransformer.transform(msg);
        }
        if (httpMethod instanceof HttpRequestBase)
        {
            RequestConfig config = RequestConfig.custom()
                .setSocketTimeout(endpoint.getResponseTimeout())
                .setConnectTimeout(((TcpConnector) connector).getConnectionTimeout())
                .setRedirectsEnabled(
                    "true".equalsIgnoreCase((String) endpoint.getProperty("followRedirects")))
                .build();
            ((HttpRequestBase) httpMethod).setConfig(config);
        }

        // keepAlive=true is the default behavior of HttpClient
        if ("false".equalsIgnoreCase((String) endpoint.getProperty("keepAlive")))
        {
            httpMethod.setHeader(new BasicHeader("Connection", "close"));
        }

        return httpMethod;
    }

    protected void setPropertyFromEndpoint(MuleEvent event, MuleMessage msg, String prop)
    {
        Object o = msg.getOutboundProperty(prop);
        if (o == null)
        {
            o = endpoint.getProperty(prop);
            if (o != null)
            {
                msg.setOutboundProperty(prop, o);
            }
        }
    }

    @Override
    protected MuleMessage doSend(MuleEvent event) throws Exception
    {
        org.apache.http.HttpRequest httpMethod = getMethod(event);
        //TODO(pablo.kraan): HTTPCLIENT - fix this
        //httpConnector.setupClientAuthorization(event, httpMethod, client, endpoint);
        //
        //httpMethod.getParams().setParameter(HttpMethodParams.RETRY_HANDLER, new MuleHttpMethodRetryHandler());
        boolean releaseConn = false;
        org.apache.http.HttpResponse httpResponse = null;
        try
        {
            httpResponse = execute(event, httpMethod);

            DefaultExceptionPayload ep = null;

            if (returnException(event, httpResponse))
            {
                ep = new DefaultExceptionPayload(new DispatchException(event, getEndpoint(),
                        new HttpResponseException(httpResponse.getStatusLine().getReasonPhrase(), httpResponse.getStatusLine().getStatusCode())));
            }
            else if (httpResponse.getStatusLine().getStatusCode() >= REDIRECT_STATUS_CODE_RANGE_START)
            {
                try
                {
                    return handleRedirect(httpResponse, event);
                }
                catch (Exception e)
                {
                    ep = new DefaultExceptionPayload(new DispatchException(event, getEndpoint(), e));
                    return getResponseFromMethod(httpResponse, ep);
                }
            }
            releaseConn = httpResponse.getEntity().getContent() == null;
            return getResponseFromMethod(httpResponse, ep);
        }
        catch (Exception e)
        {
            releaseConn = true;
            if (e instanceof DispatchException)
            {
                throw e;
            }
            throw new DispatchException(event, getEndpoint(), e);
        }
        finally
        {
            if (releaseConn)
            {
                if (httpResponse != null)
                {
                    EntityUtils.consumeQuietly(httpResponse.getEntity());
                }
            }
        }
    }

    protected MuleMessage handleRedirect(org.apache.http.HttpResponse method, MuleEvent event) throws HttpResponseException, MuleException, IOException
    {
        String followRedirects = (String)endpoint.getProperty("followRedirects");
        if (followRedirects==null || "false".equalsIgnoreCase(followRedirects))
        {
            if (logger.isInfoEnabled())
            {
                logger.info("Received a redirect, but followRedirects=false. Response code: " + method.getStatusLine().getStatusCode() + " " + method.getStatusLine().getReasonPhrase());
            }
            return getResponseFromMethod(method, null);
        }
        Header locationHeader = method.getFirstHeader(HttpConstants.HEADER_LOCATION);
        if (locationHeader == null)
        {
            throw new HttpResponseException(method.getStatusLine().getReasonPhrase(), method.getStatusLine().getStatusCode());
        }
        OutboundEndpoint out = new EndpointURIEndpointBuilder(locationHeader.getValue(),
            getEndpoint().getMuleContext()).buildOutboundEndpoint();
        MuleEvent result = out.process(event);
        if (result != null && !VoidMuleEvent.getInstance().equals(result))
        {
            return result.getMessage();
        }
        else
        {
            return null;
        }
    }

    protected MuleMessage getResponseFromMethod(org.apache.http.HttpResponse httpMethod, ExceptionPayload ep)
        throws IOException, MuleException
    {
        MuleMessage message = createMuleMessage(httpMethod);

        if (logger.isDebugEnabled())
        {
            logger.debug("Http response is: " + message.getOutboundProperty(HttpConnector.HTTP_STATUS_PROPERTY));
        }

        message.setExceptionPayload(ep);
        return message;
    }

    /**
     * An exception is thrown if http.status >= 400 and exceptions are not disabled
     * through one of the following mechanisms in order of precedence:
     *
     *  - setting to true the flow variable "http.disable.status.code.exception.check"
     *  - setting to true the outbound property "http.disable.status.code.exception.check"
     *  - setting to false the outbound endpoint attribute "exceptionOnMessageError"
     *
     * @return if an exception should be thrown
     */
    protected boolean returnException(MuleEvent event, org.apache.http.HttpResponse httpResponse)
    {
        String disableCheck = event.getMessage().getInvocationProperty(HttpConnector.HTTP_DISABLE_STATUS_CODE_EXCEPTION_CHECK);
        if (disableCheck == null)
        {
            disableCheck = event.getMessage().getOutboundProperty(HttpConnector.HTTP_DISABLE_STATUS_CODE_EXCEPTION_CHECK);
        }

        boolean throwException;
        if (disableCheck == null)
        {
            throwException = !"false".equals(endpoint.getProperty("exceptionOnMessageError"));
        }
        else
        {
            throwException = !BooleanUtils.toBoolean(disableCheck);
        }

        return httpResponse.getStatusLine().getStatusCode() >= ERROR_STATUS_CODE_RANGE_START && throwException;
    }

    protected HttpHost getHostConfig(URI uri) throws Exception
    {
        String protocol = uri.getScheme().toLowerCase();

        String host = uri.getHost();
        int port = uri.getPort();
        HttpHost config = new HttpHost(host, port, protocol);
        if (StringUtils.isNotBlank(httpConnector.getProxyHostname()))
        {
            //TODO(pablo.kraan): HTTPCLIENT - add proxy support
            // add proxy support
            //config.setProxy(httpConnector.getProxyHostname(), httpConnector.getProxyPort());
        }

        return config;
    }

    @Override
    protected void doDispose()
    {
        // template method
    }

}
