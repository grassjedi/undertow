package io.undertow.server.protocol.mhstream;

import io.undertow.UndertowLogger;
import io.undertow.UndertowOptions;
import io.undertow.conduits.ConduitListener;
import io.undertow.conduits.EmptyStreamSourceConduit;
import io.undertow.conduits.ReadDataStreamSourceConduit;
import io.undertow.server.AbstractServerConnection;
import io.undertow.server.ConnectorStatisticsImpl;
import io.undertow.server.Connectors;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.protocol.ParseTimeoutUpdater;
import io.undertow.util.*;
import org.xnio.ChannelListener;
import org.xnio.Pooled;
import org.xnio.StreamConnection;
import org.xnio.channels.StreamSinkChannel;
import org.xnio.channels.StreamSourceChannel;
import org.xnio.conduits.ConduitStreamSinkChannel;
import org.xnio.conduits.ConduitStreamSourceChannel;
import org.xnio.conduits.StreamSourceConduit;
import org.xnio.conduits.WriteReadyHandler;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;

import static org.xnio.IoUtils.safeClose;

final class MhStreamReadListener implements ChannelListener<StreamSourceChannel> {

	private final MhStreamServerConnection connection;
	private final boolean recordRequestStartTime;
	private HttpServerExchange httpServerExchange;

	private volatile int read = 0;
	private final int maxRequestSize;
	private final long maxEntitySize;
	private final ConnectorStatisticsImpl connectorStatistics;
	private WriteReadyHandler.ChannelListenerHandler<ConduitStreamSinkChannel> writeReadyHandler;

	private ParseTimeoutUpdater parseTimeoutUpdater;

	MhStreamReadListener(
			final MhStreamServerConnection connection,
			ConnectorStatisticsImpl connectorStatistics) {
		this.connection = connection;
		this.connectorStatistics = connectorStatistics;
		this.maxRequestSize = connection.getUndertowOptions().get(UndertowOptions.MAX_HEADER_SIZE, 10 * 1024 * 1024);
		this.maxEntitySize = connection.getUndertowOptions().get(UndertowOptions.MAX_ENTITY_SIZE, 10 * 1024 * 1024);
		this.writeReadyHandler = new WriteReadyHandler.ChannelListenerHandler<>(connection.getChannel().getSinkChannel());
		this.recordRequestStartTime = connection.getUndertowOptions().get(UndertowOptions.RECORD_REQUEST_START_TIME, false);
		int requestParseTimeout = connection.getUndertowOptions().get(UndertowOptions.REQUEST_PARSE_TIMEOUT, -1);
		int requestIdleTimeout = connection.getUndertowOptions().get(UndertowOptions.NO_REQUEST_TIMEOUT, -1);
		if(requestIdleTimeout < 0 && requestParseTimeout < 0) {
			this.parseTimeoutUpdater = null;
		}
		else {
			this.parseTimeoutUpdater = new ParseTimeoutUpdater(connection, requestParseTimeout, requestIdleTimeout);
			connection.addCloseListener(parseTimeoutUpdater);
		}
	}

	public void startRequest() {
		connection.resetChannel();
		httpServerExchange = new HttpServerExchange(connection, maxEntitySize);
		read = 0;
		if(parseTimeoutUpdater != null) {
			parseTimeoutUpdater.connectionIdle();
		}
		connection.setCurrentExchange(null);
	}

