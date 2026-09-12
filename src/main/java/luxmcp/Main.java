package luxmcp;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import luxmcp.docs.DocIndex;
import luxmcp.ha.HomeAssistant;
import luxmcp.lux.HeatPump;
import luxmcp.lux.LuxtronikClient;
import luxmcp.lux.Registers;
import luxmcp.lux.Snapshot;
import luxmcp.lux.WritePolicy;
import luxmcp.mcp.LuxtronikTools;
import org.eclipse.jetty.ee10.servlet.FilterHolder;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.EnumSet;
import java.util.concurrent.CountDownLatch;

/**
 * Entry point: {@code java -jar luxmcp.jar --host innotecwp [--allow-write] [--http 8765 --token ...]}.
 */
public final class Main {

    public static final String VERSION = "0.1.0";

    private static final String INSTRUCTIONS = """
            This server talks directly to the Luxtronik 2.x controller of a heat pump over its config interface \
            (TCP port 8889). Register names follow python-luxtronik: ID_WEB_* are measured/calculated values, \
            ID_Einst_* / ID_Ba_* / ID_Soll* are settings, ID_Visi_* are UI visibility flags. Names are German \
            abbreviations: TVL Vorlauf = flow (supply) temperature, TRL Ruecklauf = return temperature, TA = outdoor \
            temperature, TBW/BW/BWS = domestic hot water (Brauchwarmwasser), TWE/TWA = heat source (brine) in/out, \
            VD Verdichter = compressor, ZWE = electric backup heater, HUP = heating circulation pump, VBO = brine pump, \
            ZIP = DHW circulation pump, BUP = DHW diverter valve, WMZ = heat meter, MK1..3 = mixing circuits, \
            Hz = heating, Ku/Kuehl = cooling, Sw = swimming pool, EVU = utility lock signal, BZ = operating state, \
            akt = current, Soll = setpoint, Ist = actual, Einst = setting, HK = heating curve (Heizkurve): \
            E = end point (Endpunkt, return setpoint at -20 °C), ANH = parallel shift (Anhebung), ABS = night setback.
            Start with luxtronik_get_status. Values are cached for a few seconds; pass refresh=true when timing matters. \
            The controller is slow and must not be polled aggressively. Writes (if enabled) are allowlisted, \
            range-checked, rate-limited and verified by read-back; never write experimentally, the controller's \
            flash memory has limited erase cycles. Confirm intended changes with the user before calling \
            luxtronik_set_parameter.""";

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        // stdout belongs to the stdio transport; slf4j-simple logs to stderr by default.
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel",
                System.getProperty("org.slf4j.simpleLogger.defaultLogLevel", env("LUXMCP_LOG", "info")));
        System.setProperty("org.slf4j.simpleLogger.showDateTime", "true");
        System.setProperty("org.slf4j.simpleLogger.dateTimeFormat", "yyyy-MM-dd HH:mm:ss");
        System.setProperty("org.slf4j.simpleLogger.log.org.eclipse.jetty", "warn");

        Options o;
        try {
            o = Options.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("luxmcp: " + e.getMessage());
            System.err.println();
            System.err.println(Options.USAGE);
            System.exit(2);
            return;
        }
        if (o.help) {
            System.out.println(Options.USAGE);
            return;
        }
        if (o.host == null) {
            System.err.println("luxmcp: --host is required (or set LUXMCP_HOST)");
            System.err.println(Options.USAGE);
            System.exit(2);
        }

        Registers registers = Registers.load();
        LuxtronikClient client = new LuxtronikClient(o.host, o.port);
        WritePolicy policy = o.allowWrite
                ? (o.allowlist != null ? WritePolicy.fromFile(o.allowlist) : WritePolicy.defaults())
                : WritePolicy.disabled();
        HeatPump pump = new HeatPump(client, registers, Duration.ofSeconds(o.cacheSeconds), policy, o.auditLog);
        HomeAssistant ha = o.haUrl != null && o.haToken != null ? new HomeAssistant(o.haUrl, o.haToken) : null;
        Path docsDir = o.docs;
        if (docsDir == null && java.nio.file.Files.isDirectory(Path.of("docs", "manuals"))) {
            docsDir = Path.of("docs", "manuals");
        }
        DocIndex docs = docsDir != null ? DocIndex.loadAsync(docsDir) : null;

        log.info("luxmcp {} - controller {}:{}, registers from {}, writes {}, HA history {}, manuals {}", VERSION, o.host, o.port,
                registers.sourceInfo(), policy.enabled() ? "ENABLED (" + policy.rules().size() + " allowlisted)" : "disabled",
                ha != null ? "via " + ha.baseUrl() : "off", docsDir != null ? "from " + docsDir.toAbsolutePath() : "off");

        if (o.check) {
            Snapshot s = pump.snapshot(true);
            System.out.println("Connected to " + o.host + ":" + o.port + " - " + s.format("ID_WEB_Code_WP_akt") + ", firmware "
                    + s.firmware() + ", " + s.length(luxmcp.lux.RegisterKind.PARAMETERS) + " parameters, "
                    + s.length(luxmcp.lux.RegisterKind.CALCULATIONS) + " calculations, "
                    + s.length(luxmcp.lux.RegisterKind.VISIBILITIES) + " visibilities");
            System.out.println("Flow " + s.format("ID_WEB_Temperatur_TVL") + ", return " + s.format("ID_WEB_Temperatur_TRL")
                    + ", outdoor " + s.format("ID_WEB_Temperatur_TA") + ", hot water " + s.format("ID_WEB_Temperatur_TBW")
                    + ", status " + s.format("ID_WEB_HauptMenuStatus_Zeile1") + " / " + s.format("ID_WEB_HauptMenuStatus_Zeile3"));
            return;
        }

        if (docs != null) {
            docs.await(); // fail fast on unreadable manuals; also keeps tool descriptions complete
        }
        LuxtronikTools tools = new LuxtronikTools(pump, ha, docs);
        McpJsonMapper mapper = McpJsonDefaults.getMapper();

        if (o.httpPort > 0) {
            runHttp(o, mapper, tools);
        } else {
            runStdio(mapper, tools);
        }
    }

    private static McpSyncServer buildServer(io.modelcontextprotocol.spec.McpServerTransportProvider transport,
                                             McpJsonMapper mapper, LuxtronikTools tools) {
        return McpServer.sync(transport)
                .serverInfo("luxtronik-mcp", VERSION)
                .instructions(INSTRUCTIONS)
                .jsonMapper(mapper)
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .tools(tools.specifications())
                .build();
    }

    private static McpSyncServer buildServer(io.modelcontextprotocol.spec.McpStreamableServerTransportProvider transport,
                                             McpJsonMapper mapper, LuxtronikTools tools) {
        return McpServer.sync(transport)
                .serverInfo("luxtronik-mcp", VERSION)
                .instructions(INSTRUCTIONS)
                .jsonMapper(mapper)
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .tools(tools.specifications())
                .build();
    }

    private static void runStdio(McpJsonMapper mapper, LuxtronikTools tools) throws InterruptedException {
        StdioServerTransportProvider transport = new StdioServerTransportProvider(mapper);
        McpSyncServer server = buildServer(transport, mapper, tools);
        log.info("stdio transport ready");
        CountDownLatch done = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                server.closeGracefully();
            } catch (Exception ignored) {
                // exiting anyway
            }
            done.countDown();
        }));
        done.await();
    }

    private static void runHttp(Options o, McpJsonMapper mapper, LuxtronikTools tools) throws Exception {
        HttpServletStreamableServerTransportProvider transport = HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(mapper)
                .mcpEndpoint("/mcp")
                .keepAliveInterval(Duration.ofSeconds(30))
                .build();
        McpSyncServer server = buildServer(transport, mapper, tools);

        Server jetty = new Server();
        ServerConnector connector = new ServerConnector(jetty);
        connector.setHost(o.bind);
        connector.setPort(o.httpPort);
        jetty.addConnector(connector);

        ServletContextHandler ctx = new ServletContextHandler();
        ctx.setContextPath("/");
        if (o.token != null) {
            ctx.addFilter(new FilterHolder(new BearerTokenFilter(o.token)), "/*", EnumSet.of(DispatcherType.REQUEST));
        } else if (!"127.0.0.1".equals(o.bind) && !"localhost".equals(o.bind)) {
            log.warn("HTTP transport bound to {} WITHOUT --token: anyone on the network can control the heat pump", o.bind);
        }
        ServletHolder holder = new ServletHolder("mcp", transport);
        holder.setAsyncSupported(true);
        ctx.addServlet(holder, "/mcp");
        ctx.addServlet(holder, "/mcp/*");
        jetty.setHandler(ctx);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                server.closeGracefully();
                jetty.stop();
            } catch (Exception ignored) {
                // exiting anyway
            }
        }));
        jetty.start();
        log.info("Streamable HTTP transport listening on http://{}:{}/mcp{}", o.bind, o.httpPort, o.token != null ? " (bearer token required)" : "");
        jetty.join();
    }

    /** Minimal bearer token check for the HTTP transport. */
    static final class BearerTokenFilter implements Filter {
        private final byte[] expected;

        BearerTokenFilter(String token) {
            this.expected = token.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
            HttpServletRequest req = (HttpServletRequest) request;
            HttpServletResponse resp = (HttpServletResponse) response;
            String auth = req.getHeader("Authorization");
            if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)
                    && MessageDigest.isEqual(auth.substring(7).trim().getBytes(java.nio.charset.StandardCharsets.UTF_8), expected)) {
                chain.doFilter(request, response);
                return;
            }
            resp.setHeader("WWW-Authenticate", "Bearer");
            resp.sendError(HttpServletResponse.SC_UNAUTHORIZED, "missing or invalid bearer token");
        }
    }

    static String env(String name, String def) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? def : v;
    }

    /** Command line / environment options. */
    static final class Options {
        static final String USAGE = """
                Usage: java -jar luxmcp.jar --host <controller> [options]

                Connection:
                  --host <name|ip>       Luxtronik controller (env LUXMCP_HOST)
                  --port <n>             CFI port, default 8889 (env LUXMCP_PORT)
                  --cache-seconds <n>    how long a snapshot is reused, default 10

                Transport (default: stdio for Claude Desktop / Claude Code):
                  --http <port>          serve Streamable HTTP on /mcp instead of stdio
                  --bind <addr>          bind address for --http, default 127.0.0.1 (env LUXMCP_BIND)
                  --token <secret>       require 'Authorization: Bearer <secret>' on --http (env LUXMCP_TOKEN)

                Writing:
                  --allow-write          register luxtronik_set_parameter (env LUXMCP_ALLOW_WRITE=true)
                  --allowlist <file>     replace the built-in allowlist (lines: NAME [min max] [# note])
                  --audit-log <file>     append every write to this file (env LUXMCP_AUDIT_LOG)

                Manuals (optional):
                  --docs <dir>           folder with manufacturer manuals (PDF/txt/md) to index for
                                         luxtronik_search_docs; default ./docs/manuals if present (env LUXMCP_DOCS)

                Home Assistant history (optional):
                  --ha-url <url>         e.g. http://homeassistant.local:8123 (env HASS_URL)
                  --ha-token <token>     long-lived access token (env HASS_TOKEN)

                Other:
                  --check                connect, print a one-line status and exit
                  --help""";

        String host = env("LUXMCP_HOST", null);
        int port = Integer.parseInt(env("LUXMCP_PORT", String.valueOf(LuxtronikClient.DEFAULT_PORT)));
        int cacheSeconds = 10;
        int httpPort = 0;
        String bind = env("LUXMCP_BIND", "127.0.0.1");
        String token = env("LUXMCP_TOKEN", null);
        boolean allowWrite = Boolean.parseBoolean(env("LUXMCP_ALLOW_WRITE", "false"));
        Path allowlist;
        Path auditLog = env("LUXMCP_AUDIT_LOG", null) == null ? null : Path.of(env("LUXMCP_AUDIT_LOG", null));
        Path docs = env("LUXMCP_DOCS", null) == null ? null : Path.of(env("LUXMCP_DOCS", null));
        String haUrl = env("HASS_URL", null);
        String haToken = env("HASS_TOKEN", null);
        boolean check;
        boolean help;

        static Options parse(String[] args) {
            Options o = new Options();
            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                switch (a) {
                    case "--host": o.host = value(args, ++i, a); break;
                    case "--port": o.port = Integer.parseInt(value(args, ++i, a)); break;
                    case "--cache-seconds": o.cacheSeconds = Integer.parseInt(value(args, ++i, a)); break;
                    case "--http": o.httpPort = Integer.parseInt(value(args, ++i, a)); break;
                    case "--bind": o.bind = value(args, ++i, a); break;
                    case "--token": o.token = value(args, ++i, a); break;
                    case "--allow-write": o.allowWrite = true; break;
                    case "--allowlist": o.allowlist = Path.of(value(args, ++i, a)); break;
                    case "--audit-log": o.auditLog = Path.of(value(args, ++i, a)); break;
                    case "--docs": o.docs = Path.of(value(args, ++i, a)); break;
                    case "--ha-url": o.haUrl = value(args, ++i, a); break;
                    case "--ha-token": o.haToken = value(args, ++i, a); break;
                    case "--stdio": o.httpPort = 0; break;
                    case "--check": o.check = true; break;
                    case "--help": case "-h": o.help = true; break;
                    default:
                        throw new IllegalArgumentException("unknown option " + a);
                }
            }
            if (o.host != null) {
                // tolerate "http://innotecwp:8889" style values
                String h = o.host.replaceFirst("^[a-zA-Z]+://", "");
                int slash = h.indexOf('/');
                if (slash >= 0) {
                    h = h.substring(0, slash);
                }
                int colon = h.lastIndexOf(':');
                if (colon > 0 && h.substring(colon + 1).matches("\\d+")) {
                    o.port = Integer.parseInt(h.substring(colon + 1));
                    h = h.substring(0, colon);
                }
                o.host = h;
            }
            return o;
        }

        private static String value(String[] args, int i, String opt) {
            if (i >= args.length) {
                throw new IllegalArgumentException(opt + " needs a value");
            }
            return args[i];
        }
    }
}
