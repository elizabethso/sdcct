package gov.hhs.onc.sdcct.web.tomcat.impl;

import gov.hhs.onc.sdcct.context.SdcctPropertyNames;
import gov.hhs.onc.sdcct.logging.impl.TxIdGenerator;
import gov.hhs.onc.sdcct.logging.impl.TxTaskWrapper;
import java.io.File;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nonnegative;
import javax.annotation.Nullable;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.SessionTrackingMode;
import org.apache.catalina.Context;
import org.apache.catalina.Host;
import org.apache.catalina.Service;
import org.apache.catalina.Valve;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.core.StandardContext;
import org.apache.catalina.startup.Tomcat;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.ClassUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.coyote.http11.Http11NioProtocol;
import org.apache.tomcat.util.net.NioEndpoint;
import org.apache.tomcat.util.threads.TaskQueue;
import org.apache.tomcat.util.threads.TaskThreadFactory;
import org.apache.tomcat.util.threads.ThreadPoolExecutor;
import org.apache.tomcat.websocket.server.WsContextListener;
import org.springframework.boot.context.embedded.EmbeddedServletContainer;
import org.springframework.boot.context.embedded.tomcat.TomcatEmbeddedServletContainerFactory;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;

public class SdcctTomcatEmbeddedServletContainerFactory extends TomcatEmbeddedServletContainerFactory {
    public static class SdcctHttp11NioProtocol extends Http11NioProtocol {
        public SdcctHttp11NioProtocol() {
            super();
        }

        @Override
        public NioEndpoint getEndpoint() {
            return ((NioEndpoint) super.getEndpoint());
        }
    }

    public static class SdcctConnector extends Connector {
        public SdcctConnector() {
            super(SdcctHttp11NioProtocol.class.getName());

            this.protocolHandlerClassName = Http11NioProtocol.class.getName();
        }

        @Override
        public Response createResponse() {
            TomcatResponse resp = new TomcatResponse();
            resp.setConnector(this);

            return resp;
        }

        @Override
        public Request createRequest() {
            TomcatRequest req = new TomcatRequest();
            req.setConnector(this);

            return req;
        }
    }

    public class SdcctThreadPoolExecutor extends ThreadPoolExecutor {
        private Map<String, TxIdGenerator> txIdGens;

        public SdcctThreadPoolExecutor(Map<String, TxIdGenerator> txIdGens) {
            super(SdcctTomcatEmbeddedServletContainerFactory.this.minConnThreads, SdcctTomcatEmbeddedServletContainerFactory.this.maxConnThreads,
                SdcctTomcatEmbeddedServletContainerFactory.this.connKeepAliveTimeout, TimeUnit.SECONDS, new TaskQueue(), new TaskThreadFactory(
                    (SdcctTomcatEmbeddedServletContainerFactory.this.connEndpointName + CONN_EXEC_THREAD_NAME_PREFIX), true, Thread.NORM_PRIORITY));

            this.txIdGens = txIdGens;

            ((TaskQueue) this.getQueue()).setParent(this);
        }

        @Override
        public void execute(Runnable task) {
            super.execute((task.getClass().getName().equals(SOCKET_PROCESSOR_CLASS_NAME) ? new TxTaskWrapper(this.txIdGens, task) : task));
        }
    }

    private static class SdcctSessionConfigServletContextInitializer implements ServletContextInitializer {
        @Override
        public void onStartup(ServletContext servletContext) throws ServletException {
            servletContext.setSessionTrackingModes(EnumSet.of(SessionTrackingMode.COOKIE));
        }
    }

    private final static String CONN_EXEC_THREAD_NAME_PREFIX = "-exec-";

    private final static String SOCKET_PROCESSOR_CLASS_NAME = NioEndpoint.class.getName() + ClassUtils.INNER_CLASS_SEPARATOR_CHAR + "SocketProcessor";

    private File baseDir;
    private String connEndpointName;
    private int connKeepAliveTimeout;
    private int connTimeout;
    private int maxConns;
    private int maxConnThreads;
    private int minConnThreads;
    private String proxyHost;
    private int proxyPort;
    private TxIdGenerator txIdGen;
    private File workDir;
    private Tomcat tomcat;

    @Override
    public EmbeddedServletContainer getEmbeddedServletContainer(ServletContextInitializer ... servletContextInits) {
        (this.tomcat = new Tomcat()).setBaseDir(this.baseDir.getAbsolutePath());

        Service service = this.tomcat.getService();

        Connector conn = new SdcctConnector();
        service.addConnector(conn);

        this.customizeConnector(conn);
        this.tomcat.setConnector(conn);

        Host host = this.tomcat.getHost();
        host.setAutoDeploy(false);

        this.tomcat.getEngine().setBackgroundProcessorDelay(-1);

        this.prepareContext(host, servletContextInits);

        return this.getTomcatEmbeddedServletContainer(this.tomcat);
    }

