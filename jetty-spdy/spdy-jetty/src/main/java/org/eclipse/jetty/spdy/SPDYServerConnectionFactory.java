//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.spdy;

import java.util.Collection;
import java.util.Collections;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.AbstractConnectionFactory;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.server.ServerSessionFrameListener;
import org.eclipse.jetty.spdy.generator.Generator;
import org.eclipse.jetty.spdy.parser.Parser;

public class SPDYServerConnectionFactory extends AbstractConnectionFactory
{
    private final short version;
    private final ServerSessionFrameListener listener;
    private int initialWindowSize;
    private final Queue<Session> sessions = new ConcurrentLinkedQueue<>();
    
    public SPDYServerConnectionFactory(int version)
    {
        this(version, null);
    }

    public SPDYServerConnectionFactory(int version,  ServerSessionFrameListener listener)
    {
        super("spdy/"+version);
        this.version = (short)version;
        this.listener = listener;
        setInitialWindowSize(65536);
    }

    public short getVersion()
    {
        return version;
    }

    public ServerSessionFrameListener getServerSessionFrameListener()
    {
        return listener;
    }
    
    @Override
    public Connection newConnection(Connector connector, EndPoint endPoint)
    {
        CompressionFactory compressionFactory = new StandardCompressionFactory();
        Parser parser = new Parser(compressionFactory.newDecompressor());
        Generator generator = new Generator(connector.getByteBufferPool(), compressionFactory.newCompressor());

        ServerSessionFrameListener listener = provideServerSessionFrameListener(connector,endPoint);
        SPDYConnection connection = new ServerSPDYConnection(connector,endPoint, parser, listener, getInputBufferSize());

        FlowControlStrategy flowControlStrategy = newFlowControlStrategy(version);

        StandardSession session = new StandardSession(getVersion(), connector.getByteBufferPool(), connector.getExecutor(), connector.getScheduler(), connection, connection, 2, listener, generator, flowControlStrategy);
        session.setAttribute("org.eclipse.jetty.spdy.remoteAddress", endPoint.getRemoteAddress()); // TODO: make this available through API
        session.setWindowSize(getInitialWindowSize());
        parser.addListener(session);
        connection.setSession(session);

        sessionOpened(session);

        return connection;
    }

    protected FlowControlStrategy newFlowControlStrategy(short version)
    {
        return FlowControlStrategyFactory.newFlowControlStrategy(version);
    }

    protected ServerSessionFrameListener provideServerSessionFrameListener(Connector connector, EndPoint endPoint)
    {
        return listener;
    }

    public int getInitialWindowSize()
    {
        return initialWindowSize;
    }

    public void setInitialWindowSize(int initialWindowSize)
    {
        this.initialWindowSize = initialWindowSize;
    }

    protected boolean sessionOpened(Session session)
    {
        // Add sessions only if the connector is not stopping
        return sessions.offer(session);
    }

    protected boolean sessionClosed(Session session)
    {
        // Remove sessions only if the connector is not stopping
        // to avoid concurrent removes during iterations
        return sessions.remove(session);
    }

    void closeSessions()
    {
        for (Session session : sessions)
            session.goAway();
        sessions.clear();
    }

    @Override
    protected void doStop() throws Exception
    {
        closeSessions();
        super.doStop();
    }

    protected Collection<Session> getSessions()
    {
        return Collections.unmodifiableCollection(sessions);
    }

    private class ServerSPDYConnection extends SPDYConnection implements Runnable
    {
        private final ServerSessionFrameListener listener;
        private final AtomicBoolean connected = new AtomicBoolean();

        private ServerSPDYConnection(Connector connector,EndPoint endPoint, Parser parser, ServerSessionFrameListener listener, int bufferSize)
        {
            super(endPoint, connector.getByteBufferPool(), parser, connector.getExecutor(),bufferSize);
            this.listener = listener;
        }

        @Override
        public void onOpen()
        {
            super.onOpen();
            if (connected.compareAndSet(false, true))
                getExecutor().execute(this);
        }

        @Override
        public void onClose()
        {
            super.onClose();
            sessionClosed(getSession());
        }

        @Override
        public void run()
        {
            // NPE guard to support tests
            if (listener != null)
                listener.onConnect(getSession());
        }
    }

}
