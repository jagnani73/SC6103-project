package sc6103.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Entry point for the console client (README sec. 15.2).
 *
 * <pre>
 *   flight_client --host &lt;server-ip&gt; --port &lt;port&gt;
 *                 [--semantics at-least-once|at-most-once]
 *                 [--drop-request &lt;p&gt;] [--drop-reply &lt;p&gt;]
 *                 [--drop-first-request]
 *                 [--timeout &lt;ms&gt;] [--retries &lt;n&gt;] [--seed &lt;n&gt;]
 * </pre>
 *
 * <p>Wiring only. Every decision of substance belongs to one of the components below, and
 * keeping this class free of them is what lets each be read and reviewed on its own.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        final ClientConfig config;
        try {
            config = ClientConfig.parse(args);
        } catch (IllegalArgumentException e) {
            // parse() signals --help by throwing with usage() as the message, since a
            // method returning a config has no other way out. Distinguish the two so a
            // requested help text exits 0 and a genuine mistake exits 2.
            final boolean helpRequested = ClientConfig.usage().equals(e.getMessage());
            if (helpRequested) {
                System.out.println(e.getMessage());
                System.exit(0);
            }
            System.err.println(e.getMessage());
            System.err.println();
            System.err.println(ClientConfig.usage());
            System.exit(2);
            return;
        }

        // Generated once, here, and fixed for the process lifetime (README sec. 2.3, D5).
        // Identity is deliberately not the socket's address and port: a restarted client
        // reusing an ephemeral port could otherwise be answered from the previous
        // process's reply history, and a NAT rebinding mid-retry would make the server
        // treat a retransmission as a new call.
        final long clientId = RequestReply.newClientId();

        final TraceLog trace = new TraceLog(System.out, clientId);
        final LossSimulator loss = new LossSimulator(config.seed, config.dropRequestRate,
                config.dropReplyRate, config.dropFirstRequest);

        try (UdpChannel channel = new UdpChannel(config.host, config.port, trace)) {
            // Printed before anything is sent, and it carries the seed and the active
            // fault rules. README sec. 24.3 requires a captured log to be
            // self-describing: without these lines a saved trace cannot be replayed or
            // even interpreted, and the report's experiment section is written from
            // exactly these files.
            System.out.println(config.banner(channel.localEndpoint(), clientId));
            System.out.println();

            final RequestReply engine =
                    new RequestReply(channel, config, trace, loss, clientId);

            final BufferedReader in = new BufferedReader(
                    new InputStreamReader(System.in, StandardCharsets.UTF_8));

            new ConsoleUi(engine, config, trace, in, System.out).run();

        } catch (IOException e) {
            // A socket-level failure, not a protocol one: the port is taken, the host is
            // unroutable, or the interface went away. Distinct from a timeout, which is
            // an ordinary and expected outcome the client reports without exiting.
            System.err.println("Network error: " + e.getMessage());
            System.exit(1);
        }
    }
}
