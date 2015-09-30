package io.undertow.protocols.mh_stream;

import io.undertow.server.protocol.framed.AbstractFramedStreamSinkChannel;

public class AbstractMhStreamClientStreamSinkChannel extends AbstractFramedStreamSinkChannel<AjpClientChannel, AbstractAjpClientStreamSourceChannel,AbstractMhStreamClientStreamSinkChannel> {
    protected AbstractMhStreamClientStreamSinkChannel(AjpClientChannel channel) {
        super(channel);
    }

    @Override
    protected boolean isLastFrame() {
        return false;
    }

}
