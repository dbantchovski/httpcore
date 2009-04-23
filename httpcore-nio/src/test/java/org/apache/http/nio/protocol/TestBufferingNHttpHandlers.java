/*
 * $HeadURL$
 * $Revision$
 * $Date$
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 */

package org.apache.http.nio.protocol;

import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import junit.framework.Test;
import junit.framework.TestSuite;

import org.apache.http.HttpCoreNIOTestBase;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpRequest;
import org.apache.http.HttpStatus;
import org.apache.http.HttpVersion;
import org.apache.http.impl.DefaultConnectionReuseStrategy;
import org.apache.http.impl.DefaultHttpResponseFactory;
import org.apache.http.message.BasicHttpEntityEnclosingRequest;
import org.apache.http.message.BasicHttpRequest;
import org.apache.http.mockup.SimpleEventListener;
import org.apache.http.mockup.SimpleHttpRequestHandlerResolver;
import org.apache.http.nio.entity.NStringEntity;
import org.apache.http.nio.reactor.ListenerEndpoint;
import org.apache.http.params.CoreProtocolPNames;
import org.apache.http.protocol.BasicHttpProcessor;
import org.apache.http.protocol.HttpRequestHandler;
import org.apache.http.protocol.RequestConnControl;
import org.apache.http.protocol.RequestContent;
import org.apache.http.protocol.RequestExpectContinue;
import org.apache.http.protocol.RequestTargetHost;
import org.apache.http.protocol.RequestUserAgent;
import org.apache.http.protocol.ResponseConnControl;
import org.apache.http.protocol.ResponseContent;
import org.apache.http.protocol.ResponseDate;
import org.apache.http.protocol.ResponseServer;

/**
 * HttpCore NIO integration tests using buffering versions of the 
 * protocol handlers.
 *
 *
 * @version $Id$
 */
public class TestBufferingNHttpHandlers extends HttpCoreNIOTestBase {

    // ------------------------------------------------------------ Constructor
    public TestBufferingNHttpHandlers(String testName) {
        super(testName);
    }

    // ------------------------------------------------------------------- Main
    public static void main(String args[]) {
        String[] testCaseName = { TestBufferingNHttpHandlers.class.getName() };
        junit.textui.TestRunner.main(testCaseName);
    }

    // ------------------------------------------------------- TestCase Methods

    public static Test suite() {
        return new TestSuite(TestBufferingNHttpHandlers.class);
    }

    private void executeStandardTest(
            final HttpRequestHandler requestHandler,
            final HttpRequestExecutionHandler requestExecutionHandler) throws Exception {
        int connNo = 3;
        int reqNo = 20;
        TestJob[] jobs = new TestJob[connNo * reqNo];
        for (int i = 0; i < jobs.length; i++) {
            jobs[i] = new TestJob(); 
        }
        Queue<TestJob> queue = new ConcurrentLinkedQueue<TestJob>();
        for (int i = 0; i < jobs.length; i++) {
            queue.add(jobs[i]); 
        }

        BasicHttpProcessor serverHttpProc = new BasicHttpProcessor();
        serverHttpProc.addInterceptor(new ResponseDate());
        serverHttpProc.addInterceptor(new ResponseServer());
        serverHttpProc.addInterceptor(new ResponseContent());
        serverHttpProc.addInterceptor(new ResponseConnControl());

        BufferingHttpServiceHandler serviceHandler = new BufferingHttpServiceHandler(
                serverHttpProc,
                new DefaultHttpResponseFactory(),
                new DefaultConnectionReuseStrategy(),
                this.server.getParams());

        serviceHandler.setHandlerResolver(
                new SimpleHttpRequestHandlerResolver(requestHandler));
        serviceHandler.setEventListener(
                new SimpleEventListener());

        BasicHttpProcessor clientHttpProc = new BasicHttpProcessor();
        clientHttpProc.addInterceptor(new RequestContent());
        clientHttpProc.addInterceptor(new RequestTargetHost());
        clientHttpProc.addInterceptor(new RequestConnControl());
        clientHttpProc.addInterceptor(new RequestUserAgent());
        clientHttpProc.addInterceptor(new RequestExpectContinue());

        BufferingHttpClientHandler clientHandler = new BufferingHttpClientHandler(
                clientHttpProc,
                requestExecutionHandler,
                new DefaultConnectionReuseStrategy(),
                this.client.getParams());

        clientHandler.setEventListener(
                new SimpleEventListener());
        
        this.server.start(serviceHandler);
        this.client.start(clientHandler);

        ListenerEndpoint endpoint = this.server.getListenerEndpoint();
        endpoint.waitFor();
        InetSocketAddress serverAddress = (InetSocketAddress) endpoint.getAddress();

        for (int i = 0; i < connNo; i++) {
            this.client.openConnection(
                    new InetSocketAddress("localhost", serverAddress.getPort()),
                    queue);
        }

        for (int i = 0; i < jobs.length; i++) {
            TestJob testjob = jobs[i];
            testjob.waitFor();
            if (testjob.isSuccessful()) {
                assertEquals(HttpStatus.SC_OK, testjob.getStatusCode());
                assertEquals(testjob.getExpected(), testjob.getResult());
            } else {
                fail(testjob.getFailureMessage());
            }
        }
    }
    
