/*
 * Copyright 2002-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.integration.ip.tcp;

import static org.hamcrest.Matchers.instanceOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.EOFException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ServerSocketFactory;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mockito;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.core.serializer.DefaultDeserializer;
import org.springframework.core.serializer.DefaultSerializer;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.integration.MessageTimeoutException;
import org.springframework.integration.channel.QueueChannel;
import org.springframework.integration.ip.tcp.connection.AbstractClientConnectionFactory;
import org.springframework.integration.ip.tcp.connection.CachingClientConnectionFactory;
import org.springframework.integration.ip.tcp.connection.FailoverClientConnectionFactory;
import org.springframework.integration.ip.tcp.connection.TcpConnectionSupport;
import org.springframework.integration.ip.tcp.connection.TcpNetClientConnectionFactory;
import org.springframework.integration.ip.tcp.connection.TcpNioClientConnectionFactory;
import org.springframework.integration.support.MessageBuilder;
import org.springframework.integration.test.rule.Log4j2LevelAdjuster;
import org.springframework.integration.test.support.LongRunningIntegrationTest;
import org.springframework.integration.test.util.TestUtils;
import org.springframework.messaging.Message;
import org.springframework.messaging.PollableChannel;
import org.springframework.messaging.support.GenericMessage;

/**
 * @author Gary Russell
 * @author Artem Bilan
 *
 * @since 2.0
 */
public class TcpOutboundGatewayTests {

	private static final Log logger = LogFactory.getLog(TcpOutboundGatewayTests.class);

	@ClassRule
	public static LongRunningIntegrationTest longTests = new LongRunningIntegrationTest();

	@Rule
	public Log4j2LevelAdjuster adjuster = Log4j2LevelAdjuster.trace();


	@Test
	public void testGoodNetSingle() throws Exception {
		final CountDownLatch latch = new CountDownLatch(1);
		final AtomicBoolean done = new AtomicBoolean();
		final AtomicReference<ServerSocket> serverSocket = new AtomicReference<ServerSocket>();
		Executors.newSingleThreadExecutor().execute(() -> {
			try {
				ServerSocket server = ServerSocketFactory.getDefault().createServerSocket(0, 100);
				serverSocket.set(server);
				latch.countDown();
				List<Socket> sockets = new ArrayList<Socket>();
				int i = 0;
				while (true) {
					Socket socket = server.accept();
					ObjectInputStream ois = new ObjectInputStream(socket.getInputStream());
					ois.readObject();
					ObjectOutputStream oos = new ObjectOutputStream(socket.getOutputStream());
					oos.writeObject("Reply" + (i++));
					sockets.add(socket);
				}
			}
			catch (Exception e) {
				if (!done.get()) {
					e.printStackTrace();
				}
			}
		});
		assertTrue(latch.await(10000, TimeUnit.MILLISECONDS));
		AbstractClientConnectionFactory ccf = new TcpNetClientConnectionFactory("localhost",
				serverSocket.get().getLocalPort());
		ccf.setSerializer(new DefaultSerializer());
		ccf.setDeserializer(new DefaultDeserializer());
		ccf.setSoTimeout(10000);
		ccf.setSingleUse(true);
		ccf.start();
		TcpOutboundGateway gateway = new TcpOutboundGateway();
		gateway.setConnectionFactory(ccf);
		QueueChannel replyChannel = new QueueChannel();
		gateway.setRequiresReply(true);
		gateway.setOutputChannel(replyChannel);
		// check the default remote timeout
		assertEquals("10000", TestUtils.getPropertyValue(gateway, "remoteTimeoutExpression.literalValue"));
		gateway.setSendTimeout(123);
		gateway.setRemoteTimeout(60000);
		gateway.setSendTimeout(61000);
		// ensure this did NOT change the remote timeout
		assertEquals("60000", TestUtils.getPropertyValue(gateway, "remoteTimeoutExpression.literalValue"));
		gateway.setRequestTimeout(60000);
		for (int i = 100; i < 200; i++) {
			gateway.handleMessage(MessageBuilder.withPayload("Test" + i).build());
		}
		Set<String> replies = new HashSet<String>();
		for (int i = 100; i < 200; i++) {
			Message<?> m = replyChannel.receive(10000);
			assertNotNull(m);
			replies.add((String) m.getPayload());
		}
		for (int i = 0; i < 100; i++) {
			assertTrue(replies.remove("Reply" + i));
		}
		done.set(true);
		serverSocket.get().close();
	}

