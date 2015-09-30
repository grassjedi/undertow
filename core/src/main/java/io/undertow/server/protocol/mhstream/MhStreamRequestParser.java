package io.undertow.server.protocol.mhstream;

import io.undertow.server.HttpServerExchange;
import io.undertow.util.HttpString;

import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;

public class MhStreamRequestParser {
	public static final String TQ_FUNCTION_START_TAG = "<TQ-FUNCTION>";

	public void parse(final ByteBuffer buf, final MhStreamRequestParseState state, final HttpServerExchange exchange) throws IOException {
		if(!buf.hasRemaining()) {
			return;
		}
		if(state.buffer == null) {
			state.buffer = new byte[(int) exchange.getMaxEntitySize()];
		}
		switch(state.state) {
			case MhStreamRequestParseState.START:
				state.state = MhStreamRequestParseState.READ_LENGTH_HEADER;
				break;
			case MhStreamRequestParseState.READ_LENGTH_HEADER:
				if(!buf.hasRemaining() || buf.limit() < 9) return;
				try {
					byte[] lengthData = new byte[9];
					buf.get(lengthData);
					state.payloadLength = Integer.parseInt(new String(lengthData));
					if(state.payloadLength > state.buffer.length) {
						throw new IllegalStateException("Invalid length header: Payload is too long");
					}
					state.state = MhStreamRequestParseState.READ_REQUEST_BODY;
				}
				catch(NumberFormatException e) {
					throw new IllegalStateException("Invalid length header: Header not numeric");
				}
				break;
			case MhStreamRequestParseState.READ_REQUEST_BODY:
				if(buf.hasRemaining()) {
					int length = state.buffer.length - state.bufferOffs;
					length = length < buf.remaining() ? length : buf.remaining();
					try {
						buf.get(state.buffer, state.bufferOffs, length);
						state.tqFunctionName = this.findTqFunction(state.buffer, state.payloadLength);
						exchange.setRequestScheme("http");
						exchange.setRequestMethod(new HttpString("POST"));
						String requestPath = String.format("/tq/%s", state.tqFunctionName);
						exchange.setRequestPath(requestPath);
						exchange.setResponseContentLength(state.payloadLength);
						exchange.setRequestURI(requestPath, false);
						break;
					}
					catch(BufferUnderflowException e) {
						state.state = MhStreamRequestParseState.READ_REQUEST_BODY;
						return;
					}
				}
				break;
			case MhStreamRequestParseState.CONSTRUCT_REQUEST:
				state.tqFunctionName = this.findTqFunction(state.buffer, state.payloadLength);
				exchange.setRequestScheme("http");
				exchange.setRequestMethod(new HttpString("POST"));
				String requestPath = String.format("/tq/%s", state.tqFunctionName);
				exchange.setRequestPath(requestPath);
				exchange.setResponseContentLength(state.payloadLength);
				exchange.setRequestURI(requestPath, false);
				state.state = MhStreamRequestParseState.COMPLETE;
				break;
		}
	}

	private String findTqFunction(byte[] inputBuffer, int bufferLength) {
		int offset = 0;
		int length = 200;
		byte[] buffer = new byte[length];
		StringBuilder searchString = new StringBuilder();
		while((bufferLength < (2 * TQ_FUNCTION_START_TAG.length())) && offset < inputBuffer.length) {
			int remaining = inputBuffer.length - offset < length ? inputBuffer.length - offset : length;
			System.arraycopy(inputBuffer, offset, buffer, 0, remaining);
			offset = offset + remaining;
			searchString.append(new String(buffer));
			length = 100;
			int indexStart = searchString.indexOf(TQ_FUNCTION_START_TAG);
			int indexEnd = -1;
			if(indexStart != -1) {
				indexStart += TQ_FUNCTION_START_TAG.length();
				indexEnd = searchString.indexOf("<", indexStart);
			}
			if(indexEnd != -1) {
				return searchString.substring(indexStart, indexEnd);
			}
		}
		return "__TQ_FUNCTION_NAME_NOT_SPECIFIED__";
	}
}