    /**
     * This test case executes a series of simple (non-pipelined) GET requests
     * over multiple connections.
     */
    public void testHttpGets() throws Exception {
        HttpRequestExecutionHandler requestExecutionHandler = new TestRequestExecutionHandler() {

            @Override
            protected HttpRequest generateRequest(TestJob testjob) {
                String s = testjob.getPattern() + "x" + testjob.getCount(); 
                return new BasicHttpRequest("GET", s);
            }
            
        };
        executeStandardTest(new TestRequestHandler(), requestExecutionHandler);
    }

    /**
     * This test case executes a series of simple (non-pipelined) POST requests
     * with content length delimited content over multiple connections.
     */
    public void testHttpPostsWithContentLength() throws Exception {
        HttpRequestExecutionHandler requestExecutionHandler = new TestRequestExecutionHandler() {

            @Override
            protected HttpRequest generateRequest(TestJob testjob) {
                String s = testjob.getPattern() + "x" + testjob.getCount(); 
                HttpEntityEnclosingRequest r = new BasicHttpEntityEnclosingRequest("POST", s);
                NStringEntity entity = null;
                try {
                    entity = new NStringEntity(testjob.getExpected(), "US-ASCII");
                    entity.setChunked(false);
                } catch (UnsupportedEncodingException ignore) {
                }
                r.setEntity(entity);
                return r;
            }
            
        };
        executeStandardTest(new TestRequestHandler(), requestExecutionHandler);
    }

    /**
     * This test case executes a series of simple (non-pipelined) POST requests
     * with chunk coded content content over multiple connections.
     */
    public void testHttpPostsChunked() throws Exception {
        HttpRequestExecutionHandler requestExecutionHandler = new TestRequestExecutionHandler() {

            @Override
            protected HttpRequest generateRequest(TestJob testjob) {
                String s = testjob.getPattern() + "x" + testjob.getCount(); 
                HttpEntityEnclosingRequest r = new BasicHttpEntityEnclosingRequest("POST", s);
                NStringEntity entity = null;
                try {
                    entity = new NStringEntity(testjob.getExpected(), "US-ASCII");
                    entity.setChunked(true);
                } catch (UnsupportedEncodingException ignore) {
                }
                r.setEntity(entity);
                return r;
            }
            
        };
        executeStandardTest(new TestRequestHandler(), requestExecutionHandler);
    }

    /**
     * This test case executes a series of simple (non-pipelined) HTTP/1.0
     * POST requests over multiple persistent connections.
     */
    public void testHttpPostsHTTP10() throws Exception {
        HttpRequestExecutionHandler requestExecutionHandler = new TestRequestExecutionHandler() {

            @Override
            protected HttpRequest generateRequest(TestJob testjob) {
                String s = testjob.getPattern() + "x" + testjob.getCount(); 
                HttpEntityEnclosingRequest r = new BasicHttpEntityEnclosingRequest("POST", s, 
                        HttpVersion.HTTP_1_0);
                NStringEntity entity = null;
                try {
                    entity = new NStringEntity(testjob.getExpected(), "US-ASCII");
                } catch (UnsupportedEncodingException ignore) {
                }
                r.setEntity(entity);
                return r;
            }
            
        };
        executeStandardTest(new TestRequestHandler(), requestExecutionHandler);
    }

    /**
     * This test case executes a series of simple (non-pipelined) POST requests
     * over multiple connections using the 'expect: continue' handshake.
     */
    public void testHttpPostsWithExpectContinue() throws Exception {
        HttpRequestExecutionHandler requestExecutionHandler = new TestRequestExecutionHandler() {

            @Override
            protected HttpRequest generateRequest(TestJob testjob) {
                String s = testjob.getPattern() + "x" + testjob.getCount(); 
                HttpEntityEnclosingRequest r = new BasicHttpEntityEnclosingRequest("POST", s);
                NStringEntity entity = null;
                try {
                    entity = new NStringEntity(testjob.getExpected(), "US-ASCII");
                } catch (UnsupportedEncodingException ignore) {
                }
                r.setEntity(entity);
                r.getParams().setBooleanParameter(CoreProtocolPNames.USE_EXPECT_CONTINUE, true);
                return r;
            }
            
        };
        executeStandardTest(new TestRequestHandler(), requestExecutionHandler);
    }

}