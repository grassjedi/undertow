package io.undertow.protocols.mh_stream;

import static io.undertow.protocols.mh_stream.AjpConstants.FRAME_TYPE_END_RESPONSE;
import static io.undertow.protocols.mh_stream.AjpConstants.FRAME_TYPE_REQUEST_BODY_CHUNK;
import static io.undertow.protocols.mh_stream.AjpConstants.FRAME_TYPE_SEND_BODY_CHUNK;
import static io.undertow.protocols.mh_stream.AjpConstants.FRAME_TYPE_SEND_HEADERS;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.xnio.ChannelListener;
import org.xnio.IoUtils;
import org.xnio.Pool;
import org.xnio.Pooled;
import org.xnio.StreamConnection;

import io.undertow.UndertowLogger;
import io.undertow.UndertowMessages;
import io.undertow.server.protocol.framed.AbstractFramedChannel;
import io.undertow.server.protocol.framed.AbstractFramedStreamSourceChannel;
import io.undertow.server.protocol.framed.FrameHeaderData;
import io.undertow.util.Attachable;
import io.undertow.util.HeaderMap;
import io.undertow.util.HttpString;


public class AjpClientChannel extends AbstractFramedChannel<AjpClientChannel, AbstractAjpClientStreamSourceChannel, AbstractMhStreamClientStreamSinkChannel> {

    private final AjpResponseParser ajpParser;

    private AjpClientResponseStreamSourceChannel source;
    private MhStreamClientRequestClientStreamSinkChannel sink;

    boolean sinkDone = true;
    boolean sourceDone = true;

    private boolean lastFrameSent;
    private boolean lastFrameRecieved;

    public AjpClientChannel(StreamConnection connectedStreamChannel, Pool<ByteBuffer> bufferPool) {
        super(connectedStreamChannel, bufferPool, AjpClientFramePriority.INSTANCE, null);
        ajpParser = new AjpResponseParser();
    }

    @Override
    protected AbstractAjpClientStreamSourceChannel createChannel(FrameHeaderData frameHeaderData, Pooled<ByteBuffer> frameData) throws IOException {
        if (frameHeaderData instanceof SendHeadersResponse) {
            SendHeadersResponse h = (SendHeadersResponse) frameHeaderData;
            AjpClientResponseStreamSourceChannel sourceChannel = new AjpClientResponseStreamSourceChannel(this, h.headers, h.statusCode, h.reasonPhrase, frameData, (int) frameHeaderData.getFrameLength());
            this.source = sourceChannel;
            return sourceChannel;
        } else if (frameHeaderData instanceof RequestBodyChunk) {
            RequestBodyChunk r = (RequestBodyChunk) frameHeaderData;
            this.sink.chunkRequested(r.getLength());
            frameData.free();
            return null;
        } else {
            frameData.free();
            throw new RuntimeException("TODO: unknown frame");
        }

    }

    @Override
    protected FrameHeaderData parseFrame(ByteBuffer data) throws IOException {
        ajpParser.parse(data);
        if (ajpParser.isComplete()) {
            try {
                AjpResponseParser parser = ajpParser;
                if (parser.prefix == FRAME_TYPE_SEND_HEADERS) {
                    return new SendHeadersResponse(parser.statusCode, parser.reasonPhrase, parser.headers);
                } else if (parser.prefix == FRAME_TYPE_REQUEST_BODY_CHUNK) {
                    return new RequestBodyChunk(parser.readBodyChunkSize);
                } else if (parser.prefix == FRAME_TYPE_SEND_BODY_CHUNK) {
                    return new SendBodyChunk(parser.currentIntegerPart + 1); //+1 for the null terminator
                } else if (parser.prefix == FRAME_TYPE_END_RESPONSE) {
                    boolean persistent = parser.currentIntegerPart != 0;
                    if (!persistent) {
                        lastFrameRecieved = true;
                        lastFrameSent = true;
                    }
                    return new EndResponse();
                } else {
                    //TODO: ping pong ETC
                    UndertowLogger.ROOT_LOGGER.debug("UNKOWN FRAME");
                }
            } finally {
                ajpParser.reset();
            }
        }
        return null;
    }

