package com.wizzardo.http;

import com.wizzardo.epoll.*;
import com.wizzardo.http.request.Header;
import com.wizzardo.http.response.Status;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * @author: moxa
 * Date: 11/5/13
 */
public abstract class AbstractHttpServer<T extends HttpConnection> {

    protected volatile BlockingQueue<T> queue = new LinkedBlockingQueue<>();
    protected volatile int workersCount;
    protected volatile int sessionTimeoutSec = 30 * 60;
    protected int postBodyLimit = 2 * 1024 * 1024;
    protected int websocketFrameLengthLimit = 64 * 1024;
    protected int maxRequestsInQueue = 1000;
    protected String context;
    protected final EpollServer<T> server;

    protected MimeProvider mimeProvider;

    public AbstractHttpServer() {
        this(null, 8080);
    }

    public AbstractHttpServer(int port) {
        this(null, port);
    }

    public AbstractHttpServer(String host, int port) {
        this(host, port, 0);
    }

    public AbstractHttpServer(String host, int port, int workersCount) {
        String disabled = System.getenv("EPOLL_DISABLED");
        if (EpollCore.SUPPORTED && !Boolean.parseBoolean(disabled)) {
            server = new EpollServer<T>(host, port) {
                @Override
                protected T createConnection(int fd, int ip, int port) {
                    return AbstractHttpServer.this.createConnection(fd, ip, port);
                }

                @Override
                protected IOThread<T> createIOThread(int number, int divider) {
                    return AbstractHttpServer.this.createIOThread(number, divider);
                }
            };
        } else {
            server = new FallbackServerSocket<T>(host, port) {
                @Override
                public void onRead(T connection, ByteBufferProvider bufferProvider) {
                    process(connection, bufferProvider);
                }

                @Override
                protected SelectorConnectionWrapper createConnection(SocketChannel client) throws IOException {
                    SelectorConnectionWrapper connection = super.createConnection(client);
                    connection.server = AbstractHttpServer.this;
                    return connection;
                }
            };
        }
        this.workersCount = workersCount;
    }

    protected Worker<T> createWorker(ThreadGroup group, BlockingQueue<T> queue, String name) {
        return new HttpWorker<>(this, group, queue, name);
    }

    public synchronized void start() {
        run();
    }

    public void run() {
        Session.createSessionsHolder(sessionTimeoutSec);
        System.out.println("worker count: " + workersCount);
        ThreadGroup group = new ThreadGroup("http-workers");
        for (int i = 0; i < workersCount; i++) {
            createWorker(group, queue, "worker_" + i).start();
        }
        server.start();
    }

    public int getPort() {
        return server.getPort();
    }

    public String getHostname() {
        return server.getHostname();
    }

    public boolean isSecured() {
        return false;
    }

    public void setHostname(String hostname) {
        server.setHostname(hostname);
    }

    public void setPort(int port) {
        server.setPort(port);
    }

    public void setIoThreadsCount(int ioThreadsCount) {
        server.setIoThreadsCount(ioThreadsCount);
    }

    public void setTTL(long milliseconds) {
        server.setTTL(milliseconds);
    }

    public void loadCertificates(SslConfig sslConfig) {
        server.loadCertificates(sslConfig);
    }

    public void loadCertificates(String certFile, String keyFile) {
        server.loadCertificates(certFile, keyFile);
    }

    public void close() {
        server.close();
    }

    public int getPostBodyLimit() {
        return postBodyLimit;
    }

    public void setPostBodyLimit(int postBodyLimit) {
        this.postBodyLimit = postBodyLimit;
    }

    public int getWebsocketFrameLengthLimit() {
        return websocketFrameLengthLimit;
    }

    public void setWebsocketFrameLengthLimit(int websocketFrameLengthLimit) {
        this.websocketFrameLengthLimit = websocketFrameLengthLimit;
    }

    public int getMaxRequestsInQueue() {
        return maxRequestsInQueue;
    }

    public void setMaxRequestsInQueue(int maxRequestsInQueue) {
        this.maxRequestsInQueue = maxRequestsInQueue > 0 ? maxRequestsInQueue : Integer.MAX_VALUE;
    }

    protected T createConnection(int fd, int ip, int port) {
        return (T) new HttpConnection(fd, ip, port, this);
    }

    protected IOThread<T> createIOThread(int number, int divider) {
        return new HttpIOThread(this, number, divider);
    }

    protected boolean checkData(T connection, ByteBufferProvider bufferProvider) {
        if (connection.processInputListener())
            return false;

        ByteBuffer b;
        try {
            while ((b = connection.read(connection.getBufferSize(), bufferProvider)).limit() > 0) {
                if (connection.check(b))
                    break;
            }
            if (!connection.isRequestReady())
                return false;

        } catch (HttpException e) {
            closeConnection(connection, e.status);
            e.printStackTrace();
            return false;
        } catch (Exception e) {
            closeConnection(connection, Status._400);
            e.printStackTrace();
            return false;
        }

        return true;
    }

    protected void closeConnection(T connection, Status status) {
        connection.getResponse()
                .status(status)
                .appendHeader(Header.KV_CONNECTION_CLOSE)
                .commit(connection, getBufferProvider());
        connection.setCloseOnFinishWriting(true);
    }

    void process(T connection, ByteBufferProvider bufferProvider) {
        if (workersCount > 0) {
            if (queue.size() > maxRequestsInQueue) {
                safeOnError(connection, new IllegalStateException("Too many requests"));
                return;
            }

            queue.add(connection);
        } else if (checkData(connection, bufferProvider)) {
            while (processConnection(connection)) {
            }
        }
    }

    protected boolean processConnection(T connection) {
        try {
            handle(connection);
            if (handleAsync(connection))
                return false;
        } catch (Exception t) {
            safeOnError(connection,t);
        }
        try {
            return finishHandling(connection);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    protected abstract void handle(T connection) throws Exception;

    protected void onError(T connection, Exception e) throws Exception {
        e.printStackTrace();
    }

    protected void safeOnError(T connection, Exception e) {
        try {
            onError(connection, e);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    protected boolean handleAsync(T connection) throws IOException {
        if (connection.getResponse().isAsync()) {
            if (connection.getInputListener() != null)
                connection.getInputListener().onReady(connection);

            return true;
        }
        return false;
    }

    protected boolean finishHandling(T connection) throws IOException {
        connection.getResponse().commit(connection, getBufferProvider());
        connection.flushOutputStream();
        if (!connection.onFinishingHandling())
            return false;

        if (connection.isRequestReady())
            return true;
        else if (connection.isReadyToRead() && checkData(connection, getBufferProvider()))
            return true;
        return false;
    }

    protected ByteBufferProvider getBufferProvider() {
        return (ByteBufferProvider) Thread.currentThread();
    }

    public void setSessionTimeout(int sec) {
        checkIfStarted();
        this.sessionTimeoutSec = sec;
    }

    public void setWorkersCount(int count) {
        checkIfStarted();
        this.workersCount = count;
    }

    public void setContext(String context) {
        checkIfStarted();
        this.context = context;
    }

    protected void checkIfStarted() {
        if (server.isStarted())
            throw new IllegalStateException("Server is already started");
    }

    public String getContext() {
        return context;
    }

    public MimeProvider getMimeProvider() {
        if (mimeProvider == null)
            mimeProvider = createMimeProvider();

        return mimeProvider;
    }

    protected MimeProvider createMimeProvider() {
        return new MimeProvider();
    }
}
