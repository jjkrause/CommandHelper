package com.laytonsmith.abstraction.events;

import com.laytonsmith.abstraction.MCPlayer;
import com.laytonsmith.abstraction.MCWorld;
import com.laytonsmith.core.events.BindableEvent;

public interface MCWorldChangedEvent extends BindableEvent {

	MCPlayer getPlayer();

	MCWorld getFrom();

	MCWorld getTo();
}
