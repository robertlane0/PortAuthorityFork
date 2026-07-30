package com.aaronjwood.portauthority.response;

import java.io.Serializable;

public class WanIpResult implements Serializable {
    private final String ipv4;
    private final String ipv6;

    public WanIpResult(String ipv4, String ipv6) {
        this.ipv4 = ipv4;
        this.ipv6 = ipv6;
    }

    public String getIpv4() {
        return ipv4;
    }

    public String getIpv6() {
        return ipv6;
    }

    public boolean hasIpv4() {
        return ipv4 != null;
    }

    public boolean hasIpv6() {
        return ipv6 != null;
    }
}
