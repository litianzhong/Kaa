/**
 *  Copyright 2014-2016 CyberVision, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.kaaproject.kaa.client.channel.impl.channels;

import java.io.IOException;
import java.net.Socket;
import java.net.UnknownHostException;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.kaaproject.kaa.client.channel.ChannelDirection;
import org.kaaproject.kaa.client.channel.FailoverDecision;
import org.kaaproject.kaa.client.channel.FailoverManager;
import org.kaaproject.kaa.client.channel.FailoverStatus;
import org.kaaproject.kaa.client.channel.IPTransportInfo;
import org.kaaproject.kaa.client.channel.KaaDataChannel;
import org.kaaproject.kaa.client.channel.KaaDataDemultiplexer;
import org.kaaproject.kaa.client.channel.KaaDataMultiplexer;
import org.kaaproject.kaa.client.channel.ServerType;
import org.kaaproject.kaa.client.channel.TransportConnectionInfo;
import org.kaaproject.kaa.client.channel.TransportProtocolId;
import org.kaaproject.kaa.client.channel.TransportProtocolIdConstants;
import org.kaaproject.kaa.client.channel.connectivity.ConnectivityChecker;
import org.kaaproject.kaa.client.persistence.KaaClientState;
import org.kaaproject.kaa.common.Constants;
import org.kaaproject.kaa.common.TransportType;
import org.kaaproject.kaa.common.channels.protocols.kaatcp.KaaTcpProtocolException;
import org.kaaproject.kaa.common.channels.protocols.kaatcp.listeners.ConnAckListener;
import org.kaaproject.kaa.common.channels.protocols.kaatcp.listeners.DisconnectListener;
import org.kaaproject.kaa.common.channels.protocols.kaatcp.listeners.PingResponseListener;
import org.kaaproject.kaa.common.channels.protocols.kaatcp.listeners.SyncResponseListener;
import org.kaaproject.kaa.common.channels.protocols.kaatcp.messages.ConnAck;
import org.kaaproject.kaa.common.channels.protocols.kaatcp.messages.ConnAck.ReturnCode;
import org.kaaproject.kaa.common.channels.protocols.kaatcp.messages.Connect;
import org.kaaproject.kaa.common.channels.protocols.kaatcp.messages.Disconnect;
import org.kaaproject.kaa.common.channels.protocols.kaatcp.messages.Disconnect.DisconnectReason;
import org.kaaproject.kaa.common.channels.protocols.kaatcp.messages.MessageFactory;
import org.kaaproject.kaa.common.channels.protocols.kaatcp.messages.MqttFrame;
import org.kaaproject.kaa.common.channels.protocols.kaatcp.messages.PingRequest;
import org.kaaproject.kaa.common.channels.protocols.kaatcp.messages.PingResponse;
import org.kaaproject.kaa.common.channels.protocols.kaatcp.messages.SyncRequest;
import org.kaaproject.kaa.common.channels.protocols.kaatcp.messages.SyncResponse;
import org.kaaproject.kaa.common.endpoint.security.MessageEncoderDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DefaultOperationTcpChannel implements KaaDataChannel {

    public static final Logger LOG = LoggerFactory // NOSONAR
            .getLogger(DefaultOperationTcpChannel.class);

    private static final int EXIT_FAILURE = 1;
    private static final Map<TransportType, ChannelDirection> SUPPORTED_TYPES = new HashMap<TransportType, ChannelDirection>();
    static {
        SUPPORTED_TYPES.put(TransportType.PROFILE, ChannelDirection.BIDIRECTIONAL);
        SUPPORTED_TYPES.put(TransportType.CONFIGURATION, ChannelDirection.BIDIRECTIONAL);
        SUPPORTED_TYPES.put(TransportType.NOTIFICATION, ChannelDirection.BIDIRECTIONAL);
        SUPPORTED_TYPES.put(TransportType.USER, ChannelDirection.BIDIRECTIONAL);
        SUPPORTED_TYPES.put(TransportType.EVENT, ChannelDirection.BIDIRECTIONAL);
        SUPPORTED_TYPES.put(TransportType.LOGGING, ChannelDirection.BIDIRECTIONAL);
    }

    private static final int CHANNEL_TIMEOUT = 200;
    private static final int PING_TIMEOUT = CHANNEL_TIMEOUT / 2;

    private static final String CHANNEL_ID = "default_operation_tcp_channel";

    private IPTransportInfo currentServer;
    private final KaaClientState state;

    private ScheduledExecutorService executor;

    private volatile State channelState = State.CLOSED;

    private KaaDataDemultiplexer demultiplexer;
    private KaaDataMultiplexer multiplexer;

    private volatile Socket socket;
    private MessageEncoderDecoder encDec;

    private final FailoverManager failoverManager;

    private volatile ConnectivityChecker connectivityChecker;

    private final Runnable openConnectionTask = new Runnable() {
        @Override
        public void run() {
            openConnection();
        }
    };

    private final ConnAckListener connAckListener = new ConnAckListener() {

        @Override
        public void onMessage(ConnAck message) {
            LOG.info("ConnAck ({}) message received for channel [{}]", message.getReturnCode(), getId());

            if (message.getReturnCode() != ReturnCode.ACCEPTED) {
                LOG.error("Connection for channel [{}] was rejected: {}", getId(), message.getReturnCode());
                if (message.getReturnCode() == ReturnCode.REFUSE_BAD_CREDENTIALS) {
                    LOG.info("Cleaning client state");
                    state.clean();
                }
                onServerFailed();
            }
        }

    };

    private final PingResponseListener pingResponseListener = new PingResponseListener() {

        @Override
        public void onMessage(PingResponse message) {
            LOG.info("PingResponse message received for channel [{}]", getId());
        }

    };

    private final SyncResponseListener kaaSyncResponseListener = new SyncResponseListener() {

        @Override
        public void onMessage(SyncResponse message) {
            LOG.info("KaaSync message (zipped={}, encrypted={}) received for channel [{}]", message.isZipped(), message.isEncrypted(),
                    getId());
            byte[] resultBody = null;
            if (message.isEncrypted()) {
                synchronized (this) {
                    try {
                        resultBody = encDec.decodeData(message.getAvroObject());
                    } catch (GeneralSecurityException e) {
                        LOG.error("Failed to decrypt message body for channel [{}]: {}", getId());
                        LOG.error("Stack Trace: ", e);
                    }
                }
            } else {
                resultBody = message.getAvroObject();
            }
            if (resultBody != null) {
                try {
                    demultiplexer.preProcess();
                    demultiplexer.processResponse(resultBody);
                    demultiplexer.postProcess();
                } catch (Exception e) {
                    LOG.error("Failed to process response for channel [{}]", getId(), e);
                }

                synchronized (DefaultOperationTcpChannel.this) {
                    channelState = State.OPENED;
                }
                failoverManager.onServerConnected(currentServer);
            }
        }
    };

    private final DisconnectListener disconnectListener = new DisconnectListener() {

        @Override
        public void onMessage(Disconnect message) {
            LOG.info("Disconnect message (reason={}) received for channel [{}]", message.getReason(), getId());
            if (!message.getReason().equals(DisconnectReason.NONE)) {
                LOG.error("Server error occurred: {}", message.getReason());
                onServerFailed();
            } else {
                closeConnection();
            }
        }
    };

    private class SocketReadTask implements Runnable {
        private final Socket readTaskSocket;
        private final byte[] buffer;

        public SocketReadTask(Socket readTaskSocket) {
            this.readTaskSocket = readTaskSocket;
            this.buffer = new byte[1024];
        }

        @Override
        public void run() {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    LOG.info("Channel [{}] is reading data from stream using [{}] byte buffer", getId(), buffer.length);

                    int size = readTaskSocket.getInputStream().read(buffer);

                    if (size > 0) {
                        messageFactory.getFramer().pushBytes(Arrays.copyOf(buffer, size));
                    } else if (size == -1) {
                        LOG.info("Channel [{}] received end of stream ({})", getId(), size);
                        onServerFailed();
                    }

                } catch (IOException | KaaTcpProtocolException | RuntimeException e) {
                    if (Thread.currentThread().isInterrupted()) {
                        if (channelState != State.SHUTDOWN) {
                            LOG.warn("Failed to read from the socket for channel [{}]. Stack trace: ", getId(), e);
                            LOG.warn("Socket connection for channel [{}] was interrupted: ", e);
                        } else {
                            LOG.debug("Failed to read from the socket for channel [{}]. Stack trace: ", getId(), e);
                            LOG.debug("Socket connection for channel [{}] was interrupted: ", e);
                        }
                    }

                    if (readTaskSocket.equals(socket)) {
                        onServerFailed();
                    } else {
                        LOG.debug("Stale socket: {} is detected, killing read task...", readTaskSocket);
                    }
                    break;
                }
            }
            LOG.info("Read Task is interrupted for channel [{}]", getId());
        }
    }

    private final Runnable pingTask = new Runnable() {

        @Override
        public void run() {
            if (!Thread.currentThread().isInterrupted()) {
                try {
                    LOG.info("Executing ping task for channel [{}]", getId());
                    sendPingRequest();
                    if (!Thread.currentThread().isInterrupted()) {
                        schedulePingTask();
                    } else {
                        LOG.info("Can't schedule ping task for channel [{}]. Task was interrupted", getId());
                    }
                } catch (IOException e) {
                    LOG.error("Failed to send ping request for channel [{}]. Stack trace: ", getId(), e);
                    onServerFailed();
                }
            } else {
                LOG.info("Can't execute ping task for channel [{}]. Task was interrupted", getId());
            }
        }
    };

    private final MessageFactory messageFactory = new MessageFactory();

    private volatile Future<?> pingTaskFuture;
    private volatile Future<?> readTaskFuture;

    private volatile boolean isOpenConnectionScheduled;

    public DefaultOperationTcpChannel(KaaClientState state, FailoverManager failoverManager) {
        this.state = state;
        this.failoverManager = failoverManager;
        messageFactory.registerMessageListener(connAckListener);
        messageFactory.registerMessageListener(kaaSyncResponseListener);
        messageFactory.registerMessageListener(pingResponseListener);
        messageFactory.registerMessageListener(disconnectListener);
    }

    private void sendFrame(MqttFrame frame) throws IOException {
        if (socket != null) {
            synchronized (socket) {
                socket.getOutputStream().write(frame.getFrame().array());
            }
        }
    }

    private void sendPingRequest() throws IOException {
        LOG.debug("Sending PinRequest from channel [{}]", getId());
        sendFrame(new PingRequest());
    }

    private void sendDisconnect() throws IOException {
        LOG.debug("Sending Disconnect from channel [{}]", getId());
        sendFrame(new Disconnect(DisconnectReason.NONE));
    }

    private void sendKaaSyncRequest(Map<TransportType, ChannelDirection> types) throws Exception {
        LOG.debug("Sending KaaSync from channel [{}]", getId());
        byte[] body = multiplexer.compileRequest(types);
        byte[] requestBodyEncoded = encDec.encodeData(body);
        sendFrame(new SyncRequest(requestBodyEncoded, false, true));
    }

    private void sendConnect() throws Exception {
        LOG.debug("Sending Connect to channel [{}]", getId());
        byte[] body = multiplexer.compileRequest(getSupportedTransportTypes());
        byte[] requestBodyEncoded = encDec.encodeData(body);
        byte[] sessionKey = encDec.getEncodedSessionKey();
        byte[] signature = encDec.sign(sessionKey);
        sendFrame(new Connect(CHANNEL_TIMEOUT, Constants.KAA_PLATFORM_PROTOCOL_AVRO_ID, sessionKey, requestBodyEncoded, signature));
    }

    private synchronized void closeConnection() {
        if (pingTaskFuture != null && !pingTaskFuture.isCancelled()) {
            pingTaskFuture.cancel(true);
        }

        if (readTaskFuture != null && !readTaskFuture.isCancelled()) {
            readTaskFuture.cancel(true);
        }

        if (socket != null) {
            LOG.info("Channel \"{}\": closing current connection", getId());
            try {
                sendDisconnect();
            } catch (IOException e) {
                LOG.error("Failed to send Disconnect to server: {}", e);
            } finally {
                try {
                    socket.close();
                } catch (IOException e) {
                    LOG.error("Failed to close socket: {}", e);
                }
                socket = null;
                messageFactory.getFramer().flush();
                if (channelState != State.SHUTDOWN) {
                    channelState = State.CLOSED;
                }
            }
        }
    }

    protected Socket createSocket(String host, int port) throws UnknownHostException, IOException {
        return new Socket(host, port);
    }

    private synchronized void openConnection() {
        if (channelState == State.PAUSE || channelState == State.SHUTDOWN) {
            LOG.info("Can't open connection, as channel is in the {} state", channelState);
            return;
        }
        try {
            LOG.info("Channel [{}]: opening connection to server {}", getId(), currentServer);
            isOpenConnectionScheduled = false;
            socket = createSocket(currentServer.getHost(), currentServer.getPort());
            sendConnect();
            scheduleReadTask(socket);
            schedulePingTask();
        } catch (Exception e) {
            LOG.error("Failed to create a socket for server {}:{}. Stack trace: ", currentServer.getHost(), currentServer.getPort(), e);
            onServerFailed();
        }
    }

    private void onServerFailed() {
        LOG.info("[{}] has failed", getId());
        closeConnection();
        if (connectivityChecker != null && !connectivityChecker.checkConnectivity()) {
            LOG.warn("Loss of connectivity detected");

            FailoverDecision decision = failoverManager.onFailover(FailoverStatus.NO_CONNECTIVITY);
            switch (decision.getAction()) {
                case NOOP:
                    LOG.warn("No operation is performed according to failover strategy decision");
                    break;
                case RETRY:
                    long retryPeriod = decision.getRetryPeriod();
                    LOG.warn("Attempt to reconnect will be made in {} ms " +
                            "according to failover strategy decision", retryPeriod);
                    scheduleOpenConnectionTask(retryPeriod);
                break;
                case STOP_APP:
                    LOG.warn("Stopping application according to failover strategy decision!");
                    System.exit(EXIT_FAILURE); //NOSONAR
            }
        } else {
            failoverManager.onServerFailed(currentServer);
        }
    }

    private synchronized void scheduleOpenConnectionTask(long retryPeriod) {
        if (!isOpenConnectionScheduled) {
            if (executor != null) {
                LOG.info("Scheduling open connection task");
                executor.schedule(openConnectionTask, retryPeriod, TimeUnit.MILLISECONDS);
                isOpenConnectionScheduled = true;
            } else {
                LOG.info("Executor is null, can't schedule open connection task");
            }
        } else {
            LOG.info("Reconnect is already scheduled, ignoring the call");
        }
    }

    private void scheduleReadTask(Socket socket) {
        if (executor != null) {
            readTaskFuture = executor.submit(new SocketReadTask(socket));
            LOG.debug("Submitting a read task for channel [{}]", getId());
        } else {
            LOG.warn("Executor is null, can't submit read task");
        }
    }

    private void schedulePingTask() {
        if (executor != null) {
            LOG.debug("Scheduling a ping task ({} seconds) for channel [{}]", PING_TIMEOUT, getId());
            pingTaskFuture = executor.schedule(pingTask, PING_TIMEOUT, TimeUnit.SECONDS);
        } else {
            LOG.warn("Executor is null, can't schedule ping task");
        }
    }

    protected ScheduledExecutorService createExecutor() {
        LOG.info("Creating a new executor for channel [{}]", getId());
        return new ScheduledThreadPoolExecutor(2);
    }

    @Override
    public synchronized void sync(TransportType type) {
        sync(Collections.singleton(type));
    }

    @Override
    public synchronized void sync(Set<TransportType> types) {
        if (channelState == State.SHUTDOWN) {
            LOG.info("Can't sync. Channel [{}] is down", getId());
            return;
        }
        if (channelState == State.PAUSE) {
            LOG.info("Can't sync. Channel [{}] is paused", getId());
            return;
        }
        if (channelState != State.OPENED) {
            LOG.info("Can't sync. Channel [{}] is waiting for CONNACK message + KAASYNC message", getId());
            return;
        }
        if (multiplexer == null) {
            LOG.warn("Can't sync. Channel {} multiplexer is not set", getId());
            return;
        }
        if (demultiplexer == null) {
            LOG.warn("Can't sync. Channel {} demultiplexer is not set", getId());
            return;
        }
        if (currentServer == null || socket == null) {
            LOG.warn("Can't sync. Server is {}, socket is \"{}\"", currentServer, socket);
            return;
        }

        Map<TransportType, ChannelDirection> typeMap = new HashMap<>(getSupportedTransportTypes().size());
        for (TransportType type : types) {
            LOG.info("Processing sync {} for channel [{}]", type, getId());
            ChannelDirection direction = getSupportedTransportTypes().get(type);
            if (direction != null) {
                typeMap.put(type, direction);
            } else {
                LOG.error("Unsupported type {} for channel [{}]", type, getId());
            }
            for (Map.Entry<TransportType, ChannelDirection> typeIt : getSupportedTransportTypes().entrySet()) {
                if (!typeIt.getKey().equals(type)) {
                    typeMap.put(typeIt.getKey(), ChannelDirection.DOWN);
                }
            }
        }
        try {
            sendKaaSyncRequest(typeMap);
        } catch (Exception e) {
            LOG.error("Failed to sync channel [{}]", getId(), e);
        }
    }

    @Override
    public synchronized void syncAll() {
        if (channelState == State.SHUTDOWN) {
            LOG.info("Can't sync. Channel [{}] is down", getId());
            return;
        }
        if (channelState == State.PAUSE) {
            LOG.info("Can't sync. Channel [{}] is paused", getId());
            return;
        }
        if (channelState != State.OPENED) {
            LOG.info("Can't sync. Channel [{}] is waiting for CONNACK + KAASYNC message", getId());
            return;
        }
        LOG.info("Processing sync all for channel [{}]", getId());
        if (multiplexer != null && demultiplexer != null) {
            if (currentServer != null && socket != null) {
                try {
                    sendKaaSyncRequest(getSupportedTransportTypes());
                } catch (Exception e) {
                    LOG.error("Failed to sync channel [{}]: {}", getId(), e);
                    onServerFailed();
                }
            } else {
                LOG.warn("Can't sync. Server is {}, socket is {}", currentServer, socket);
            }
        }
    }

    @Override
    public void syncAck(TransportType type) {
        LOG.info("Adding sync acknowledgement for type {} as a regular sync for channel [{}]", type, getId());
        syncAck(Collections.singleton(type));
    }

    @Override
    public void syncAck(Set<TransportType> types) {
        synchronized (this) {
            if (channelState != State.OPENED) {
                LOG.info("First KaaSync message received and processed for channel [{}]", getId());
                channelState = State.OPENED;
                failoverManager.onServerConnected(currentServer);
                LOG.debug("There are pending requests for channel [{}] -> starting sync", getId());
                syncAll();
            } else {
                LOG.debug("Acknowledgment is pending for channel [{}] -> starting sync", getId());
                if (types.size() == 1) {
                    sync(types.iterator().next());
                } else {
                    syncAll();
                }
            }
        }
    }

    @Override
    public synchronized void setDemultiplexer(KaaDataDemultiplexer demultiplexer) {
        if (demultiplexer != null) {
            this.demultiplexer = demultiplexer;
        }
    }

    @Override
    public synchronized void setMultiplexer(KaaDataMultiplexer multiplexer) {
        if (multiplexer != null) {
            this.multiplexer = multiplexer;
        }
    }

    @Override
    public synchronized void setServer(TransportConnectionInfo server) {
        LOG.info("Setting server [{}] for channel [{}]", server, getId());
        if (server == null) {
            LOG.warn("Server is null for Channel [{}].", getId());
            return;
        }
        if (channelState == State.SHUTDOWN) {
            LOG.info("Can't set server. Channel [{}] is down", getId());
            return;
        }
        IPTransportInfo oldServer = currentServer;
        this.currentServer = new IPTransportInfo(server);
        this.encDec = new MessageEncoderDecoder(state.getPrivateKey(), state.getPublicKey(), currentServer.getPublicKey());
        if (channelState != State.PAUSE) {
            if (executor == null) {
                executor = createExecutor();
            }
            if (oldServer == null
                        || socket == null
                        || !oldServer.getHost().equals(currentServer.getHost())
                        || oldServer.getPort() != currentServer.getPort()) {
                LOG.info("New server's: {} host or ip is different from the old {}, reconnecting", oldServer, oldServer);
                closeConnection();
                scheduleOpenConnectionTask(0);
            }
        } else {
            LOG.info("Can't start new session. Channel [{}] is paused", getId());
        }
    }

    @Override
    public TransportConnectionInfo getServer() {
        return currentServer;
    }

    @Override
    public void setConnectivityChecker(ConnectivityChecker checker) {
        connectivityChecker = checker;
    }

    @Override
    public synchronized void shutdown() {
        LOG.info("Shutting down...");
        channelState = State.SHUTDOWN;
        closeConnection();
        destroyExecutor();
    }

    @Override
    public synchronized void pause() {
        if (channelState != State.PAUSE) {
            LOG.info("Pausing...");
            channelState = State.PAUSE;
            closeConnection();
            destroyExecutor();
        }
    }

    private synchronized void destroyExecutor() {
        if (executor != null) {
            executor.shutdownNow();
            isOpenConnectionScheduled = false;
            executor = null;
        }
    }

    @Override
    public synchronized void resume() {
        if (channelState == State.PAUSE) {
            LOG.info("Resuming...");
            channelState = State.CLOSED;
            if (executor == null) {
                executor = createExecutor();
            }
            scheduleOpenConnectionTask(0);
        }
    }

    @Override
    public String getId() {
        return CHANNEL_ID;
    }

    @Override
    public TransportProtocolId getTransportProtocolId() {
        return TransportProtocolIdConstants.TCP_TRANSPORT_ID;
    }

    @Override
    public ServerType getServerType() {
        return ServerType.OPERATIONS;
    }

    @Override
    public Map<TransportType, ChannelDirection> getSupportedTransportTypes() {
        return SUPPORTED_TYPES;
    }

    private enum State {
        SHUTDOWN, PAUSE, CLOSED, OPENED
    }
}
