package io.undertow.server.protocol.mhstream;

import io.undertow.UndertowLogger;
import io.undertow.UndertowMessages;
import io.undertow.UndertowOptions;
import io.undertow.conduits.BytesReceivedStreamSourceConduit;
import io.undertow.conduits.BytesSentStreamSinkConduit;
import io.undertow.conduits.ReadTimeoutStreamSourceConduit;
import io.undertow.conduits.WriteTimeoutStreamSinkConduit;
import io.undertow.server.ConnectorStatistics;
import io.undertow.server.ConnectorStatisticsImpl;
import io.undertow.server.HttpHandler;
import io.undertow.server.OpenListener;
import org.xnio.IoUtils;
import org.xnio.OptionMap;
import org.xnio.Options;
import org.xnio.Pool;
import org.xnio.Pooled;
import org.xnio.StreamConnection;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static io.undertow.UndertowOptions.DECODE_URL;
import static io.undertow.UndertowOptions.URL_CHARSET;

public class MhStreamOpenListener implements OpenListener {

    private final Pool<ByteBuffer> bufferPool;
    private final int bufferSize;
    private volatile String scheme;
    private volatile HttpHandler rootHandler;
    private volatile OptionMap undertowOptions;
    private volatile boolean statisticsEnabled;
    private final ConnectorStatisticsImpl connectorStatistics;

    @Deprecated
    public MhStreamOpenListener(final Pool<ByteBuffer> pool, final int bufferSize) {
        this(pool, OptionMap.EMPTY);
    }

    @Deprecated
    public MhStreamOpenListener(final Pool<ByteBuffer> pool, final OptionMap undertowOptions, final int bufferSize) {
        this(pool, undertowOptions);
    }
    public MhStreamOpenListener(final Pool<ByteBuffer> pool) {
        this(pool, OptionMap.EMPTY);
    }

    public MhStreamOpenListener(final Pool<ByteBuffer> pool, final OptionMap undertowOptions) {
        this.undertowOptions = undertowOptions;
        this.bufferPool = pool;
        Pooled<ByteBuffer> buf = pool.allocate();
        this.bufferSize = buf.getResource().remaining();
        buf.free();
        connectorStatistics = new ConnectorStatisticsImpl();
        statisticsEnabled = undertowOptions.get(UndertowOptions.ENABLE_CONNECTOR_STATISTICS, false);
    }

    @Override
    public void handleEvent(final StreamConnection channel) {
        if (UndertowLogger.REQUEST_LOGGER.isTraceEnabled()) {
            UndertowLogger.REQUEST_LOGGER.tracef("Opened connection with %s", channel.getPeerAddress());
        }

        //set read and write timeouts
        try {
            Integer readTimeout = channel.getOption(Options.READ_TIMEOUT);
            Integer idleTimeout = undertowOptions.get(UndertowOptions.IDLE_TIMEOUT);
            if ((readTimeout == null || readTimeout <= 0) && idleTimeout != null) {
                readTimeout = idleTimeout;
            } else if (readTimeout != null && idleTimeout != null && idleTimeout > 0) {
                readTimeout = Math.min(readTimeout, idleTimeout);
            }
            if (readTimeout != null && readTimeout > 0) {
                channel.getSourceChannel().setConduit(new ReadTimeoutStreamSourceConduit(channel.getSourceChannel().getConduit(), channel, this));
            }
            Integer writeTimeout = channel.getOption(Options.WRITE_TIMEOUT);
            if ((writeTimeout == null || writeTimeout <= 0) && idleTimeout != null) {
                writeTimeout = idleTimeout;
            } else if (writeTimeout != null && idleTimeout != null && idleTimeout > 0) {
                writeTimeout = Math.min(writeTimeout, idleTimeout);
            }
            if (writeTimeout != null && writeTimeout > 0) {
                channel.getSinkChannel().setConduit(new WriteTimeoutStreamSinkConduit(channel.getSinkChannel().getConduit(), channel, this));
            }
        } catch (IOException e) {
            IoUtils.safeClose(channel);
            UndertowLogger.REQUEST_IO_LOGGER.ioException(e);
        }
        if(statisticsEnabled) {
            channel.getSinkChannel().setConduit(new BytesSentStreamSinkConduit(channel.getSinkChannel().getConduit(), connectorStatistics.sentAccumulator()));
            channel.getSourceChannel().setConduit(new BytesReceivedStreamSourceConduit(channel.getSourceChannel().getConduit(), connectorStatistics.receivedAccumulator()));
        }

        MhStreamServerConnection connection = new MhStreamServerConnection(channel, bufferPool, rootHandler, undertowOptions, bufferSize);
        MhStreamReadListener readListener = new MhStreamReadListener(connection, statisticsEnabled ? connectorStatistics : null);
        connection.setMhStreamReadListener(readListener);
        readListener.startRequest();
        channel.getSourceChannel().setReadListener(readListener);
        readListener.handleEvent(channel.getSourceChannel());
    }

    @Override
    public HttpHandler getRootHandler() {
        return rootHandler;
    }

    @Override
    public void setRootHandler(final HttpHandler rootHandler) {
        this.rootHandler = rootHandler;
    }

    @Override
    public OptionMap getUndertowOptions() {
        return undertowOptions;
    }

    @Override
    public void setUndertowOptions(final OptionMap undertowOptions) {
        if (undertowOptions == null) {
            throw UndertowMessages.MESSAGES.argumentCannotBeNull("undertowOptions");
        }
        this.undertowOptions = undertowOptions;
        statisticsEnabled = undertowOptions.get(UndertowOptions.ENABLE_CONNECTOR_STATISTICS, false);
    }

    @Override
    public Pool<ByteBuffer> getBufferPool() {
        return bufferPool;
    }

    @Override
    public ConnectorStatistics getConnectorStatistics() {
        if(statisticsEnabled) {
            return connectorStatistics;
        }
        return null;
    }

    public String getScheme() {
        return scheme;
    }

    public void setScheme(final String scheme) {
        this.scheme = scheme;
    }
}