	@Test
	public void testGoodNetMultiplex() throws Exception {
		final CountDownLatch latch = new CountDownLatch(1);
		final AtomicBoolean done = new AtomicBoolean();
		final AtomicReference<ServerSocket> serverSocket = new AtomicReference<ServerSocket>();
		Executors.newSingleThreadExecutor().execute(() -> {
			try {
				ServerSocket server = ServerSocketFactory.getDefault().createServerSocket(0, 10);
				serverSocket.set(server);
				latch.countDown();
				int i = 0;
				Socket socket = server.accept();
				while (true) {
					ObjectInputStream ois = new ObjectInputStream(socket.getInputStream());
					ois.readObject();
					ObjectOutputStream oos = new ObjectOutputStream(socket.getOutputStream());
					oos.writeObject("Reply" + (i++));
				}
			}
			catch (Exception e) {
				if (!done.get()) {
					e.printStackTrace();
				}
			}
		});
		assertTrue(latch.await(10000, TimeUnit.MILLISECONDS));
		AbstractClientConnectionFactory ccf = new TcpNetClientConnectionFactory("localhost",
				serverSocket.get().getLocalPort());
		ccf.setSerializer(new DefaultSerializer());
		ccf.setDeserializer(new DefaultDeserializer());
		ccf.setSoTimeout(10000);
		ccf.setSingleUse(false);
		ccf.start();
		TcpOutboundGateway gateway = new TcpOutboundGateway();
		gateway.setConnectionFactory(ccf);
		QueueChannel replyChannel = new QueueChannel();
		gateway.setRequiresReply(true);
		gateway.setOutputChannel(replyChannel);
		for (int i = 100; i < 110; i++) {
			gateway.handleMessage(MessageBuilder.withPayload("Test" + i).build());
		}
		Set<String> replies = new HashSet<String>();
		for (int i = 100; i < 110; i++) {
			Message<?> m = replyChannel.receive(10000);
			assertNotNull(m);
			replies.add((String) m.getPayload());
		}
		for (int i = 0; i < 10; i++) {
			assertTrue(replies.remove("Reply" + i));
		}
		done.set(true);
		gateway.stop();
		serverSocket.get().close();
	}

