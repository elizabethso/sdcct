package gov.hhs.onc.sdcct.web.testcases.ihe.ws.impl;

import gov.hhs.onc.sdcct.api.SdcctIssueSeverity;
import gov.hhs.onc.sdcct.testcases.ihe.IheTestcase;
import gov.hhs.onc.sdcct.testcases.ihe.results.IheTestcaseResult;
import gov.hhs.onc.sdcct.testcases.ihe.results.IheTestcaseResultHandler;
import gov.hhs.onc.sdcct.testcases.ihe.submissions.IheTestcaseSubmission;
import gov.hhs.onc.sdcct.testcases.utils.SdcctTestcaseUtils;
import gov.hhs.onc.sdcct.web.tomcat.impl.TomcatRequest;
import gov.hhs.onc.sdcct.web.tomcat.impl.TomcatResponse;
import gov.hhs.onc.sdcct.web.tomcat.utils.SdcctHttpEventUtils;
import gov.hhs.onc.sdcct.web.tomcat.utils.SdcctTomcatUtils;
import gov.hhs.onc.sdcct.ws.utils.SdcctWsPropertyUtils;
import java.io.OutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.apache.cxf.interceptor.Fault;
import org.apache.cxf.io.CacheAndWriteOutputStream;
import org.apache.cxf.message.Message;
import org.apache.cxf.transport.http.AbstractHTTPDestination;

public abstract class AbstractServerIheTestcaseOutCallback<T extends IheTestcase, U extends IheTestcaseSubmission<T>, V extends IheTestcaseResult<T, U>>
    extends AbstractIheTestcaseOutCallback<T, U, V> {
    protected IheTestcaseResultHandler resultHandler;

    protected AbstractServerIheTestcaseOutCallback(IheTestcaseResultHandler resultHandler, Message msg, Class<V> resultClass, String resultPropName) {
        super(msg, resultClass, resultPropName);

        this.resultHandler = resultHandler;
    }

    protected void onCloseInternal(CacheAndWriteOutputStream cachedStream) throws Exception {
        try {
            Message inMsg = this.msg.getExchange().getInMessage();
            Object resultPropValue = inMsg.getContextualProperty(this.resultPropName);

            if (resultPropValue != null) {
                V result = SdcctTestcaseUtils.addWsResponseEvent(inMsg, this.resultPropName, this.resultClass, this.msg);
                U submission = result.getSubmission();
                T testcase = submission.getTestcase();

                Fault fault = ((Fault) this.msg.getContent(Exception.class));
                result.setFault(fault);

                if (result.hasFault()) {
                    if ((testcase != null) && testcase.isNegative()) {
                        result.getMessages(SdcctIssueSeverity.INFORMATION)
                            .add(String.format(
                                "Please check that the web service response event payload contains a SOAP fault (message=%s) that corresponds to the associated testcase (id=%s) description.",
                                fault.getMessage(), testcase.getId()));
                    } else {
                        result.getMessages(SdcctIssueSeverity.INFORMATION)
                            .add(String.format(
                                "Please check that the web service response event payload (msg=%s) corresponds to what is expected in the associated testcase (id=%s) description.",
                                fault.getMessage(), ((testcase != null) ? testcase.getId() : "None")));
                    }
                }

                TomcatRequest servletReq =
                    SdcctTomcatUtils.unwrapRequest(SdcctWsPropertyUtils.getProperty(inMsg, AbstractHTTPDestination.HTTP_REQUEST, HttpServletRequest.class));
                TomcatResponse servletResp = SdcctTomcatUtils
                    .unwrapResponse(SdcctWsPropertyUtils.getProperty(this.msg, AbstractHTTPDestination.HTTP_RESPONSE, HttpServletResponse.class));

                result.setHttpResponseEvent(SdcctHttpEventUtils.createHttpResponseEvent(servletReq, servletResp));

                // noinspection ConstantConditions
                if (!result.hasMessages(SdcctIssueSeverity.FATAL) && !result.hasMessages(SdcctIssueSeverity.ERROR) && submission.hasTestcase() &&
                    (!testcase.isNegative() && (fault == null))) {
                    result.setSuccess(true);
                }

                this.resultHandler.addResult(result);
            }
        } finally {
            OutputStream outStream = cachedStream.getFlowThroughStream();

            cachedStream.lockOutputStream();
            cachedStream.resetOut(null, false);

            this.msg.setContent(OutputStream.class, outStream);
        }
    }
}
