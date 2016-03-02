package gov.hhs.onc.sdcct.form.receiver.impl;

import gov.hhs.onc.sdcct.form.impl.AbstractFormService;
import gov.hhs.onc.sdcct.form.receiver.FormReceiver;
import gov.hhs.onc.sdcct.rfd.AnyXMLContentType;
import javax.annotation.Nullable;
import org.springframework.stereotype.Component;

// TODO: implement
@Component("formReceiverImpl")
public class FormReceiverImpl extends AbstractFormService implements FormReceiver {
    @Nullable
    @Override
    public String submitForm(AnyXMLContentType body) throws Exception {
        return "Received form....";
    }
}