	@Test
	public void testGoodNetTimeout() throws Exception {
		final CountDownLatch latch = new CountDownLatch(1);
		final AtomicBoolean done = new AtomicBoolean();
		final AtomicReference<ServerSocket> serverSocket = new AtomicReference<ServerSocket>();
		Executors.newSingleThreadExecutor().execute(() -> {
			try {
				ServerSocket server = ServerSocketFactory.getDefault().createServerSocket(0);
				serverSocket.set(server);
				latch.countDown();
				int i = 0;
				Socket socket = server.accept();
				while (true) {
					ObjectInputStream ois = new ObjectInputStream(socket.getInputStream());
					ois.readObject();
					ObjectOutputStream oos = new ObjectOutputStream(socket.getOutputStream());
					Thread.sleep(1000);
					oos.writeObject("Reply" + (i++));
				}
			}
			catch (Exception e) {
				if (!done.get()) {
					e.printStackTrace();
				}
			}
		});
		assertTrue(latch.await(10000, TimeUnit.MILLISECONDS));
		AbstractClientConnectionFactory ccf = new TcpNetClientConnectionFactory("localhost",
				serverSocket.get().getLocalPort());
		ccf.setSerializer(new DefaultSerializer());
		ccf.setDeserializer(new DefaultDeserializer());
		ccf.setSoTimeout(10000);
		ccf.setSingleUse(false);
		ccf.start();
		final TcpOutboundGateway gateway = new TcpOutboundGateway();
		gateway.setConnectionFactory(ccf);
		gateway.setRequestTimeout(1);
		QueueChannel replyChannel = new QueueChannel();
		gateway.setRequiresReply(true);
		gateway.setOutputChannel(replyChannel);
		@SuppressWarnings("unchecked")
		Future<Integer>[] results = (Future<Integer>[]) new Future<?>[2];
		for (int i = 0; i < 2; i++) {
			final int j = i;
			results[j] = (Executors.newSingleThreadExecutor().submit(() -> {
				gateway.handleMessage(MessageBuilder.withPayload("Test" + j).build());
				return 0;
			}));
		}
		Set<String> replies = new HashSet<String>();
		int timeouts = 0;
		for (int i = 0; i < 2; i++) {
			try {
				results[i].get();
			}
			catch (ExecutionException e) {
				if (timeouts > 0) {
					fail("Unexpected " + e.getMessage());
				}
				else {
					assertNotNull(e.getCause());
					assertTrue(e.getCause() instanceof MessageTimeoutException);
				}
				timeouts++;
				continue;
			}
			Message<?> m = replyChannel.receive(10000);
			assertNotNull(m);
			replies.add((String) m.getPayload());
		}
		if (timeouts < 1) {
			fail("Expected ExecutionException");
		}
		for (int i = 0; i < 1; i++) {
			assertTrue(replies.remove("Reply" + i));
		}
		done.set(true);
		gateway.stop();
		serverSocket.get().close();
	}

	@Test
	public void testGoodNetGWTimeout() throws Exception {
		ServerSocket serverSocket = ServerSocketFactory.getDefault().createServerSocket(0);
		final int port = serverSocket.getLocalPort();
		AbstractClientConnectionFactory ccf = buildCF(port);
		ccf.start();
		testGoodNetGWTimeoutGuts(port, ccf, serverSocket);
		serverSocket.close();
	}

	@Test
	public void testGoodNetGWTimeoutCached() throws Exception {
		ServerSocket serverSocket = ServerSocketFactory.getDefault().createServerSocket(0);
		final int port = serverSocket.getLocalPort();
		AbstractClientConnectionFactory ccf = buildCF(port);
		CachingClientConnectionFactory cccf = new CachingClientConnectionFactory(ccf, 1);
		cccf.start();
		testGoodNetGWTimeoutGuts(port, cccf, serverSocket);
		serverSocket.close();
	}

	private AbstractClientConnectionFactory buildCF(final int port) {
		AbstractClientConnectionFactory ccf = new TcpNetClientConnectionFactory("localhost", port);
		ccf.setSerializer(new DefaultSerializer());
		ccf.setDeserializer(new DefaultDeserializer());
		ccf.setSoTimeout(10000);
		ccf.setSingleUse(false);
		return ccf;
	}

