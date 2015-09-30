package io.undertow.server.protocol.mhstream;

import io.undertow.UndertowMessages;
import io.undertow.server.AbstractServerConnection;
import io.undertow.server.BasicSSLSessionInfo;
import io.undertow.server.HttpUpgradeListener;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.SSLSessionInfo;
import io.undertow.util.DateUtils;
import org.xnio.OptionMap;
import org.xnio.Pool;
import org.xnio.StreamConnection;
import org.xnio.conduits.ConduitStreamSinkChannel;
import org.xnio.conduits.StreamSinkConduit;
import org.xnio.conduits.WriteReadyHandler;

import java.nio.ByteBuffer;

public final class MhStreamServerConnection extends AbstractServerConnection {
    private WriteReadyHandler.ChannelListenerHandler<ConduitStreamSinkChannel> writeReadyHandler;
    private MhStreamReadListener mhStreamReadListener;

    public MhStreamServerConnection(StreamConnection channel, Pool<ByteBuffer> bufferPool, HttpHandler rootHandler, OptionMap undertowOptions, int bufferSize) {
        super(channel, bufferPool, rootHandler, undertowOptions, bufferSize);
        this.writeReadyHandler = new WriteReadyHandler.ChannelListenerHandler<>(channel.getSinkChannel());
    }

    @Override
    public HttpServerExchange sendOutOfBandResponse(HttpServerExchange exchange) {
        throw UndertowMessages.MESSAGES.outOfBandResponseNotSupported();
    }

    @Override
    public boolean isContinueResponseSupported() {
        return false;
    }

    @Override
    public void terminateRequestChannel(HttpServerExchange exchange) {
        //todo: terminate
    }

    @Override
    public void restoreChannel(ConduitState state) {
        super.restoreChannel(state);
        channel.getSinkChannel().getConduit().setWriteReadyHandler(writeReadyHandler);
    }

    @Override
    public ConduitState resetChannel() {
        ConduitState state = super.resetChannel();
        channel.getSinkChannel().getConduit().setWriteReadyHandler(writeReadyHandler);
        return state;
    }

    @Override
    public void clearChannel() {
        super.clearChannel();
        channel.getSinkChannel().getConduit().setWriteReadyHandler(writeReadyHandler);
    }

    @Override
    public SSLSessionInfo getSslSessionInfo() {
        return null;
    }

    @Override
    public void setSslSessionInfo(SSLSessionInfo sessionInfo) {}

    void setSSLSessionInfo(BasicSSLSessionInfo sslSessionInfo) {}

    @Override
    protected StreamConnection upgradeChannel() {
        throw UndertowMessages.MESSAGES.upgradeNotSupported();
    }

    @Override
    protected StreamSinkConduit getSinkConduit(HttpServerExchange exchange, StreamSinkConduit conduit) {
        DateUtils.addDateHeaderIfRequired(exchange);
        return conduit;
    }

    @Override
    protected boolean isUpgradeSupported() {
        return false;
    }

    @Override
    protected boolean isConnectSupported() {
        return false;
    }

    void setMhStreamReadListener(MhStreamReadListener mhStreamReadListener) {
        this.mhStreamReadListener = mhStreamReadListener;
    }

    @Override
    protected void exchangeComplete(HttpServerExchange exchange) {
        this.mhStreamReadListener.exchangeComplete(exchange);
    }

    @Override
    protected void setConnectListener(HttpUpgradeListener connectListener) {
        throw UndertowMessages.MESSAGES.connectNotSupported();
    }

    void setCurrentExchange(HttpServerExchange exchange) {
        this.current = exchange;
    }

    @Override
    public String getTransportProtocol() {
        return "mh-stream";
    }
}
