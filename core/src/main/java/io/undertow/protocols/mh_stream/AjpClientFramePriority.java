package io.undertow.protocols.mh_stream;

import java.util.Deque;
import java.util.Iterator;
import java.util.List;

import io.undertow.server.protocol.framed.FramePriority;
import io.undertow.server.protocol.framed.SendFrameHeader;

class AjpClientFramePriority implements FramePriority<AjpClientChannel, AbstractAjpClientStreamSourceChannel, AbstractMhStreamClientStreamSinkChannel>{

    public static AjpClientFramePriority INSTANCE = new AjpClientFramePriority();

    @Override
    public boolean insertFrame(AbstractMhStreamClientStreamSinkChannel newFrame, List<AbstractMhStreamClientStreamSinkChannel> pendingFrames) {
        if(newFrame instanceof MhStreamClientRequestClientStreamSinkChannel) {
            SendFrameHeader header = ((MhStreamClientRequestClientStreamSinkChannel) newFrame).generateSendFrameHeader();
            if(header.getByteBuffer() == null) {
                //we clear the header, as we want to generate a new real header when the flow control window is updated
                ((MhStreamClientRequestClientStreamSinkChannel) newFrame).clearHeader();
                return false;
            }
        }
        pendingFrames.add(newFrame);
        return true;
    }

    @Override
    public void frameAdded(AbstractMhStreamClientStreamSinkChannel addedFrame, List<AbstractMhStreamClientStreamSinkChannel> pendingFrames, Deque<AbstractMhStreamClientStreamSinkChannel> holdFrames) {
        Iterator<AbstractMhStreamClientStreamSinkChannel> it = holdFrames.iterator();
        while (it.hasNext()){
            AbstractMhStreamClientStreamSinkChannel pending = it.next();
            if(pending instanceof MhStreamClientRequestClientStreamSinkChannel) {
                SendFrameHeader header = ((MhStreamClientRequestClientStreamSinkChannel) pending).generateSendFrameHeader();
                if(header.getByteBuffer() != null) {
                    pendingFrames.add(pending);
                    it.remove();
                } else {
                    //we clear the header, as we want to generate a new real header when the flow control window is updated
                    ((MhStreamClientRequestClientStreamSinkChannel) pending).clearHeader();
                }
            }
        }
    }
}