	/**
	 * Sends 2 concurrent messages on a shared connection. The GW single threads
	 * these requests. The first will timeout; the second should receive its
	 * own response, not that for the first.
	 * @throws Exception
	 */
	private void testGoodNetGWTimeoutGuts(final int port, AbstractClientConnectionFactory ccf,
			final ServerSocket server) throws InterruptedException {
		final CountDownLatch latch = new CountDownLatch(1);
		final AtomicBoolean done = new AtomicBoolean();
		/*
		 * The payload of the last message received by the remote side;
		 * used to verify the correct response is received.
		 */
		final AtomicReference<String> lastReceived = new AtomicReference<String>();
		final CountDownLatch serverLatch = new CountDownLatch(2);

		Executors.newSingleThreadExecutor().execute(() -> {
			try {
				latch.countDown();
				int i = 0;
				while (!done.get()) {
					Socket socket = server.accept();
					i++;
					while (!socket.isClosed()) {
						try {
							ObjectInputStream ois = new ObjectInputStream(socket.getInputStream());
							String request = (String) ois.readObject();
							logger.debug("Read " + request);
							ObjectOutputStream oos = new ObjectOutputStream(socket.getOutputStream());
							if (i < 2) {
								Thread.sleep(2000);
							}
							oos.writeObject(request.replace("Test", "Reply"));
							logger.debug("Replied to " + request);
							lastReceived.set(request);
							serverLatch.countDown();
						}
						catch (IOException e1) {
							logger.debug("error on write " + e1.getClass().getSimpleName());
							socket.close();
						}
					}
				}
			}
			catch (Exception e2) {
				if (!done.get()) {
					e2.printStackTrace();
				}
			}
		});
		assertTrue(latch.await(10000, TimeUnit.MILLISECONDS));
		final TcpOutboundGateway gateway = new TcpOutboundGateway();
		gateway.setConnectionFactory(ccf);
		gateway.setRequestTimeout(Integer.MAX_VALUE);
		QueueChannel replyChannel = new QueueChannel();
		gateway.setRequiresReply(true);
		gateway.setOutputChannel(replyChannel);

		Expression remoteTimeoutExpression = Mockito.mock(Expression.class);

		when(remoteTimeoutExpression.getValue(Mockito.any(EvaluationContext.class), Mockito.any(Message.class),
				Mockito.eq(Long.class))).thenReturn(50L, 10000L);

		gateway.setRemoteTimeoutExpression(remoteTimeoutExpression);

		@SuppressWarnings("unchecked")
		Future<Integer>[] results = (Future<Integer>[]) new Future<?>[2];

		for (int i = 0; i < 2; i++) {
			final int j = i;
			results[j] = (Executors.newSingleThreadExecutor().submit(() -> {
				gateway.handleMessage(MessageBuilder.withPayload("Test" + j).build());
				return j;
			}));

		}
		// wait until the server side has processed both requests
		assertTrue(serverLatch.await(30, TimeUnit.SECONDS));
		List<String> replies = new ArrayList<String>();
		int timeouts = 0;
		for (int i = 0; i < 2; i++) {
			try {
				int result = results[i].get();
				String reply = (String) replyChannel.receive(1000).getPayload();
				logger.debug(i + " got " + result + " " + reply);
				replies.add(reply);
			}
			catch (ExecutionException e) {
				if (timeouts >= 2) {
					fail("Unexpected " + e.getMessage());
				}
				else {
					assertNotNull(e.getCause());
					assertThat(e.getCause(), instanceOf(MessageTimeoutException.class));
				}
				timeouts++;
				continue;
			}
		}
		assertEquals("Expected exactly one ExecutionException", 1, timeouts);
		assertEquals(1, replies.size());
		assertEquals(lastReceived.get().replace("Test", "Reply"), replies.get(0));
		done.set(true);
		assertEquals(0, TestUtils.getPropertyValue(gateway, "pendingReplies", Map.class).size());
		gateway.stop();
	}

