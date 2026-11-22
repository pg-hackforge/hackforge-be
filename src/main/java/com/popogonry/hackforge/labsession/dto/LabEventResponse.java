package com.popogonry.hackforge.labsession.dto;

import java.time.Instant;

import com.popogonry.hackforge.labsession.domain.LabEventActor;
import com.popogonry.hackforge.labsession.domain.LabStatus;
import com.popogonry.hackforge.labsession.domain.SessionEvent;

/** openapi.yaml `LabEventResponse` */
public record LabEventResponse(LabStatus fromStatus, LabStatus toStatus, LabEventActor actor, Instant at) {

	public static LabEventResponse from(SessionEvent event) {
		return new LabEventResponse(event.getFromStatus(), event.getToStatus(), event.getActor(), event.getCreatedAt());
	}

}
