package com.ridex.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * What never goes into a log line: passwords, tokens, keys, one-time codes, card or bank data, email addresses, request bodies.
 *
 * Logs are copied to other systems, kept for months and read by many people, so a secret that reaches a log is leaked for good. This test
 * reads every logging call in the main code of every service and looks at what is being PRINTED (the arguments, and the words of the message
 * that precede a placeholder). It is a net for the obvious mistakes, not a proof: reviewers still have to look.
 */
class LogHygieneTest {

    private static final Path BACKEND = Path.of("..");

    private static final Pattern LOG_CALL = Pattern.compile("\\blog(?:ger)?\\.(?:trace|debug|info|warn|error)\\(");
    private static final Pattern MASKED = Pattern.compile("LogContext\\.maskEmail\\((?:[^()]|\\([^()]*\\))*\\)");
    private static final Pattern STRING_LITERAL = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"");

    /** Words that must not be printed as a value. */
    private static final Pattern SENSITIVE = Pattern.compile(
            "(?i)(password|passwd|secret|\\botp\\b|otpcode|verificationcode|apikey|api_key|privatekey|private_key|authorization|bearer|"
                    + "\\bcvv\\b|cardnumber|accountnumber|\\biban\\b|\\btoken\\b|accesstoken|refreshtoken|rawtoken|email|phone|"
                    + "getbody|requestbody|\\bpayload\\b|\\bjwt\\b)");

    private static List<Path> mainSources() throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> modules = Files.list(BACKEND)) {
            for (Path module : modules.filter(Files::isDirectory).toList()) {
                Path main = module.resolve("src/main/java");
                if (Files.isDirectory(main)) {
                    try (Stream<Path> walk = Files.walk(main)) {
                        walk.filter(p -> p.toString().endsWith(".java")).forEach(files::add);
                    }
                }
            }
        }
        return files;
    }

    /** The text of the call from "log.x(" to its closing parenthesis. */
    private static String statement(String source, int openParen) {
        int depth = 0;
        boolean inString = false;
        for (int i = openParen; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '"' && source.charAt(i - 1) != '\\') {
                inString = !inString;
            } else if (!inString && c == '(') {
                depth++;
            } else if (!inString && c == ')' && --depth == 0) {
                return source.substring(openParen, i + 1);
            }
        }
        return source.substring(openParen);
    }

    static List<String> findings(String source, String where) {
        List<String> found = new ArrayList<>();
        Matcher call = LOG_CALL.matcher(source);
        while (call.find()) {
            String text = statement(source, call.end() - 1);
            int line = (int) source.substring(0, call.start()).chars().filter(c -> c == '\n').count() + 1;
            // 1. what is printed: everything except the fixed words of the message
            String arguments = STRING_LITERAL.matcher(text).replaceAll("\"\"");
            arguments = MASKED.matcher(arguments).replaceAll("masked");   // LogContext.maskEmail(x) is the one approved way to mention an address
            Matcher bad = SENSITIVE.matcher(arguments);
            if (bad.find()) {
                found.add(where + ":" + line + " prints '" + bad.group() + "': " + text.replaceAll("\\s+", " "));
                continue;
            }
            // 2. a message like "password: {}" or "token={}" prints the value that follows (unless that is only an exception class name)
            if (arguments.contains("getSimpleName()")) {
                continue;
            }
            Matcher literal = STRING_LITERAL.matcher(text);
            while (literal.find()) {
                String message = literal.group();
                if (Pattern.compile("(?i)(password|secret|\\botp\\b|code|token|authorization|key)\\s*[:=]\\s*\\{\\}").matcher(message).find()) {
                    found.add(where + ":" + line + " message prints a secret-looking value: " + message);
                }
            }
        }
        return found;
    }

    @Test
    void noLoggingCallPrintsASecretOrPersonalValue() throws IOException {
        assumeThat(Files.isDirectory(BACKEND.resolve("platform"))).as("run from the architecture-tests module").isTrue();
        List<String> violations = new ArrayList<>();
        for (Path file : mainSources()) {
            violations.addAll(findings(Files.readString(file), BACKEND.relativize(file).toString()));
        }
        assertThat(violations).as("log lines that print something sensitive").isEmpty();
    }

    // ------------------------------------------------------------------ the net itself must catch what it is for

    @Test
    void theCheckRecognisesTheMistakesItIsMeantToCatch() {
        assertThat(findings("log.info(\"Login for {}\", request.password());", "x")).hasSize(1);
        assertThat(findings("log.warn(\"Bad token {}\", token);", "x")).hasSize(1);
        assertThat(findings("log.debug(\"Sent code to {}\", user.getEmail());", "x")).hasSize(1);
        assertThat(findings("log.info(\"otp={}\", value);", "x")).hasSize(1);
        assertThat(findings("log.info(\"Body: {}\", exchange.getRequest().getBody());", "x")).hasSize(1);
    }

    @Test
    void theCheckLeavesHarmlessLinesAlone() {
        assertThat(findings("log.debug(\"Ignoring invalid bearer token: {}\", e.getClass().getSimpleName());", "x")).isEmpty();
        assertThat(findings("log.info(\"Ride status changed: {} -> {}\", from, to);", "x")).isEmpty();
        assertThat(findings("log.warn(\"Payment {} failed\", payment.getId());", "x")).isEmpty();
        assertThat(findings("log.error(\"Could not send mail to {}\", LogContext.maskEmail(event.toEmail()), ex);", "x")).isEmpty();
    }
}