	@Test
	public void testCachingFailover() throws Exception {
		final AtomicReference<ServerSocket> serverSocket = new AtomicReference<ServerSocket>();
		final CountDownLatch latch = new CountDownLatch(1);
		final AtomicBoolean done = new AtomicBoolean();
		final CountDownLatch serverLatch = new CountDownLatch(1);

		Executors.newSingleThreadExecutor().execute(() -> {
			try {
				ServerSocket server = ServerSocketFactory.getDefault().createServerSocket(0);
				serverSocket.set(server);
				latch.countDown();
				while (!done.get()) {
					Socket socket = server.accept();
					while (!socket.isClosed()) {
						try {
							ObjectInputStream ois = new ObjectInputStream(socket.getInputStream());
							String request = (String) ois.readObject();
							logger.debug("Read " + request);
							ObjectOutputStream oos = new ObjectOutputStream(socket.getOutputStream());
							oos.writeObject("bar");
							logger.debug("Replied to " + request);
							serverLatch.countDown();
						}
						catch (IOException e1) {
							logger.debug("error on write " + e1.getClass().getSimpleName());
							socket.close();
						}
					}
				}
			}
			catch (Exception e2) {
				if (!done.get()) {
					e2.printStackTrace();
				}
			}
		});
		assertTrue(latch.await(10000, TimeUnit.MILLISECONDS));

		// Failover
		AbstractClientConnectionFactory factory1 = mock(AbstractClientConnectionFactory.class);
		TcpConnectionSupport mockConn1 = makeMockConnection();
		when(factory1.getConnection()).thenReturn(mockConn1);
		doThrow(new IOException("fail")).when(mockConn1).send(Mockito.any(Message.class));

		AbstractClientConnectionFactory factory2 = new TcpNetClientConnectionFactory("localhost",
				serverSocket.get().getLocalPort());
		factory2.setSerializer(new DefaultSerializer());
		factory2.setDeserializer(new DefaultDeserializer());
		factory2.setSoTimeout(10000);
		factory2.setSingleUse(false);

		List<AbstractClientConnectionFactory> factories = new ArrayList<AbstractClientConnectionFactory>();
		factories.add(factory1);
		factories.add(factory2);
		FailoverClientConnectionFactory failoverFactory = new FailoverClientConnectionFactory(factories);
		failoverFactory.start();

		// Cache
		CachingClientConnectionFactory cachingFactory = new CachingClientConnectionFactory(failoverFactory, 2);
		cachingFactory.start();
		TcpOutboundGateway gateway = new TcpOutboundGateway();
		gateway.setConnectionFactory(cachingFactory);
		PollableChannel outputChannel = new QueueChannel();
		gateway.setOutputChannel(outputChannel);
		gateway.setBeanFactory(mock(BeanFactory.class));
		gateway.afterPropertiesSet();
		gateway.start();

		GenericMessage<String> message = new GenericMessage<String>("foo");
		gateway.handleMessage(message);
		Message<?> reply = outputChannel.receive(0);
		assertNotNull(reply);
		assertEquals("bar", reply.getPayload());
		done.set(true);
		gateway.stop();
		verify(mockConn1).send(Mockito.any(Message.class));
		serverSocket.get().close();
	}

