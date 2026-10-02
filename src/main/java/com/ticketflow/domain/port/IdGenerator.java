package com.ticketflow.domain.port;

import com.ticketflow.domain.model.EventId;

/** Output port that supplies new identifiers, keeping randomness out of the core. */
public interface IdGenerator {

    EventId nextEventId();
}
