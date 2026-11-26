/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.forms.impl;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Modified;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sends plain-text mail over SMTP, with no mail library: EHLO, STARTTLS (or TLS from the start on port
 * 465), AUTH PLAIN when a user is set, one message per call. Configured by {@code io.sensible.studio.forms.impl.Mail}
 * ({@code host}, {@code port} (25 / 587 / 465), {@code starttls} (true), {@code user}, {@code password},
 * {@code from}); without a host nothing is sent and the after-submit action says so.
 */
@Component(service = Mail.class, configurationPolicy = ConfigurationPolicy.OPTIONAL)
public class Mail {

    private static final Logger LOG = LoggerFactory.getLogger(Mail.class);
    private static final int TIMEOUT_MS = 15000;

    private volatile String host;
    private volatile int port = 587;
    private volatile boolean starttls = true;
    private volatile String user;
    private volatile String password;
    private volatile String from = "forms@localhost";

    @Activate
    @Modified
    protected void activate(Map<String, Object> props) {
        host = AfterSubmit.blank(str(props.get("host")));
        port = props.get("port") == null ? 587 : Integer.parseInt(String.valueOf(props.get("port")));
        starttls = props.get("starttls") == null || Boolean.parseBoolean(String.valueOf(props.get("starttls")));
        user = AfterSubmit.blank(str(props.get("user")));
        password = str(props.get("password"));
        String f = AfterSubmit.blank(str(props.get("from")));
        from = f == null ? "forms@localhost" : f;
    }

    public boolean isConfigured() {
        return host != null;
    }

    public void send(List<String> to, String subject, String text) throws IOException {
        if (!isConfigured()) {
            throw new IOException("No mail server configured");
        }
        Socket socket = port == 465 ? SSLSocketFactory.getDefault().createSocket(host, port) : new Socket(host, port);
        socket.setSoTimeout(TIMEOUT_MS);
        try {
            Smtp s = new Smtp(socket);
            s.expect(220);
            String me = InetAddress.getLocalHost().getHostName();
            String ehlo = s.command("EHLO " + me, 250);
            if (starttls && port != 465 && ehlo.contains("STARTTLS")) {
                s.command("STARTTLS", 220);
                SSLSocket tls = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(socket, host, port, true);
                tls.startHandshake();
                s = new Smtp(tls);
                s.command("EHLO " + me, 250);
            }
            if (user != null) {
                String token = Base64.getEncoder().encodeToString(("\0" + user + "\0" + (password == null ? "" : password)).getBytes(StandardCharsets.UTF_8));
                s.command("AUTH PLAIN " + token, 235);
            }
            s.command("MAIL FROM:<" + address(from) + ">", 250);
            for (String r : to) {
                s.command("RCPT TO:<" + address(r) + ">", 250, 251);
            }
            s.command("DATA", 354);
            StringBuilder msg = new StringBuilder();
            msg.append("From: ").append(from).append("\r\n");
            msg.append("To: ").append(String.join(", ", to)).append("\r\n");
            msg.append("Subject: ").append(encodeHeader(subject)).append("\r\n");
            msg.append("Date: ").append(java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(java.time.ZonedDateTime.now())).append("\r\n");
            msg.append("MIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: 8bit\r\n");
            msg.append("X-Mailer: Sensible Studio Forms\r\n\r\n");
            for (String line : text.split("\r?\n", -1)) {
                msg.append(line.startsWith(".") ? "." + line : line).append("\r\n");
            }
            s.command(msg + ".", 250);
            s.command("QUIT", 221);
        } finally {
            socket.close();
        }
    }

    /** "Name <a@b>" → a@b */
    static String address(String s) {
        int lt = s.indexOf('<');
        int gt = s.indexOf('>');
        return lt >= 0 && gt > lt ? s.substring(lt + 1, gt).trim() : s.trim();
    }

    static String encodeHeader(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 126) {
                return "=?UTF-8?B?" + Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8)) + "?=";
            }
        }
        return s;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /** One SMTP conversation: a command, then the reply's lines until the last ("250 ", not "250-"). */
    private static final class Smtp {
        private final BufferedReader in;
        private final OutputStream out;

        Smtp(Socket socket) throws IOException {
            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
            out = socket.getOutputStream();
        }

        String command(String line, int... ok) throws IOException {
            out.write((line + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            return expect(ok);
        }

        String expect(int... ok) throws IOException {
            StringBuilder reply = new StringBuilder();
            String line;
            do {
                line = in.readLine();
                if (line == null) {
                    throw new IOException("The mail server closed the connection");
                }
                reply.append(line).append('\n');
            } while (line.length() >= 4 && line.charAt(3) == '-');
            int code = line.length() >= 3 ? Integer.parseInt(line.substring(0, 3)) : -1;
            for (int o : ok) {
                if (o == code) {
                    return reply.toString();
                }
            }
            LOG.debug("SMTP refused: {}", reply);
            throw new IOException("The mail server answered " + line);
        }
    }
}