	@Test
	public void testFailoverCached() throws Exception {
		final AtomicReference<ServerSocket> serverSocket = new AtomicReference<ServerSocket>();
		final CountDownLatch latch = new CountDownLatch(1);
		final AtomicBoolean done = new AtomicBoolean();
		final CountDownLatch serverLatch = new CountDownLatch(1);

		Executors.newSingleThreadExecutor().execute(() -> {
			try {
				ServerSocket server = ServerSocketFactory.getDefault().createServerSocket(0);
				serverSocket.set(server);
				latch.countDown();
				while (!done.get()) {
					Socket socket = server.accept();
					while (!socket.isClosed()) {
						try {
							ObjectInputStream ois = new ObjectInputStream(socket.getInputStream());
							String request = (String) ois.readObject();
							logger.debug("Read " + request);
							ObjectOutputStream oos = new ObjectOutputStream(socket.getOutputStream());
							oos.writeObject("bar");
							logger.debug("Replied to " + request);
							serverLatch.countDown();
						}
						catch (IOException e1) {
							logger.debug("error on write " + e1.getClass().getSimpleName());
							socket.close();
						}
					}
				}
			}
			catch (Exception e2) {
				if (!done.get()) {
					e2.printStackTrace();
				}
			}
		});
		assertTrue(latch.await(10000, TimeUnit.MILLISECONDS));

		// Cache
		AbstractClientConnectionFactory factory1 = mock(AbstractClientConnectionFactory.class);
		TcpConnectionSupport mockConn1 = makeMockConnection();
		when(factory1.getConnection()).thenReturn(mockConn1);
		when(factory1.isSingleUse()).thenReturn(true);
		doThrow(new IOException("fail")).when(mockConn1).send(Mockito.any(Message.class));
		CachingClientConnectionFactory cachingFactory1 = new CachingClientConnectionFactory(factory1, 1);

		AbstractClientConnectionFactory factory2 = new TcpNetClientConnectionFactory("localhost",
				serverSocket.get().getLocalPort());
		factory2.setSerializer(new DefaultSerializer());
		factory2.setDeserializer(new DefaultDeserializer());
		factory2.setSoTimeout(10000);
		factory2.setSingleUse(true);
		CachingClientConnectionFactory cachingFactory2 = new CachingClientConnectionFactory(factory2, 1);

		// Failover
		List<AbstractClientConnectionFactory> factories = new ArrayList<AbstractClientConnectionFactory>();
		factories.add(cachingFactory1);
		factories.add(cachingFactory2);
		FailoverClientConnectionFactory failoverFactory = new FailoverClientConnectionFactory(factories);
		failoverFactory.setSingleUse(true);
		failoverFactory.afterPropertiesSet();
		failoverFactory.start();

		TcpOutboundGateway gateway = new TcpOutboundGateway();
		gateway.setConnectionFactory(failoverFactory);
		PollableChannel outputChannel = new QueueChannel();
		gateway.setOutputChannel(outputChannel);
		gateway.setBeanFactory(mock(BeanFactory.class));
		gateway.afterPropertiesSet();
		gateway.start();

		GenericMessage<String> message = new GenericMessage<String>("foo");
		gateway.handleMessage(message);
		Message<?> reply = outputChannel.receive(0);
		assertNotNull(reply);
		assertEquals("bar", reply.getPayload());
		done.set(true);
		gateway.stop();
		verify(mockConn1).send(Mockito.any(Message.class));
		serverSocket.get().close();
	}

	public TcpConnectionSupport makeMockConnection() {
		TcpConnectionSupport connection = mock(TcpConnectionSupport.class);
		when(connection.isOpen()).thenReturn(true);
		return connection;
	}

	@Test
	public void testNetGWPropagatesSocketClose() throws Exception {
		ServerSocket serverSocket = ServerSocketFactory.getDefault().createServerSocket(0);
		final int port = serverSocket.getLocalPort();
		AbstractClientConnectionFactory ccf = new TcpNetClientConnectionFactory("localhost", port);
		ccf.setSerializer(new DefaultSerializer());
		ccf.setDeserializer(new DefaultDeserializer());
		ccf.setSoTimeout(10000);
		ccf.setSingleUse(false);
		ccf.start();
		testGWPropagatesSocketCloseGuts(port, ccf, serverSocket);
		serverSocket.close();
	}

	@Test
	public void testNioGWPropagatesSocketClose() throws Exception {
		ServerSocket serverSocket = ServerSocketFactory.getDefault().createServerSocket(0);
		final int port = serverSocket.getLocalPort();
		AbstractClientConnectionFactory ccf = new TcpNioClientConnectionFactory("localhost", port);
		ccf.setSerializer(new DefaultSerializer());
		ccf.setDeserializer(new DefaultDeserializer());
		ccf.setSoTimeout(10000);
		ccf.setSingleUse(false);
		ccf.start();
		testGWPropagatesSocketCloseGuts(port, ccf, serverSocket);
		serverSocket.close();
	}

	@Test
	public void testCachedGWPropagatesSocketClose() throws Exception {
		ServerSocket serverSocket = ServerSocketFactory.getDefault().createServerSocket(0);
		final int port = serverSocket.getLocalPort();
		AbstractClientConnectionFactory ccf = new TcpNetClientConnectionFactory("localhost", port);
		ccf.setSerializer(new DefaultSerializer());
		ccf.setDeserializer(new DefaultDeserializer());
		ccf.setSoTimeout(10000);
		ccf.setSingleUse(false);
		CachingClientConnectionFactory cccf = new CachingClientConnectionFactory(ccf, 1);
		cccf.start();
		testGWPropagatesSocketCloseGuts(port, cccf, serverSocket);
		serverSocket.close();
	}

