/*******************************************************************************
 * Copyright (c) 2015, 2016 Institute for Pervasive Computing, ETH Zurich and others.
 * 
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v1.0 which accompany this distribution.
 * 
 * The Eclipse Public License is available at
 *    http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 *    http://www.eclipse.org/org/documents/edl-v10.html.
 * 
 * Contributors:
 *    Matthias Kovatsch - creator and main architect
 *    Martin Lanter - architect and re-implementation
 *    Dominique Im Obersteg - parsers and initial implementation
 *    Daniel Pauli - parsers and initial implementation
 *    Kai Hudalla (Bosch Software Innovations GmbH) - logging
 *    Kai Hudalla (Bosch Software Innovations GmbH) - include client identity in Requests
 *                                                    (465073)
 *    Kai Hudalla (Bosch Software Innovations GmbH) - use static reference to Serializer
 *    Kai Hudalla (Bosch Software Innovations GmbH) - use Logger's message formatting instead of
 *                                                    explicit String concatenation
 *    Bosch Software Innovations GmbH - use correlation context to improve matching
 *                                      of Response(s) to Request (fix GitHub issue #1)
 *    Bosch Software Innovations GmbH - adapt message parsing error handling
 *    Joe Magerramov (Amazon Web Services) - CoAP over TCP support.
 *    Bosch Software Innovations GmbH - adjust request scheme for TCP
 ******************************************************************************/
package org.eclipse.californium.core.network;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.californium.core.Utils;
import org.eclipse.californium.core.coap.CoAP;
import org.eclipse.californium.core.coap.CoAP.Type;
import org.eclipse.californium.core.coap.EmptyMessage;
import org.eclipse.californium.core.coap.Message;
import org.eclipse.californium.core.coap.MessageFormatException;
import org.eclipse.californium.core.coap.Request;
import org.eclipse.californium.core.coap.Response;
import org.eclipse.californium.core.network.EndpointManager.ClientMessageDeliverer;
import org.eclipse.californium.core.network.config.NetworkConfig;
import org.eclipse.californium.core.network.interceptors.MessageInterceptor;
import org.eclipse.californium.core.network.serialization.DataParser;
import org.eclipse.californium.core.network.serialization.DataSerializer;
import org.eclipse.californium.core.network.serialization.TcpDataParser;
import org.eclipse.californium.core.network.serialization.TcpDataSerializer;
import org.eclipse.californium.core.network.serialization.UdpDataParser;
import org.eclipse.californium.core.network.serialization.UdpDataSerializer;
import org.eclipse.californium.core.network.stack.BlockwiseLayer;
import org.eclipse.californium.core.network.stack.CoapStack;
import org.eclipse.californium.core.network.stack.CoapTcpStack;
import org.eclipse.californium.core.network.stack.CoapUdpStack;
import org.eclipse.californium.core.network.stack.ObserveLayer;
import org.eclipse.californium.core.network.stack.ReliabilityLayer;
import org.eclipse.californium.core.observe.InMemoryObservationStore;
import org.eclipse.californium.core.observe.NotificationListener;
import org.eclipse.californium.core.observe.ObservationStore;
import org.eclipse.californium.core.server.MessageDeliverer;
import org.eclipse.californium.elements.Connector;
import org.eclipse.californium.elements.CorrelationContext;
import org.eclipse.californium.elements.MessageCallback;
import org.eclipse.californium.elements.RawData;
import org.eclipse.californium.elements.RawDataChannel;
import org.eclipse.californium.elements.UDPConnector;


