package com.relay.run;

/**
 * Run lifecycle states. Terminal: {@link #succeeded}, {@link #failed}, {@link #cancelled}.
 */
public enum RunStatus {
    queued,
    running,
    waiting_approval,
    succeeded,
    failed,
    cancelled;

    public boolean isTerminal() {
        return this == succeeded || this == failed || this == cancelled;
    }
}