    @Override
    protected void customizeConnector(Connector conn) {
        super.customizeConnector(conn);

        if (!StringUtils.isEmpty(this.proxyHost) && (this.proxyPort > 0)) {
            conn.setProxyName(this.proxyHost);
            conn.setProxyPort(this.proxyPort);
        }

        SdcctHttp11NioProtocol connProtocol = ((SdcctHttp11NioProtocol) conn.getProtocolHandler());
        connProtocol.setExecutor(new SdcctThreadPoolExecutor(Collections.singletonMap(SdcctPropertyNames.HTTP_SERVER_TX_ID, this.txIdGen)));
        connProtocol.setConnectionTimeout(this.connTimeout);
        connProtocol.getEndpoint().setName(this.connEndpointName);
        connProtocol.setKeepAliveTimeout(this.connKeepAliveTimeout);
        connProtocol.setMaxConnections(this.maxConns);
        connProtocol.setMaxThreads(this.maxConnThreads);
        connProtocol.setMinSpareThreads(this.minConnThreads);
    }

    @Override
    protected void configureContext(Context context, ServletContextInitializer[] servletContextInits) {
        context.setCookies(true);
        context.setDistributable(true);
        context.setIgnoreAnnotations(true);
        context.setTldValidation(true);
        context.setUseHttpOnly(false);
        context.setXmlNamespaceAware(true);
        context.setXmlValidation(true);

        context.addApplicationListener(WsContextListener.class.getName());

        ((StandardContext) context).setWorkDir(this.workDir.getAbsolutePath());

        super.configureContext(context, ArrayUtils.add(servletContextInits, 0, new SdcctSessionConfigServletContextInitializer()));
    }

    public File getBaseDirectory() {
        return this.baseDir;
    }

    @Override
    public void setBaseDirectory(File baseDir) {
        super.setBaseDirectory((this.baseDir = baseDir));
    }

    public String getConnectionEndpointName() {
        return this.connEndpointName;
    }

    public void setConnectionEndpointName(String connEndpointName) {
        this.connEndpointName = connEndpointName;
    }

    @Nonnegative
    public int getConnectionKeepAliveTimeout() {
        return this.connKeepAliveTimeout;
    }

    public void setConnectionKeepAliveTimeout(@Nonnegative int connKeepAliveTimeout) {
        this.connKeepAliveTimeout = connKeepAliveTimeout;
    }

    @Nonnegative
    public int getConnectionTimeout() {
        return this.connTimeout;
    }

    public void setConnectionTimeout(@Nonnegative int connTimeout) {
        this.connTimeout = connTimeout;
    }

    @Nonnegative
    public int getMaxConnections() {
        return this.maxConns;
    }

    public void setMaxConnections(@Nonnegative int maxConns) {
        this.maxConns = maxConns;
    }

    @Nonnegative
    public int getMaxConnectionThreads() {
        return this.maxConnThreads;
    }

    public void setMaxConnectionThreads(@Nonnegative int maxConnThreads) {
        this.maxConnThreads = maxConnThreads;
    }

    @Nonnegative
    public int getMinConnectionThreads() {
        return this.minConnThreads;
    }

    public void setMinConnectionThreads(@Nonnegative int minConnThreads) {
        this.minConnThreads = minConnThreads;
    }

    @Nullable
    public String getProxyHost() {
        return this.proxyHost;
    }

    public void setProxyHost(@Nullable String proxyHost) {
        this.proxyHost = proxyHost;
    }

    @Nonnegative
    public int getProxyPort() {
        return this.proxyPort;
    }

    public void setProxyPort(@Nonnegative int proxyPort) {
        this.proxyPort = proxyPort;
    }

    public Tomcat getTomcat() {
        return this.tomcat;
    }

    public TxIdGenerator getTxIdGenerator() {
        return this.txIdGen;
    }

    public void setTxIdGenerator(TxIdGenerator txIdGen) {
        this.txIdGen = txIdGen;
    }

    public void setValves(Valve ... valves) {
        this.setContextValves(Stream.of(valves).sorted(AnnotationAwareOrderComparator.INSTANCE).collect(Collectors.toList()));
    }

    public File getWorkDirectory() {
        return this.workDir;
    }

    public void setWorkDirectory(File workDir) {
        this.workDir = workDir;
    }
}