/**
 * Endpoint encapsulates the stack that executes the CoAP protocol. Endpoint
 * forwards incoming messages to a {@link MessageDeliverer}. The deliverer will
 * deliver requests to its destination resource. The resource sends the response
 * back over the same endpoint. The endpoint sends outgoing messages over a
 * connector. The connector encapsulates the transport protocol.
 * <p>
 * The CoAP Draft 18 describes an endpoint as: "A CoAP Endpoint is is identified
 * by transport layer multiplexing information that can include a UDP port
 * number and a security association." (draft-ietf-core-coap-14: 1.2)
 * <p>
 * The following diagram describes the structure of an endpoint. The endpoint
 * implements CoAP in layers. Incoming and outgoing messages always travel from
 * layer to layer. An {@link Exchange} represents the known state about the
 * exchange between a request and one or more corresponding responses. The
 * matcher remembers outgoing messages and matches incoming responses, acks and
 * rsts to them. MessageInterceptors receive every incoming and outgoing
 * message. By default, only one interceptor is used to log messages.
 * 
 * <pre>
 * +-----------------------+
 * |   {@link MessageDeliverer}    +--&gt; (Resource Tree)
 * +-------------A---------+
 *               |
 *             * A            
 * +-Endpoint--+-A---------+
 * |           v A         |
 * |           v A         |
 * | +---------v-+-------+ |
 * | | Stack Top         | |
 * | +-------------------+ |
 * | | {@link ObserveLayer}      | |
 * | +-------------------+ |
 * | | {@link BlockwiseLayer}    | |
 * | +-------------------+ |
 * | | {@link ReliabilityLayer}  | |
 * | +-------------------+ |
 * | | Stack Bottom      | |
 * | +--------+-+--------+ |
 * |          v A          |
 * |          v A          |
 * |        {@link Matcher}        |
 * |          v A          |
 * |   {@link MessageInterceptor}  |  
 * |          v A          |
 * |          v A          |
 * | +--------v-+--------+ |
 * +-|     {@link Connector}     |-+
 *   +--------+-A--------+
 *            v A
 *            v A
 *         (Network)
 * </pre>
 * <p>
 * The endpoint and its layers use an {@link ScheduledExecutorService} to
 * execute tasks, e.g., when a request arrives.
 */
public class CoapEndpoint implements Endpoint {
	
	/** the logger. */
	private static final Logger LOGGER = Logger.getLogger(CoapEndpoint.class.getCanonicalName());
	
	/** The stack of layers that make up the CoAP protocol */
	private final CoapStack coapstack;
	
	/** The connector over which the endpoint connects to the network */
	private final Connector connector;
	
	private final String scheme;
	
	private final String secureScheme;
	
	/** The configuration of this endpoint */
	private final NetworkConfig config;
	
	/** The matcher which matches incoming responses, akcs and rsts an exchange */
	private final Matcher matcher;

	/** Serializer to convert messages to datagrams. */
	private final DataSerializer serializer;

	/** Parser to convert datagrams to messages. */
	private final DataParser parser;

	/** The executor to run tasks for this endpoint and its layers */
	private ScheduledExecutorService executor;
	
	/** Indicates if the endpoint has been started */
	private boolean started;
	
	/** The list of endpoint observers (has nothing to do with CoAP observe relations) */
	private List<EndpointObserver> observers = new CopyOnWriteArrayList<>();
	
	/** The list of interceptors */
	private List<MessageInterceptor> interceptors = new CopyOnWriteArrayList<>();

	/** The list of Notification listener (use for CoAP observer relations) */
	private List<NotificationListener> notificationListeners = new CopyOnWriteArrayList<>();

	private final MessageExchangeStore exchangeStore;

	/**
	 * Creates a new <em>coap</em> endpoint using default configuration.
	 * <p>
	 * The endpoint will bind to all network interfaces and listen on an ephemeral port.
	 */
	public CoapEndpoint() {
		this(0);
	}

	/**
	 * Creates a new <em>coap</em> endpoint using default configuration.
	 * <p>
	 * The endpoint will bind to all network interfaces.
	 *
	 * @param port The port to listen on.
	 */
	public CoapEndpoint(final int port) {
		this(new InetSocketAddress(port));
	}

	/**
	 * Creates a new <em>coap</em> endpoint using default configuration.
	 *
	 * @param address The IP address and port to bind to.
	 */
	public CoapEndpoint(final InetSocketAddress address) {
		this(address, NetworkConfig.getStandard());
	}

