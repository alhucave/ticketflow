package com.ticketflow.usecase;

import com.ticketflow.domain.model.Event;
import com.ticketflow.domain.model.Inventory;

/** An event together with a snapshot of its current inventory. */
public record EventDetails(Event event, Inventory inventory) {}
