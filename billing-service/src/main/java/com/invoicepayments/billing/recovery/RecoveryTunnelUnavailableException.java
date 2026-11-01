package com.invoicepayments.billing.recovery;

import java.util.Set;

/**
 * The private route to the laptop database (or the parameters that open it) is not available yet: tunnel start or
 * login failed, the laptop peer is unreachable (asleep, offline), or Parameter Store could not be read. Retryable, like
 * a database outage. The message is a fixed reason code, never tool output or credentials.
 */
public final class RecoveryTunnelUnavailableException extends RuntimeException {
    static final Set<String> REASONS=Set.of("TUNNEL_START_FAILED","TUNNEL_LOGIN_FAILED","PEER_UNREACHABLE","PARAMETERS_UNAVAILABLE");
    public RecoveryTunnelUnavailableException(String reason) {
        super(REASONS.contains(reason)?reason:"TUNNEL_START_FAILED",null,false,true);
    }
}