	/**
	 * Creates a new <em>coap</em> endpoint for a configuration.
	 * <p>
	 * The endpoint will bind to all network interfaces and listen on an ephemeral port.
	 * 
	 * @param config The configuration values to use.
	 */
	public CoapEndpoint(final NetworkConfig config) {
		this(new InetSocketAddress(0), config);
	}

	/**
	 * Creates a new <em>coap</em> endpoint for a port and configuration.
	 * <p>
	 * The endpoint will bind to all network interfaces and listen on an ephemeral port.
	 *
	 * @param port The port to listen on.
	 * @param config The configuration values to use.
	 */
	public CoapEndpoint(final int port, final NetworkConfig config) {
		this(new InetSocketAddress(port), config);
	}

	/**
	 * Creates a new <em>coap</em> endpoint for a configuration.
	 *
	 * @param address The IP address and port to bind to.
	 * @param config The configuration values to use.
	 */
	public CoapEndpoint(final InetSocketAddress address, final NetworkConfig config) {
		this(createUDPConnector(address, config), config, null, null);
	}

	/**
	 * Creates a new <em>coap</em> endpoint for a configuration and message exchange store.
	 *
	 * @param address The IP address and port to bind to.
	 * @param config The configuration values to use.
	 * @param exchangeStore The store to use for keeping track of message exchanges.
	 */
	public CoapEndpoint(final InetSocketAddress address, final NetworkConfig config, final MessageExchangeStore exchangeStore) {
		this(createUDPConnector(address, config), config, null, exchangeStore);
	}

	/**
	 * Creates a new endpoint for a connector and configuration.
	 * <p>
	 * The endpoint will support the connector's implemented scheme and will bind to
	 * the IP address and port the connector is configured for.
	 * 
	 * @param connector The connector to use.
	 * @param config The configuration values to use.
	 */
	public CoapEndpoint(final Connector connector, final NetworkConfig config) {
		this(connector, config, null, null);
	}

	/**
	 * Creates a new <em>coap</em> endpoint for a configuration and observation store.
	 * 
	 * @param address The IP address and port to bind to.
	 * @param config The configuration values to use.
	 * @param store The store to use for keeping track of observations initiated by this
	 *              endpoint.
	 */
	public CoapEndpoint(final InetSocketAddress address, final NetworkConfig config, final ObservationStore store) {
		this(createUDPConnector(address, config), config, store, null);
	}

	/**
	 * Creates a new endpoint for a connector, configuration, message exchange and observation store.
	 * <p>
	 * The endpoint will support the connector's implemented scheme and will bind to
	 * the IP address and port the connector is configured for.
	 *
	 * @param connector The connector to use.
	 * @param config The configuration values to use.
	 * @param store The store to use for keeping track of observations initiated by this
	 *              endpoint.
	 * @param exchangeStore The store to use for keeping track of message exchanges.
	 */
	public CoapEndpoint(Connector connector, NetworkConfig config, ObservationStore store, MessageExchangeStore exchangeStore) {
		this.config = config;
		this.connector = connector;
		this.connector.setRawDataReceiver(new InboxImpl());
		ObservationStore observationStore = store != null ? store : new InMemoryObservationStore();
		this.exchangeStore = exchangeStore;

		if (connector.isSchemeSupported(CoAP.COAP_TCP_URI_SCHEME) ||
				connector.isSchemeSupported(CoAP.COAP_SECURE_TCP_URI_SCHEME)) {
			this.matcher = new TcpMatcher(config, new NotificationDispatcher(), observationStore);
			this.coapstack = new CoapTcpStack(config, new OutboxImpl());
			this.serializer = new TcpDataSerializer();
			this.parser = new TcpDataParser();
			this.scheme = CoAP.COAP_TCP_URI_SCHEME;
			this.secureScheme = CoAP.COAP_SECURE_TCP_URI_SCHEME;
		} else {
			this.matcher = new UdpMatcher(config, new NotificationDispatcher(), observationStore);
			this.coapstack = new CoapUdpStack(config, new OutboxImpl());
			this.serializer = new UdpDataSerializer();
			this.parser = new UdpDataParser();
			this.scheme = CoAP.COAP_URI_SCHEME;
			this.secureScheme = CoAP.COAP_SECURE_URI_SCHEME;
		}
	}