	public void handleEvent(final StreamSourceChannel channel) {
		if(connection
				.getOriginalSinkConduit()
				.isWriteShutdown()
			|| connection
				.getOriginalSourceConduit()
				.isReadShutdown()) {
			safeClose(connection);
			channel.suspendReads();
			return;
		}
		Pooled<ByteBuffer> existing = connection.getExtraBytes();
		final Pooled<ByteBuffer> pooled = existing == null ? connection.getBufferPool().allocate() : existing;
		final ByteBuffer buffer = pooled.getResource();
		final Pooled<ByteBuffer> pooledRequestBody = connection.getBufferPool().allocate();
		final ByteBuffer requestBody = pooledRequestBody.getResource();
		boolean free = true;
		boolean bytesRead = false;
		try {
			int res;
			do {
				if(existing == null) {
					buffer.clear();
					try {
						res = channel.read(buffer);
					}
					catch(IOException e) {
						UndertowLogger.REQUEST_IO_LOGGER.ioException(e);
						safeClose(connection);
						return;
					}
				}
				else {
					res = buffer.remaining();
				}
				if(res == 0) {
					if(bytesRead && parseTimeoutUpdater != null) {
						parseTimeoutUpdater.failedParse();
					}
					if(!channel.isReadResumed()) {
						channel.getReadSetter().set(this);
						channel.resumeReads();
					}
					return;
				}
				if(res == -1) {
					try {
						channel.shutdownReads();
						final StreamSinkChannel responseChannel = connection.getChannel().getSinkChannel();
						responseChannel.shutdownWrites();
						safeClose(connection);
					}
					catch(IOException e) {
						UndertowLogger.REQUEST_IO_LOGGER.ioException(e);
						safeClose(connection);
						return;
					}
					return;
				}
				bytesRead = true;
				if(existing != null) {
					existing = null;
					connection.setExtraBytes(null);
				}
				else {
					buffer.flip();
				}
				int begin = buffer.remaining();
				//decode using the platform charset
				CharsetDecoder decoder = Charset.defaultCharset().newDecoder();
				if(begin >= 9) {
					ByteBuffer temp = buffer.slice();
					temp.limit(9);
					CharBuffer decodedLengthChars = decoder.decode(temp);
					Integer.parseInt(decodedLengthChars.toString());
				}

				read += begin - buffer.remaining();
				if(buffer.hasRemaining()) {
					free = false;
					connection.setExtraBytes(pooled);
				}
				if(read > maxRequestSize) {
					UndertowLogger.REQUEST_LOGGER.requestHeaderWasTooLarge(connection.getPeerAddress(), maxRequestSize);
					safeClose(connection);
					return;
				}
			} while(true);
			System.out.println();
			if(parseTimeoutUpdater != null) {
				parseTimeoutUpdater.requestStarted();
			}

			// we remove ourselves as the read listener from the channel;
			// if the http handler doesn't set any then reads will suspend, which is the right thing to do
			channel.getReadSetter().set(null);
			channel.suspendReads();

			final HttpServerExchange httpServerExchange = this.httpServerExchange;
			httpServerExchange.setRequestMethod(Methods.POST);
			httpServerExchange.setRequestScheme("http");
			httpServerExchange.setProtocol(Protocols.HTTP_1_1);
			httpServerExchange.setRequestURI("/tq/%s");
			HeaderMap requestHeaders = httpServerExchange.getRequestHeaders();
			requestHeaders.add(Headers.CONTENT_TYPE, "text/plain");
			requestHeaders.add(Headers.ACCEPT, "application/xml");



			final MhStreamServerResponseConduit responseConduit =
					new MhStreamServerResponseConduit(
							connection.getChannel().getSinkChannel().getConduit(),
							connection.getBufferPool(),
							httpServerExchange,
							new ConduitListener<MhStreamServerResponseConduit>() {
								@Override
								public void handleEvent(MhStreamServerResponseConduit channel) {
									Connectors.terminateResponse(httpServerExchange);
								}
							}, httpServerExchange.getRequestMethod().equals(Methods.HEAD));
			connection.getChannel().getSinkChannel().setConduit(responseConduit);
			connection.getChannel().getSourceChannel().setConduit(createSourceConduit(connection.getChannel().getSourceChannel().getConduit(), responseConduit, httpServerExchange));
			//we need to set the write ready handler. This allows the response conduit to wrap it
			responseConduit.setWriteReadyHandler(writeReadyHandler);

			try {
				connection.setSSLSessionInfo(null);
				httpServerExchange.setSourceAddress(connection.getPeerAddress(InetSocketAddress.class));
				httpServerExchange.setDestinationAddress(connection.getLocalAddress(InetSocketAddress.class));
				httpServerExchange.setRequestScheme("http");
				this.httpServerExchange = null;
				httpServerExchange.setPersistent(true);
				if(recordRequestStartTime) {
					Connectors.setRequestStartTime(httpServerExchange);
				}
				connection.setCurrentExchange(httpServerExchange);
				if(connectorStatistics != null) {
					connectorStatistics.setup(httpServerExchange);
				}
				Connectors.executeRootHandler(connection.getRootHandler(), httpServerExchange);
			}
			catch(Throwable t) {
				UndertowLogger.REQUEST_LOGGER.exceptionProcessingRequest(t);
				safeClose(connection);
			}
		}
		catch(Exception e) {
			UndertowLogger.REQUEST_LOGGER.exceptionProcessingRequest(e);
			safeClose(connection);
		}
		finally {
			if(free) pooled.free();
		}
	}

	public void exchangeComplete(final HttpServerExchange exchange) {
		if(!exchange.isUpgrade() && exchange.isPersistent()) {
			startRequest();
			ConduitStreamSourceChannel channel = ((MhStreamServerConnection) exchange.getConnection()).getChannel().getSourceChannel();
			channel.getReadSetter().set(this);
			channel.wakeupReads();
		}
		else if(!exchange.isPersistent()) {
			safeClose(exchange.getConnection());
		}
	}

	private StreamSourceConduit createSourceConduit(
			StreamSourceConduit underlyingConduit,
			MhStreamServerResponseConduit responseConduit,
			final HttpServerExchange exchange) {

		ReadDataStreamSourceConduit conduit = new ReadDataStreamSourceConduit(underlyingConduit, (AbstractServerConnection) exchange.getConnection());

		final HeaderMap requestHeaders = exchange.getRequestHeaders();
		HttpString transferEncoding = Headers.IDENTITY;
		Long length;
		final String teHeader = requestHeaders.getLast(Headers.TRANSFER_ENCODING);
		boolean hasTransferEncoding = teHeader != null;
		if(hasTransferEncoding) {
			transferEncoding = new HttpString(teHeader);
		}
		final String requestContentLength = requestHeaders.getFirst(Headers.CONTENT_LENGTH);
		if(hasTransferEncoding && !transferEncoding.equals(Headers.IDENTITY)) {
			length = null; //unknown length
		}
		else if(requestContentLength != null) {
			final long contentLength = Long.parseLong(requestContentLength);
			if(contentLength == 0L) {
				UndertowLogger.REQUEST_LOGGER.trace("No content, starting next request");
				// no content - immediately start the next request, returning an empty stream for this one
				Connectors.terminateRequest(httpServerExchange);
				return new EmptyStreamSourceConduit(conduit.getReadThread());
			}
			else {
				length = contentLength;
			}
		}
		else {
			UndertowLogger.REQUEST_LOGGER.trace("No content length or transfer coding, starting next request");
			// no content - immediately start the next request, returning an empty stream for this one
			Connectors.terminateRequest(exchange);
			return new EmptyStreamSourceConduit(conduit.getReadThread());
		}
		return new MhStreamServerRequestConduit(conduit, exchange, responseConduit, length, new ConduitListener<MhStreamServerRequestConduit>() {
			@Override
			public void handleEvent(MhStreamServerRequestConduit channel) {
				Connectors.terminateRequest(exchange);
			}
		});
	}
}
