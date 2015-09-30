package io.undertow.protocols.mh_stream;

import java.nio.ByteBuffer;

import org.xnio.Pooled;

import io.undertow.server.protocol.framed.AbstractFramedStreamSourceChannel;

public class AbstractAjpClientStreamSourceChannel extends AbstractFramedStreamSourceChannel<AjpClientChannel,AbstractAjpClientStreamSourceChannel, AbstractMhStreamClientStreamSinkChannel> {


    public AbstractAjpClientStreamSourceChannel(AjpClientChannel framedChannel, Pooled<ByteBuffer> data, long frameDataRemaining) {
        super(framedChannel, data, frameDataRemaining);
    }
}
