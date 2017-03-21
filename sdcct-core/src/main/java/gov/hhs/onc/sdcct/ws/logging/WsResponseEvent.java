package gov.hhs.onc.sdcct.ws.logging;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonSubTypes.Type;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import gov.hhs.onc.sdcct.ws.logging.impl.WsResponseEventImpl;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME)
@JsonSubTypes({ @Type(WsResponseEventImpl.class) })
public interface WsResponseEvent extends WsEvent {
}