	@Test
	public void testFailoverGWPropagatesSocketClose() throws Exception {
		ServerSocket serverSocket = ServerSocketFactory.getDefault().createServerSocket(0);
		final int port = serverSocket.getLocalPort();
		AbstractClientConnectionFactory ccf = new TcpNetClientConnectionFactory("localhost", port);
		ccf.setSerializer(new DefaultSerializer());
		ccf.setDeserializer(new DefaultDeserializer());
		ccf.setSoTimeout(10000);
		ccf.setSingleUse(false);
		FailoverClientConnectionFactory focf = new FailoverClientConnectionFactory(
				Collections.singletonList(ccf));
		focf.start();
		testGWPropagatesSocketCloseGuts(port, focf, serverSocket);
		serverSocket.close();
	}

	private void testGWPropagatesSocketCloseGuts(final int port, AbstractClientConnectionFactory ccf,
			final ServerSocket server) throws Exception {
		final CountDownLatch latch = new CountDownLatch(1);
		final AtomicBoolean done = new AtomicBoolean();
		final AtomicReference<String> lastReceived = new AtomicReference<String>();
		final CountDownLatch serverLatch = new CountDownLatch(1);

		Executors.newSingleThreadExecutor().execute(() -> {
			List<Socket> sockets = new ArrayList<Socket>();
			try {
				latch.countDown();
				while (!done.get()) {
					Socket socket1 = server.accept();
					sockets.add(socket1);
					while (!socket1.isClosed()) {
						try {
							ObjectInputStream ois = new ObjectInputStream(socket1.getInputStream());
							String request = (String) ois.readObject();
							logger.debug("Read " + request + " closing socket");
							socket1.close();
							lastReceived.set(request);
							serverLatch.countDown();
						}
						catch (IOException e1) {
							socket1.close();
						}
					}
				}
			}
			catch (Exception e2) {
				if (!done.get()) {
					e2.printStackTrace();
				}
			}
			for (Socket socket2 : sockets) {
				try {
					socket2.close();
				}
				catch (IOException e3) {
				}
			}
		});
		assertTrue(latch.await(10000, TimeUnit.MILLISECONDS));
		final TcpOutboundGateway gateway = new TcpOutboundGateway();
		gateway.setConnectionFactory(ccf);
		gateway.setRequestTimeout(Integer.MAX_VALUE);
		QueueChannel replyChannel = new QueueChannel();
		gateway.setRequiresReply(true);
		gateway.setOutputChannel(replyChannel);
		gateway.setRemoteTimeoutExpression(new SpelExpressionParser().parseExpression("5000"));
		gateway.setBeanFactory(mock(BeanFactory.class));
		gateway.afterPropertiesSet();
		gateway.start();
		try {
			gateway.handleMessage(MessageBuilder.withPayload("Test").build());
			fail("expected failure");
		}
		catch (Exception e) {
			assertThat(e.getCause(), instanceOf(EOFException.class));
		}
		assertEquals(0, TestUtils.getPropertyValue(gateway, "pendingReplies", Map.class).size());
		Message<?> reply = replyChannel.receive(0);
		assertNull(reply);
		done.set(true);
		ccf.getConnection();
		gateway.stop();
		ccf.stop();
	}

	@Test
	public void testNetGWPropagatesSocketTimeout() throws Exception {
		ServerSocket serverSocket = ServerSocketFactory.getDefault().createServerSocket(0);
		final int port = serverSocket.getLocalPort();
		AbstractClientConnectionFactory ccf = new TcpNetClientConnectionFactory("localhost", port);
		ccf.setSerializer(new DefaultSerializer());
		ccf.setDeserializer(new DefaultDeserializer());
		ccf.setSoTimeout(100);
		ccf.setSingleUse(false);
		ccf.start();
		testGWPropagatesSocketTimeoutGuts(port, ccf, serverSocket);
		serverSocket.close();
	}

