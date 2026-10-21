package com.ridex.platform.web;

import java.io.IOException;
import java.net.InetAddress;

import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Says WHICH copy of the service answered: an X-Instance response header holding the pod name (Kubernetes sets the HOSTNAME of a pod to its
 * name). With several replicas behind one address this is how you SEE load balancing, and how a bug report can say "it only fails on pod
 * pricing-7d9f-abc12". It reveals nothing a caller can abuse: the name is meaningless outside the cluster.
 */
public class InstanceHeaderFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Instance";

    private final String instance;

    public InstanceHeaderFilter() {
        this.instance = instanceName();
    }

    static String instanceName() {
        String hostname = System.getenv("HOSTNAME");
        if (hostname != null && !hostname.isBlank()) {
            return hostname;
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (IOException e) {
            return "unknown";
        }
    }

    public String instance() {
        return instance;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader(HEADER, instance);   // set first: it must be there even when the request fails or is rejected
        chain.doFilter(request, response);
    }
}