	/**
	 * Creates a new UDP connector.
	 *
	 * @param address the address
	 * @param config the configuration
	 * @return the connector
	 */
	private static Connector createUDPConnector(final InetSocketAddress address, final NetworkConfig config) {
		UDPConnector c = new UDPConnector(address);

		c.setReceiverThreadCount(config.getInt(NetworkConfig.Keys.NETWORK_STAGE_RECEIVER_THREAD_COUNT));
		c.setSenderThreadCount(config.getInt(NetworkConfig.Keys.NETWORK_STAGE_SENDER_THREAD_COUNT));

		c.setReceiveBufferSize(config.getInt(NetworkConfig.Keys.UDP_CONNECTOR_RECEIVE_BUFFER));
		c.setSendBufferSize(config.getInt(NetworkConfig.Keys.UDP_CONNECTOR_SEND_BUFFER));
		c.setReceiverPacketSize(config.getInt(NetworkConfig.Keys.UDP_CONNECTOR_DATAGRAM_SIZE));

		return c;
	}

	@Override
	public synchronized void start() throws IOException {
		if (started) {
			LOGGER.log(Level.FINE, "Endpoint at {0} is already started", getUri());
			return;
		}

		if (!this.coapstack.hasDeliverer()) {
			setMessageDeliverer(new ClientMessageDeliverer());
		}

		if (this.executor == null) {
			LOGGER.log(Level.CONFIG, "Endpoint [{0}] requires an executor to start, using default single-threaded daemon executor", getUri());

			// in production environments the executor should be set to a multi threaded version
			// in order to utilize all cores of the processor
			setExecutor(Executors.newSingleThreadScheduledExecutor(
					new Utils.DaemonThreadFactory("CoapEndpoint-" + connector.getUri() + '#'))); //$NON-NLS-1$
			addObserver(new EndpointObserver() {
				@Override
				public void started(final Endpoint endpoint) {
					// do nothing
				}
				@Override
				public void stopped(final Endpoint endpoint) {
					// do nothing
				}
				@Override
				public void destroyed(final Endpoint endpoint) {
					executor.shutdown();
				}
			});
		}

		if (exchangeStore == null) {
			matcher.setMessageExchangeStore(new InMemoryMessageExchangeStore(config));
		} else {
			matcher.setMessageExchangeStore(exchangeStore);
		}

		try {
			LOGGER.log(Level.INFO, "Starting endpoint at {0}", getUri());

			started = true;
			matcher.start();
			connector.start();
			for (EndpointObserver obs : observers) {
				obs.started(this);
			}
			startExecutor();
			LOGGER.log(Level.INFO, "Started endpoint at {0}", getUri());
		} catch (IOException e) {
			// free partially acquired resources
			stop();
			throw e;
		}
	}

	/**
	 * Makes sure that the executor has started, i.e., a thread has been
	 * created. This is necessary for the server because it makes sure a
	 * non-daemon thread is running. Otherwise the program might find that only
	 * daemon threads are running and exit.
	 */
	private void startExecutor() {
		// Run a task that does nothing but make sure at least one thread of
		// the executor has started.
		runInProtocolStage(new Runnable() {
			@Override
			public void run() {
				// do nothing
			}
		});
	}

	@Override
	public synchronized void stop() {
		if (!started) {
			LOGGER.log(Level.INFO, "Endpoint at {0} is already stopped", getUri());
		} else {
			LOGGER.log(Level.INFO, "Stopping endpoint at address {0}", getUri());
			started = false;
			connector.stop();
			matcher.stop();
			for (EndpointObserver obs : observers) {
				obs.stopped(this);
			}
			matcher.clear();
		}
	}

	@Override
	public synchronized void destroy() {
		LOGGER.log(Level.INFO, "Destroying endpoint at address {0}", getUri());
		if (started) {
			stop();
		}
		connector.destroy();
		coapstack.destroy();
		for (EndpointObserver obs : observers) {
			obs.destroyed(this);
		}
	}