	@Test
	public void testNioGWPropagatesSocketTimeout() throws Exception {
		ServerSocket serverSocket = ServerSocketFactory.getDefault().createServerSocket(0);
		final int port = serverSocket.getLocalPort();
		AbstractClientConnectionFactory ccf = new TcpNioClientConnectionFactory("localhost", port);
		ccf.setSerializer(new DefaultSerializer());
		ccf.setDeserializer(new DefaultDeserializer());
		ccf.setSoTimeout(100);
		ccf.setSingleUse(false);
		ccf.start();
		testGWPropagatesSocketTimeoutGuts(port, ccf, serverSocket);
		serverSocket.close();
	}

	@Test
	public void testNetGWPropagatesSocketTimeoutSingleUse() throws Exception {
		ServerSocket serverSocket = ServerSocketFactory.getDefault().createServerSocket(0);
		final int port = serverSocket.getLocalPort();
		AbstractClientConnectionFactory ccf = new TcpNetClientConnectionFactory("localhost", port);
		ccf.setSerializer(new DefaultSerializer());
		ccf.setDeserializer(new DefaultDeserializer());
		ccf.setSoTimeout(100);
		ccf.setSingleUse(true);
		ccf.start();
		testGWPropagatesSocketTimeoutGuts(port, ccf, serverSocket);
		serverSocket.close();
	}

	@Test
	public void testNioGWPropagatesSocketTimeoutSingleUse() throws Exception {
		ServerSocket serverSocket = ServerSocketFactory.getDefault().createServerSocket(0);
		final int port = serverSocket.getLocalPort();
		AbstractClientConnectionFactory ccf = new TcpNioClientConnectionFactory("localhost", port);
		ccf.setSerializer(new DefaultSerializer());
		ccf.setDeserializer(new DefaultDeserializer());
		ccf.setSoTimeout(100);
		ccf.setSingleUse(true);
		ccf.start();
		testGWPropagatesSocketTimeoutGuts(port, ccf, serverSocket);
		serverSocket.close();
	}

	private void testGWPropagatesSocketTimeoutGuts(final int port, AbstractClientConnectionFactory ccf,
			final ServerSocket server) throws Exception {
		final CountDownLatch latch = new CountDownLatch(1);
		final AtomicBoolean done = new AtomicBoolean();

		Executors.newSingleThreadExecutor().execute(() -> {
			List<Socket> sockets = new ArrayList<Socket>();
			try {
				latch.countDown();
				while (!done.get()) {
					sockets.add(server.accept());
				}
			}
			catch (Exception e1) {
				if (!done.get()) {
					e1.printStackTrace();
				}
			}
			for (Socket socket : sockets) {
				try {
					socket.close();
				}
				catch (IOException e2) {
				}
			}
		});
		assertTrue(latch.await(10000, TimeUnit.MILLISECONDS));
		final TcpOutboundGateway gateway = new TcpOutboundGateway();
		gateway.setConnectionFactory(ccf);
		gateway.setRequestTimeout(Integer.MAX_VALUE);
		QueueChannel replyChannel = new QueueChannel();
		gateway.setRequiresReply(true);
		gateway.setOutputChannel(replyChannel);
		gateway.setRemoteTimeoutExpression(new SpelExpressionParser().parseExpression("5000"));
		gateway.setBeanFactory(mock(BeanFactory.class));
		gateway.afterPropertiesSet();
		gateway.start();
		try {
			gateway.handleMessage(MessageBuilder.withPayload("Test").build());
			fail("expected failure");
		}
		catch (Exception e) {
			assertThat(e.getCause(), instanceOf(SocketTimeoutException.class));
		}
		assertEquals(0, TestUtils.getPropertyValue(gateway, "pendingReplies", Map.class).size());
		Message<?> reply = replyChannel.receive(0);
		assertNull(reply);
		done.set(true);
		ccf.getConnection();
		gateway.stop();
		ccf.stop();
	}

}
