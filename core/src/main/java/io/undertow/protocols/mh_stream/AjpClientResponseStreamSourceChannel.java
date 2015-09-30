package io.undertow.protocols.mh_stream;

import java.io.IOException;
import java.nio.ByteBuffer;

import io.undertow.protocols.ajp.*;
import io.undertow.protocols.ajp.AjpClientChannel;
import org.xnio.ChannelListener;
import org.xnio.Pooled;

import io.undertow.server.protocol.framed.FrameHeaderData;
import io.undertow.util.HeaderMap;

public class AjpClientResponseStreamSourceChannel extends AbstractAjpClientStreamSourceChannel {

    private ChannelListener<AjpClientResponseStreamSourceChannel> finishListener;

    private final HeaderMap headers;
    private final int statusCode;
    private final String reasonPhrase;

    public AjpClientResponseStreamSourceChannel(io.undertow.protocols.mh_stream.AjpClientChannel framedChannel, HeaderMap headers, int statusCode, String reasonPhrase, Pooled<ByteBuffer> frameData, int remaining) {
        super(framedChannel, frameData, remaining);
        this.headers = headers;
        this.statusCode = statusCode;
        this.reasonPhrase = reasonPhrase;
    }

    public HeaderMap getHeaders() {
        return headers;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getReasonPhrase() {
        return reasonPhrase;
    }

    public void setFinishListener(ChannelListener<AjpClientResponseStreamSourceChannel> finishListener) {
        this.finishListener = finishListener;
    }

    @Override
    protected void handleHeaderData(FrameHeaderData headerData) {
        if(headerData instanceof io.undertow.protocols.mh_stream.AjpClientChannel.EndResponse) {
            lastFrame();
        }
    }
    protected long handleFrameData(Pooled<ByteBuffer> frameData, long frameDataRemaining) {
        if(frameDataRemaining > 0  && frameData.getResource().remaining() == frameDataRemaining) {
            //there is a null terminator on the end
            frameData.getResource().limit(frameData.getResource().limit() - 1);
            return frameDataRemaining - 1;
        }
        return frameDataRemaining;
    }

    @Override
    protected void complete() throws IOException {
        if(finishListener != null) {
            getFramedChannel().sourceDone();
            finishListener.handleEvent(this);
        }
    }
}