	@Override
	public void clear() {
		matcher.clear();
	}

	@Override
	public synchronized boolean isStarted() {
		return started;
	}

	@Override
	public synchronized void setExecutor(final ScheduledExecutorService executor) {
		// TODO: don't we need to stop and shut down the previous executor?
		this.executor = executor;
		this.coapstack.setExecutor(executor);
	}

	@Override
	public void addNotificationListener(final NotificationListener lis) {
		notificationListeners.add(lis);
	}

	@Override
	public void removeNotificationListener(final NotificationListener lis) {
		notificationListeners.remove(lis);
	}

	@Override
	public void addObserver(final EndpointObserver observer) {
		observers.add(observer);
	}

	@Override
	public void removeObserver(final EndpointObserver observer) {
		observers.remove(observer);
	}

	@Override
	public void addInterceptor(final MessageInterceptor interceptor) {
		interceptors.add(interceptor);
	}

	@Override
	public void removeInterceptor(final MessageInterceptor interceptor) {
		interceptors.remove(interceptor);
	}

	@Override
	public List<MessageInterceptor> getInterceptors() {
		return Collections.unmodifiableList(interceptors);
	}

	@Override
	public void sendRequest(final Request request) {
		// always use endpoint executor
		runInProtocolStage(new Runnable() {
			@Override
			public void run() {
				coapstack.sendRequest(request);
			}
		});
	}

	@Override
	public void sendResponse(final Exchange exchange, final Response response) {
		if (exchange.hasCustomExecutor()) {
			// handle sending by protocol stage instead of business logic stage
			runInProtocolStage(new Runnable() {
				@Override
				public void run() {
					coapstack.sendResponse(exchange, response);
				}
			});
		} else {
			// use same thread to save switching overhead
			coapstack.sendResponse(exchange, response);
		}
	}

	@Override
	public void sendEmptyMessage(final Exchange exchange, final EmptyMessage message) {
		// send empty messages right away in the same thread to ensure execution order
		// of CoapExchange.accept() / .reject() and similar cases.
		coapstack.sendEmptyMessage(exchange, message);
	}

	/**
	 * Sets a processor for incoming requests and responses to.
	 * <p>
	 * Incoming responses that represent notifications for observations 
	 * will also be forwarded to all notification listeners.
	 * </p>
	 * 
	 *  @param deliverer the processor to deliver messages to.
	 *  @throws NullPointerException if the given deliverer is {@code null}
	 */
	@Override
	public void setMessageDeliverer(MessageDeliverer deliverer) {
		coapstack.setDeliverer(deliverer);
	}

	@Override
	public InetSocketAddress getAddress() {
		return connector.getAddress();
	}

	@Override
	public URI getUri() {
		return connector.getUri();
	}

	@Override
	public NetworkConfig getConfig() {
		return config;
	}

	private class NotificationDispatcher implements NotificationListener {
		@Override
		public void onNotification(final Request request, final Response response) {

			// we can rely on the fact that the CopyOnWriteArrayList just provides a
			// "snapshot" iterator over the notification listeners
			for (NotificationListener notificationListener : notificationListeners) {
				notificationListener.onNotification(request, response);
			}
		}
	}

	/**
	 * The stack of layers uses this Outbox to send messages. The OutboxImpl
	 * will then give them to the matcher, the interceptors, and finally send
	 * them over the connector.
	 */
	private class OutboxImpl implements Outbox {

		@Override
		public void sendRequest(final Exchange exchange, final Request request) {

			assertMessageHasDestinationAddress(request);
			matcher.sendRequest(exchange, request);

			/* 
			 * Logging here causes significant performance loss.
			 * If necessary, add an interceptor that logs the messages,
			 * e.g., the MessageTracer.
			 */

			for (MessageInterceptor messageInterceptor : interceptors) {
				messageInterceptor.sendRequest(request);
			}

			// One of the interceptors may have cancelled the request
			if (!request.isCanceled()) {
				// create callback for setting correlation context
				MessageCallback callback = new MessageCallback() {

					@Override
					public void onContextEstablished(final CorrelationContext context) {
						exchange.setCorrelationContext(context);
					}
				};
				RawData message = serializer.serializeRequest(request, callback);
				connector.send(message);
			}
		}

