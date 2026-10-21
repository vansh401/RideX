package com.ridex.gateway;

import java.util.List;

import org.springframework.http.HttpMethod;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/** "METHOD:/path/**" rules, parsed once. Method "*" matches any method. */
final class PathRules {

    private record Rule(String method, PathPattern pattern) {
    }

    private final List<Rule> rules;

    PathRules(List<String> entries) {
        PathPatternParser parser = new PathPatternParser();
        this.rules = entries.stream().map(entry -> {
            int colon = entry.indexOf(':');
            if (colon > 0 && entry.charAt(colon + 1) == '/') {
                return new Rule(entry.substring(0, colon), parser.parse(entry.substring(colon + 1)));
            }
            return new Rule("*", parser.parse(entry));   // a bare path means any method
        }).toList();
    }

    boolean matches(HttpMethod method, String path) {
        var container = org.springframework.http.server.PathContainer.parsePath(path);
        for (Rule rule : rules) {
            boolean methodOk = "*".equals(rule.method()) || rule.method().equalsIgnoreCase(method.name());
            if (methodOk && rule.pattern().matches(container)) {
                return true;
            }
        }
        return false;
    }
}