    public MhStreamClientRequestClientStreamSinkChannel sendRequest(final HttpString method, final String path, final HttpString protocol, final HeaderMap headers, final Attachable attachable, ChannelListener<MhStreamClientRequestClientStreamSinkChannel> finishListener) {
        if (!sinkDone || !sourceDone) {
            throw UndertowMessages.MESSAGES.ajpRequestAlreadyInProgress();
        }
        sinkDone = false;
        sourceDone = false;
        MhStreamClientRequestClientStreamSinkChannel ajpClientRequestStreamSinkChannel = new MhStreamClientRequestClientStreamSinkChannel(this, finishListener, headers, path, method, protocol, attachable);
        sink = ajpClientRequestStreamSinkChannel;
        source = null;
        return ajpClientRequestStreamSinkChannel;
    }

    @Override
    protected boolean isLastFrameReceived() {
        return lastFrameRecieved;
    }

    @Override
    protected boolean isLastFrameSent() {
        return lastFrameSent;
    }

    protected void lastDataRead() {
        lastFrameRecieved = true;
        lastFrameSent = true;
        IoUtils.safeClose(this);
    }

    @Override
    protected void handleBrokenSourceChannel(Throwable e) {
        IoUtils.safeClose(source, sink);
        UndertowLogger.REQUEST_IO_LOGGER.ioException(new IOException(e));
        IoUtils.safeClose(this);
    }

    @Override
    protected void handleBrokenSinkChannel(Throwable e) {
        IoUtils.safeClose(source, sink);
        UndertowLogger.REQUEST_IO_LOGGER.ioException(new IOException(e));
        IoUtils.safeClose(this);
    }

    @Override
    protected void closeSubChannels() {
        IoUtils.safeClose(source, sink);
    }

    void sinkDone() {
        sinkDone = true;
        if (sourceDone) {
            sink = null;
            source = null;
        }
    }

    void sourceDone() {
        sourceDone = true;
        if (sinkDone) {
            sink = null;
            source = null;
        } else {
            sink.startDiscard();
        }
    }

    @Override
    public boolean isOpen() {
        return super.isOpen() && !lastFrameSent && !lastFrameRecieved;
    }

    @Override
    protected synchronized void recalculateHeldFrames() throws IOException {
        super.recalculateHeldFrames();
    }

    class SendHeadersResponse implements FrameHeaderData {

        private final int statusCode;
        private final String reasonPhrase;
        private final HeaderMap headers;

        SendHeadersResponse(int statusCode, String reasonPhrase, HeaderMap headers) {
            this.statusCode = statusCode;
            this.reasonPhrase = reasonPhrase;
            this.headers = headers;
        }

        @Override
        public long getFrameLength() {
            //zero
            return 0;
        }

        @Override
        public AbstractFramedStreamSourceChannel<?, ?, ?> getExistingChannel() {
            return null;
        }
    }


    class RequestBodyChunk implements FrameHeaderData {

        private final int length;

        RequestBodyChunk(int length) {
            this.length = length;
        }

        public int getLength() {
            return length;
        }

        @Override
        public long getFrameLength() {
            return 0;
        }

        @Override
        public AbstractFramedStreamSourceChannel<?, ?, ?> getExistingChannel() {
            return null;
        }
    }


    class SendBodyChunk implements FrameHeaderData {

        private final int length;

        SendBodyChunk(int length) {
            this.length = length;
        }

        @Override
        public long getFrameLength() {
            return length;
        }

        @Override
        public AbstractFramedStreamSourceChannel<?, ?, ?> getExistingChannel() {
            return source;
        }
    }

    class EndResponse implements FrameHeaderData {

        @Override
        public long getFrameLength() {
            return 0;
        }

        @Override
        public AbstractFramedStreamSourceChannel<?, ?, ?> getExistingChannel() {
            return source;
        }
    }
}