		@Override
		public void sendResponse(Exchange exchange, Response response) {

			assertMessageHasDestinationAddress(response);
			matcher.sendResponse(exchange, response);

			/* 
			 * Logging here causes significant performance loss.
			 * If necessary, add an interceptor that logs the messages,
			 * e.g., the MessageTracer.
			 */
			for (MessageInterceptor interceptor:interceptors) {
				interceptor.sendResponse(response);
			}

			// MessageInterceptor might have canceled
			if (!response.isCanceled()) {
				connector.send(serializer.serializeResponse(response));
			}
		}

		@Override
		public void sendEmptyMessage(Exchange exchange, EmptyMessage message) {

			assertMessageHasDestinationAddress(message);
			matcher.sendEmptyMessage(exchange, message);

			/* 
			 * Logging here causes significant performance loss.
			 * If necessary, add an interceptor that logs the messages,
			 * e.g., the MessageTracer.
			 */
			for (MessageInterceptor interceptor:interceptors) {
				interceptor.sendEmptyMessage(message);
			}

			// MessageInterceptor might have canceled
			if (!message.isCanceled()) {
				connector.send(serializer.serializeEmptyMessage(message));
			}
		}

		private void assertMessageHasDestinationAddress(final Message message) {
			if (message.getDestination() == null) {
				throw new IllegalArgumentException("Message has no destination address");
			} else if (message.getDestinationPort() == 0) {
				throw new IllegalArgumentException("Message has no destination port");
			}
		}
	}

	/**
	 * The connector uses this channel to forward messages (in form of
	 * {@link RawData}) to the endpoint. The endpoint creates a new task to
	 * process the message. The task consists of invoking the matcher to look
	 * for an associated exchange and then forwards the message with the
	 * exchange to the stack of layers.
	 */
	private class InboxImpl implements RawDataChannel {

		@Override
		public void receiveData(final RawData raw) {
			if (raw.getAddress() == null) {
				throw new IllegalArgumentException("received message that does not have a source address");
			} else if (raw.getPort() == 0) {
				throw new IllegalArgumentException("received message that does not have a source port");
			} else {

				// Create a new task to process this message
				runInProtocolStage(new Runnable() {
					@Override
					public void run() {
						receiveMessage(raw);
					}
				});
			}
		}

		/*
		 * The endpoint's executor executes this method to convert the raw bytes
		 * into a message, look for an associated exchange and forward it to
		 * the stack of layers. If the message is a CON and cannot be parsed,
		 * e.g. because the message is malformed, an RST is sent back to the sender.
		 */
		private void receiveMessage(final RawData raw) {

			Message msg = null;

			try {
				msg = parser.parseMessage(raw);
				msg.setSource(raw.getAddress());
				msg.setSourcePort(raw.getPort());

				if (CoAP.isRequest(msg.getRawCode())) {

					receiveRequest((Request) msg, raw);

				} else if (CoAP.isResponse(msg.getRawCode())) {

					receiveResponse((Response) msg, raw);

				} else if (CoAP.isEmptyMessage(msg.getRawCode())) {

					receiveEmptyMessage((EmptyMessage) msg, raw);

				} else {
					LOGGER.log(Level.FINER, "Silently ignoring non-CoAP message from {0}", raw.getInetSocketAddress());
				}

			} catch (MessageFormatException e) {
				if (e.isConfirmable() && e.hasMid()) {
					// reject erroneous reliably transmitted message as mandated by CoAP spec
					// https://tools.ietf.org/html/rfc7252#section-4.2
					reject(raw, e);
					LOGGER.log(
							Level.FINE,
							"rejected malformed message from [{0}], reason: {1}",
							new Object[]{raw.getInetSocketAddress(), e.getMessage()});
				} else {
					// ignore erroneous messages that are not transmitted reliably
					LOGGER.log(Level.FINER, "discarding malformed message from [{0}]", raw.getInetSocketAddress());
				}
			}
		}

