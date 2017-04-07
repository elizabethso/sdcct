package gov.hhs.onc.sdcct.web.ws.controller.impl;

import gov.hhs.onc.sdcct.context.SdcctPropertyNames;
import gov.hhs.onc.sdcct.logging.impl.TxIdGenerator;
import gov.hhs.onc.sdcct.utils.SdcctStringUtils;
import javax.annotation.Nullable;
import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.StringUtils;
import org.apache.cxf.Bus;
import org.apache.cxf.BusFactory;
import org.apache.cxf.transport.DestinationFactoryManager;
import org.apache.cxf.transport.http.AbstractHTTPDestination;
import org.apache.cxf.transport.http.DestinationRegistry;
import org.apache.cxf.transport.http.HTTPTransportFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.web.context.ServletConfigAware;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.Controller;

public class WebServiceController implements Controller, ServletConfigAware {
    private final static String HTTP_TRANSPORT_NS_URI = HTTPTransportFactory.DEFAULT_NAMESPACES.get(0);

    private final static String ENDPOINT_ADDR_SERVLET_REQ_ATTR_NAME = "org.apache.cxf.transport.endpoint.address";

    private final static Logger LOGGER = LoggerFactory.getLogger(WebServiceController.class);

    private Bus bus;
    private String baseUrlPath;
    private ServletConfig servletConfig;
    private ServletContext servletContext;
    private TxIdGenerator txIdGen;

    @Nullable
    @Override
    public ModelAndView handleRequest(HttpServletRequest servletReq, HttpServletResponse servletResp) throws Exception {
        String destPath = StringUtils.prependIfMissing(
            StringUtils.removeStart(((String) servletReq.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE)), this.baseUrlPath),
            SdcctStringUtils.SLASH), txId;
        DestinationRegistry destReg =
            ((HTTPTransportFactory) this.bus.getExtension(DestinationFactoryManager.class).getDestinationFactoryForUri(HTTP_TRANSPORT_NS_URI)).getRegistry();
        AbstractHTTPDestination dest = destReg.getDestinationForPath(destPath, true);

        if (dest == null) {
            dest = destReg.checkRestfulRequest(destPath);
        }

        if (dest != null) {
            servletReq.setAttribute(ENDPOINT_ADDR_SERVLET_REQ_ATTR_NAME, (this.baseUrlPath + dest.getEndpointInfo().getAddress()));

            try {
                MDC.put(SdcctPropertyNames.WS_SERVER_TX_ID, (txId = this.txIdGen.generateId().toString()));

                servletReq.setAttribute(SdcctPropertyNames.HTTP_SERVER_TX_ID, txId);

                BusFactory.setThreadDefaultBus(bus);

                dest.invoke(this.servletConfig, this.servletContext, servletReq, servletResp);
            } finally {
                BusFactory.setThreadDefaultBus(null);

                MDC.remove(SdcctPropertyNames.WS_SERVER_TX_ID);
            }
        } else {
            // TODO: improve error handling
            LOGGER.error(String.format("Unable to determine CXF destination (path=%s) for request.", destPath));

            servletResp.setStatus(HttpStatus.NOT_FOUND.value());
        }

        return null;
    }

    public String getBaseUrlPath() {
        return this.baseUrlPath;
    }

    public void setBaseUrlPath(String baseUrlPath) {
        this.baseUrlPath = baseUrlPath;
    }

    public Bus getBus() {
        return this.bus;
    }

    public void setBus(Bus bus) {
        this.bus = bus;
    }

    @Override
    public void setServletConfig(ServletConfig servletConfig) {
        this.servletContext = (this.servletConfig = servletConfig).getServletContext();
    }

    public TxIdGenerator getTxIdGenerator() {
        return this.txIdGen;
    }

    public void setTxIdGenerator(TxIdGenerator txIdGen) {
        this.txIdGen = txIdGen;
    }
}