		private void reject(final RawData raw, final MessageFormatException cause) {
			// Generate RST, and send it if not cancelled by one of the interceptors
			EmptyMessage rst = new EmptyMessage(Type.RST);
			rst.setMID(cause.getMid());
			rst.setDestination(raw.getAddress());
			rst.setDestinationPort(raw.getPort());

			coapstack.sendEmptyMessage(null, rst);
		}

		private void reject(final Message message) {
			EmptyMessage rst = EmptyMessage.newRST(message);
			coapstack.sendEmptyMessage(null, rst);
		}

		private void receiveRequest(final Request request, final RawData raw) {

			// set request attributes from raw data
			request.setScheme(raw.isSecure() ? secureScheme : scheme);
			request.setSenderIdentity(raw.getSenderIdentity());

			/* 
			 * Logging here causes significant performance loss.
			 * If necessary, add an interceptor that logs the messages,
			 * e.g., the MessageTracer.
			 */
			for (MessageInterceptor interceptor:interceptors) {
				interceptor.receiveRequest(request);
			}

			// MessageInterceptor might have canceled
			if (!request.isCanceled()) {
				Exchange exchange = matcher.receiveRequest(request);
				if (exchange != null) {
					exchange.setEndpoint(CoapEndpoint.this);
					coapstack.receiveRequest(exchange, request);
				}
			}
		}

		private void receiveResponse(final Response response, final RawData raw) {

			/* 
			 * Logging here causes significant performance loss.
			 * If necessary, add an interceptor that logs the messages,
			 * e.g., the MessageTracer.
			 */
			for (MessageInterceptor interceptor:interceptors) {
				interceptor.receiveResponse(response);
			}

			// MessageInterceptor might have canceled
			if (!response.isCanceled()) {
				Exchange exchange = matcher.receiveResponse(response, raw.getCorrelationContext());
				if (exchange != null) {
					exchange.setEndpoint(CoapEndpoint.this);
					response.setRTT(System.currentTimeMillis() - exchange.getTimestamp());
					coapstack.receiveResponse(exchange, response);
				} else if (response.getType() != Type.ACK) {
					LOGGER.log(Level.FINE, "Rejecting unmatchable response from {0}", raw.getInetSocketAddress());
					reject(response);
				}
			}
		}

		private void receiveEmptyMessage(final EmptyMessage message, final RawData raw) {

			/* 
			 * Logging here causes significant performance loss.
			 * If necessary, add an interceptor that logs the messages,
			 * e.g., the MessageTracer.
			 */
			for (MessageInterceptor interceptor:interceptors) {
				interceptor.receiveEmptyMessage(message);
			}

			// MessageInterceptor might have canceled
			if (!message.isCanceled()) {
				// CoAP Ping
				if (message.getType() == Type.CON || message.getType() == Type.NON) {
					LOGGER.log(Level.FINER, "responding to ping from {0}", raw.getInetSocketAddress());
					reject(message);
				} else {
					Exchange exchange = matcher.receiveEmptyMessage(message);
					if (exchange != null) {
						exchange.setEndpoint(CoapEndpoint.this);
						coapstack.receiveEmptyMessage(exchange, message);
					}
				}
			}
		}
	}

	@Override
	public void cancelObservation(byte[] token) {
		matcher.cancelObserve(token);
	}

	/**
	 * Execute the specified task on the endpoint's executor (protocol stage).
	 *
	 * @param task the task
	 */
	private void runInProtocolStage(final Runnable task) {
		executor.execute(new Runnable() {
			@Override
			public void run() {
				try {
					task.run();
				} catch (final Throwable t) {
					LOGGER.log(Level.SEVERE, String.format("Exception in protocol stage thread: %s", t.getMessage()), t);
				}
			}
		});
	}
}
