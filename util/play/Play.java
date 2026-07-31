/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.bixilon.minosoft.debug.DebugClient;
import de.bixilon.minosoft.debug.DebugClientException;
import de.bixilon.minosoft.debug.DebugDiscovery;
import de.bixilon.minosoft.debug.DebugEndpointDescriptor;
import de.bixilon.minosoft.debug.DebugEndpointRole;
import de.bixilon.minosoft.debug.DebugJson;
import de.bixilon.minosoft.debug.DebugPaths;
import de.bixilon.minosoft.debug.DebugResponse;

import java.io.Console;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.awt.image.BufferedImage;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.util.stream.Collectors;

/** Repository-local launcher built incrementally and invoked by the thin play.sh shim. */
public final class Play {
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
    private static final Pattern SAFE_MANAGED_FILENAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]*");
    private static final Pattern MINECRAFT_VERSION_ID = Pattern.compile("\\\"id\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final String MINOSOFT_MAIN = "de.bixilon.minosoft.Minosoft";
    private static final String FABRIC_PREFLIGHT = "de.bixilon.minosoft.modding.loader.fabric.FabricPackPreflightCli";
    private static final int MAX_COMMAND_OUTPUT_BYTES = 1024 * 1024;
    private static final int MAX_SCENARIO_BYTES = 4 * 1024 * 1024;
    private static final long MAX_SCREENSHOT_PIXELS = 16_777_216L;
    private static final long MAX_SCREENSHOT_FILE_BYTES = 64L * 1024 * 1024;
    private static final long MAX_MODPACK_ARTIFACT_BYTES = 1024L * 1024 * 1024;
    private static final int[] ARGB_CHANNEL_SHIFTS = {24, 16, 8, 0};

    private final Map<String, String> environment = System.getenv();
    private final Path project;
    private final Path javaHome;
    private final Path javaBin;
    private final Path serverJar;
    private final Path fabricServerJar;
    private final Path serverDirectory;
    private final Path serverModsDirectory;
    private final Path serverManagedModsFile;
    private final String serverFlavor;
    private final String serverAddress;
    private final String serverHost;
    private final int serverPort;
    private final String serverMemory;
    private final String minecraftVersion;
    private final Path runDirectory;
    private final Path serverPidFile;
    private final Path clientPidFile;
    private final Path supervisorPidFile;
    private final Path clientLog;
    private final Path serverLog;
    private final Path eventLog;
    private final Path modpacksDirectory;
    private final Path modpackStore;
    private final Path modpackCache;
    private final Path canarySourceDirectory;
    private final Path canaryBuildJar;
    private String modpackName;
    private final String serverModpackName;
    private String trajectory;
    private boolean canaryEnabled;
    private boolean localWorld;
    private boolean debugGpuMemoryLeaks;
    private boolean jsonOutput;
    private long worldSeed;
    private String worldGenerator;
    private int clientGeneration = 1;
    private String sessionId;
    private volatile Process supervisedClient;
    private volatile Process supervisedServer;
    private volatile boolean supervisorOwnsServer;
    private final AtomicBoolean supervisorCleanup = new AtomicBoolean();

    private Play() {
        if (Runtime.version().feature() != 25) {
            throw failure("Java 25 is required; the play shim launched Java " + Runtime.version().feature() + ". Set MINOSOFT_JAVA_HOME.");
        }
        project = Path.of(System.getProperty("minosoft.project", ".")).toAbsolutePath().normalize();
        javaHome = Path.of(System.getProperty("java.home")).toAbsolutePath().normalize();
        javaBin = javaHome.resolve("bin").resolve(isWindows() ? "java.exe" : "java");
        serverJar = resolveProjectPath(env("MINECRAFT_SERVER_JAR", "server/server.jar"));
        serverDirectory = resolveProjectPath(env("MINECRAFT_SERVER_DIR", serverJar.getParent().toString()));
        fabricServerJar = serverDirectory.resolve("fabric-server-mc.1.20.4-loader.0.15.11-launcher.1.0.1.jar");
        serverModsDirectory = serverDirectory.resolve("mods");
        serverManagedModsFile = serverDirectory.resolve(".minosoft-managed-mods");
        serverFlavor = env("MINECRAFT_SERVER_FLAVOR", "fabric").toLowerCase(Locale.ROOT);
        serverAddress = env("MINECRAFT_SERVER_ADDRESS", "127.0.0.1:25565");
        HostAndPort hostAndPort = parseAddress(serverAddress);
        serverHost = hostAndPort.host;
        serverPort = hostAndPort.port;
        serverMemory = env("MINECRAFT_SERVER_MEMORY", "2G");
        minecraftVersion = env("MINECRAFT_VERSION", "1.20.4");
        runDirectory = project.resolve(".run");
        serverPidFile = runDirectory.resolve("minecraft-server.pid");
        clientPidFile = runDirectory.resolve("minosoft-client.pid");
        supervisorPidFile = runDirectory.resolve("play-supervisor.pid");
        clientLog = runDirectory.resolve("minosoft-client.log");
        serverLog = serverDirectory.resolve("server-console.log");
        eventLog = runDirectory.resolve("play-events.jsonl");
        modpackName = environment.getOrDefault("MINOSOFT_MODPACK", "");
        serverModpackName = environment.getOrDefault("MINOSOFT_SERVER_MODPACK", "fabric-stack");
        trajectory = environment.getOrDefault("MINOSOFT_TRAJECTORY", "default");
        modpacksDirectory = resolveProjectPath(env("MINOSOFT_MODPACKS_DIR", project.resolve("modpacks").toString()));
        modpackStore = resolveProjectPath(env("MINOSOFT_MODPACK_STORE", defaultModpackStore().toString()));
        String configuredModpackCache = environment.get("MINOSOFT_MODPACK_CACHE");
        modpackCache = configuredModpackCache == null || configuredModpackCache.isBlank()
            ? null
            : resolveProjectPath(configuredModpackCache);
        canarySourceDirectory = project.resolve("dev/canary-mod");
        canaryBuildJar = project.resolve("build/dev-mods/hot-reload-canary.jar");
        canaryEnabled = environment.getOrDefault("MINOSOFT_CANARY", "false").equalsIgnoreCase("true");
        localWorld = environment.getOrDefault("MINOSOFT_LOCAL_WORLD", "false").equalsIgnoreCase("true");
        debugGpuMemoryLeaks = environment.getOrDefault("MINOSOFT_DEBUG_GPU_MEMORY_LEAKS", "false").equalsIgnoreCase("true");
        worldSeed = parseLong(environment.getOrDefault("MINOSOFT_WORLD_SEED", "6072333650475958863"), "MINOSOFT_WORLD_SEED");
        worldGenerator = normalizeWorldGenerator(environment.getOrDefault("MINOSOFT_WORLD_GENERATOR", ""));
    }

    public static void main(String[] args) {
        try {
            new Play().run(new ArrayList<>(List.of(args)));
        } catch (PlayFailure error) {
            System.err.println("Error: " + error.getMessage());
            System.exit(1);
        } catch (DebugClientException error) {
            if (List.of(args).contains("--json")) {
                ObjectNode result = DebugJson.MAPPER.createObjectNode().put("ok", false);
                result.putObject("error").put("code", error.code()).put("message", error.getMessage());
                System.err.println(result);
            } else {
                System.err.println("Debug error [" + error.code() + "]: " + error.getMessage());
            }
            System.exit(1);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            System.err.println("Error: interrupted.");
            System.exit(130);
        } catch (Exception error) {
            error.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private void run(List<String> arguments) throws Exception {
        if (!arguments.isEmpty() && arguments.get(0).equals("debug")) {
            runDebug(arguments.subList(1, arguments.size()));
            return;
        }
        if (!arguments.isEmpty() && arguments.get(0).equals("modpack")) {
            runModpack(arguments.subList(1, arguments.size()));
            return;
        }
        if (!arguments.isEmpty() && arguments.get(0).equals("wait")) {
            runWait(arguments.subList(1, arguments.size()));
            return;
        }
        if (!arguments.isEmpty() && arguments.get(0).equals("scenario")) {
            runScenario(arguments.subList(1, arguments.size()));
            return;
        }
        if (!arguments.isEmpty() && arguments.get(0).equals("worldgen")) {
            runWorldgen(arguments.subList(1, arguments.size()));
            return;
        }
        if (!arguments.isEmpty() && arguments.get(0).equals("screenshot")) {
            runScreenshot(arguments.subList(1, arguments.size()));
            return;
        }
        if (!arguments.isEmpty() && arguments.get(0).equals("lease")) {
            runLease(arguments.subList(1, arguments.size()));
            return;
        }
        if (!arguments.isEmpty() && arguments.get(0).equals("diagnose")) {
            runDiagnose(arguments.subList(1, arguments.size()));
            return;
        }

        String action = arguments.isEmpty() || arguments.get(0).startsWith("--") ? "dev" : arguments.remove(0);
        if (action.equals("help") || action.equals("--help") || action.equals("-h")) {
            usage();
            return;
        }
        String target = "both";
        if (!arguments.isEmpty() && Set.of("server", "client", "both").contains(arguments.get(0))) {
            target = arguments.remove(0);
        }
        parsePackOptions(arguments);
        if (localWorld) require(target.equals("client"), "--local-world requires the client target; use './play.sh dev client --local-world'.");
        if (!Set.of("dev", "start", "stop", "status").contains(action)) {
            usage();
            throw failure("Unknown action: " + action);
        }
        if (!modpackName.isBlank()) {
            validateName("modpack", modpackName);
            validateName("trajectory", trajectory);
        }
        if (jsonOutput) {
            require(action.equals("status"), "--json is only supported with status.");
            statusJson(target);
            return;
        }

        switch (action + ":" + target) {
            case "dev:both", "dev:client" -> supervise(target);
            case "dev:server" -> throw failure("Hot reload supervises a client; use './play.sh dev client' or './play.sh start server'.");
            case "start:server", "start:client", "start:both" -> startParent(target);
            case "stop:server" -> stopServer();
            case "status:server" -> serverStatus();
            case "stop:client" -> {
                if (!stopSupervisor()) stopClient();
            }
            case "status:client" -> clientStatus();
            case "stop:both" -> {
                if (!stopSupervisor()) {
                    stopClient();
                    stopServer();
                }
            }
            case "status:both" -> {
                serverStatus();
                clientStatus();
            }
            default -> throw failure("Unsupported command.");
        }
    }

    private void runDiagnose(List<String> rawArguments) throws IOException {
        List<String> arguments = new ArrayList<>(rawArguments);
        require(!arguments.isEmpty() && arguments.remove(0).equals("capture"),
            "Usage: ./play.sh diagnose capture [--trajectory NAME] [--output PATH] [--visual] [--json]");
        boolean visual = false;
        Path output = null;
        while (!arguments.isEmpty()) {
            String option = arguments.remove(0);
            if (option.equals("--trajectory")) {
                require(!arguments.isEmpty(), "--trajectory requires a name.");
                trajectory = arguments.remove(0);
            } else if (option.startsWith("--trajectory=")) {
                trajectory = option.substring("--trajectory=".length());
            } else if (option.equals("--output")) {
                require(!arguments.isEmpty(), "--output requires a path.");
                output = resolveProjectPath(arguments.remove(0));
            } else if (option.startsWith("--output=")) {
                output = resolveProjectPath(option.substring("--output=".length()));
            } else if (option.equals("--visual")) {
                visual = true;
            } else if (!option.equals("--json")) {
                throw failure("Unknown diagnose capture option: " + option);
            }
        }
        validateName("trajectory", trajectory);
        if (output == null) {
            String runId = Instant.now().toString().replace(':', '-') + "-" + safeFileName(trajectory);
            output = runDirectory.resolve("diagnostics").resolve(runId);
        }
        TrajectoryDiagnostics diagnostics = new TrajectoryDiagnostics(
            eventLog,
            clientLog,
            serverLog,
            modpackStore,
            modpackName
        );
        try {
            printDebugJson(diagnostics.capture(trajectory, output, visual, buildStatusJson("both")), true);
        } catch (IllegalArgumentException error) {
            throw failure(error.getMessage());
        }
    }

    private void runLease(List<String> rawArguments) throws IOException {
        List<String> arguments = new ArrayList<>(rawArguments);
        String action = arguments.isEmpty() ? "status" : arguments.remove(0);
        TrajectoryLeaseStore leases = new TrajectoryLeaseStore(runDirectory);
        try {
            switch (action) {
                case "acquire" -> {
                    String scope = null;
                    Duration ttl = Duration.ofMinutes(20);
                    String owner = environment.getOrDefault(
                        "MINOSOFT_LEASE_OWNER",
                        "pid-" + ProcessHandle.current().pid()
                    );
                    while (!arguments.isEmpty()) {
                        String option = arguments.remove(0);
                        if (option.equals("--scope")) {
                            require(!arguments.isEmpty(), "--scope requires a lease scope.");
                            scope = arguments.remove(0);
                        } else if (option.startsWith("--scope=")) {
                            scope = option.substring("--scope=".length());
                        } else if (option.equals("--trajectory")) {
                            require(!arguments.isEmpty(), "--trajectory requires a name.");
                            trajectory = arguments.remove(0);
                        } else if (option.startsWith("--trajectory=")) {
                            trajectory = option.substring("--trajectory=".length());
                        } else if (option.equals("--ttl")) {
                            require(!arguments.isEmpty(), "--ttl requires a duration.");
                            ttl = parseDuration(arguments.remove(0), "--ttl");
                        } else if (option.startsWith("--ttl=")) {
                            ttl = parseDuration(option.substring("--ttl=".length()), "--ttl");
                        } else if (option.equals("--owner")) {
                            require(!arguments.isEmpty(), "--owner requires a label.");
                            owner = arguments.remove(0);
                        } else if (option.startsWith("--owner=")) {
                            owner = option.substring("--owner=".length());
                        } else if (!option.equals("--json")) {
                            throw failure("Unknown lease acquire option: " + option);
                        }
                    }
                    require(scope != null, "Usage: ./play.sh lease acquire --scope SCOPE [--trajectory NAME] [--ttl 20m] [--owner LABEL]");
                    validateName("trajectory", trajectory);
                    require(!owner.isBlank() && owner.length() <= 128, "--owner must contain 1..128 characters.");
                    printDebugJson(leases.acquire(scope, trajectory, ttl, owner), true);
                }
                case "status" -> {
                    require(arguments.isEmpty() || arguments.equals(List.of("--json")), "lease status accepts only --json.");
                    printDebugJson(leases.status(), true);
                }
                case "release" -> {
                    require(!arguments.isEmpty(), "Usage: ./play.sh lease release TOKEN");
                    String token = arguments.remove(0);
                    require(arguments.isEmpty() || arguments.equals(List.of("--json")), "lease release accepts only --json.");
                    printDebugJson(leases.release(token), true);
                }
                default -> throw failure("Unknown lease action: " + action);
            }
        } catch (IllegalArgumentException | IllegalStateException error) {
            throw failure(error.getMessage());
        }
    }

    private void runDebug(List<String> rawArguments) throws Exception {
        List<String> arguments = new ArrayList<>(rawArguments);
        String command = arguments.isEmpty() ? "endpoints" : arguments.remove(0);
        DebugSelection selection = parseDebugSelection(arguments);
        DebugDiscovery discovery = new DebugDiscovery(DebugPaths.system());
        if (command.equals("endpoints")) {
            List<DebugEndpointDescriptor> endpoints = discovery.list(true).stream()
                .filter(endpoint -> selection.role == null || endpoint.getRole() == selection.role)
                .filter(endpoint -> !selection.trajectorySpecified || endpoint.getTrajectory().equals(selection.trajectory))
                .collect(Collectors.toList());
            require(arguments.isEmpty(), "debug endpoints does not accept operation arguments.");
            ArrayNode result = DebugJson.MAPPER.createArrayNode();
            for (DebugEndpointDescriptor endpoint : endpoints) result.add(endpointJson(endpoint));
            printDebugJson(result, selection.json);
            return;
        }
        if (command.equals("compare")) {
            debugCompare(discovery, selection, arguments);
            return;
        }
        if (command.equals("visual") && !arguments.isEmpty() && arguments.get(0).equals("motion-noise")) {
            arguments.remove(0);
            debugMotionNoise(discovery, selection, arguments);
            return;
        }

        DebugEndpointRole defaultRole = Set.of("visual", "input", "state", "mods").contains(command) ? DebugEndpointRole.CLIENT : null;
        DebugEndpointDescriptor endpoint = selectDebugEndpoint(discovery, selection, defaultRole);
        try (DebugClient client = DebugClient.connect(DebugPaths.system(), endpoint)) {
            switch (command) {
                case "status" -> {
                    require(arguments.isEmpty(), "debug status does not accept operation arguments.");
                    printDebugJson(client.request("core.status"), selection.json);
                }
                case "capabilities" -> {
                    require(arguments.isEmpty(), "debug capabilities does not accept operation arguments.");
                    printDebugJson(client.request("core.capabilities"), selection.json);
                }
                case "state" -> {
                    String view = arguments.isEmpty() ? "client.summary" : arguments.remove(0);
                    require(arguments.isEmpty(), "Usage: ./play.sh debug state [VIEW]");
                    printDebugJson(client.request("state.sample", DebugJson.MAPPER.createObjectNode().put("view", view), 5_000), selection.json);
                }
                case "mods" -> {
                    require(arguments.isEmpty(), "debug mods does not accept operation arguments.");
                    printDebugJson(client.request("mods.debug"), selection.json);
                }
                case "blocks", "aoi" -> debugBlocks(client, command.equals("blocks") ? "world.blocks.sample" : "world.aoi", arguments, selection.json);
                case "visual" -> debugVisual(client, arguments, endpoint.getTrajectory(), selection.json);
                case "input" -> debugInput(client, arguments, selection.json);
                case "request" -> {
                    require(!arguments.isEmpty(), "Usage: ./play.sh debug request OPERATION [JSON_BODY]");
                    String operation = arguments.remove(0);
                    JsonNode body = arguments.isEmpty() ? DebugJson.MAPPER.createObjectNode() : DebugJson.MAPPER.readTree(arguments.remove(0));
                    require(arguments.isEmpty(), "debug request accepts one optional JSON body.");
                    printDebugJson(client.request(operation, body, 5_000), selection.json);
                }
                default -> throw failure("Unknown debug command: " + command);
            }
        }
    }

    private void runWait(List<String> rawArguments) throws Exception {
        List<String> arguments = new ArrayList<>(rawArguments);
        require(!arguments.isEmpty(), "Usage: ./play.sh wait PREDICATE [--timeout 120s] [--trajectory NAME] [--json]");
        String predicate = arguments.remove(0);
        Duration timeout = Duration.ofSeconds(120);
        boolean json = false;
        while (!arguments.isEmpty()) {
            String option = arguments.remove(0);
            if (option.equals("--timeout")) {
                require(!arguments.isEmpty(), "--timeout requires a duration such as 30s or 2m.");
                timeout = parseDuration(arguments.remove(0), "--timeout");
            } else if (option.startsWith("--timeout=")) {
                timeout = parseDuration(option.substring("--timeout=".length()), "--timeout");
            } else if (option.equals("--trajectory")) {
                require(!arguments.isEmpty(), "--trajectory requires a name.");
                trajectory = arguments.remove(0);
            } else if (option.startsWith("--trajectory=")) {
                trajectory = option.substring("--trajectory=".length());
            } else if (option.equals("--json")) {
                json = true;
            } else {
                throw failure("Unknown wait option: " + option);
            }
        }
        validateName("trajectory", trajectory);
        PredicateObservation observation = waitForPredicate(predicate, timeout, null);
        ObjectNode result = DebugJson.MAPPER.createObjectNode()
            .put("predicate", predicate)
            .put("matched", observation.matched)
            .put("elapsedMs", observation.elapsedMillis)
            .put("timeoutMs", timeout.toMillis())
            .set("observation", observation.value);
        printDebugJson(result, json);
        require(observation.matched, "Timed out waiting for predicate '" + predicate + "' after " + timeout.toMillis() + " ms.");
    }

    private PredicateObservation waitForPredicate(String predicate, Duration timeout, Long expectedPid) throws InterruptedException {
        require(!timeout.isNegative() && !timeout.isZero() && timeout.compareTo(Duration.ofHours(24)) <= 0,
            "Wait timeout must be between 1 ms and 24 hours.");
        long started = System.nanoTime();
        long deadline = started + timeout.toNanos();
        PredicateObservation last;
        do {
            last = observePredicate(predicate, expectedPid);
            if (last.matched) return new PredicateObservation(true, elapsedMillis(started), last.value);
            if (System.nanoTime() >= deadline) break;
            Thread.sleep(Math.min(250L, Math.max(1L, Duration.ofNanos(deadline - System.nanoTime()).toMillis())));
        } while (true);
        return new PredicateObservation(false, elapsedMillis(started), last.value);
    }

    private PredicateObservation observePredicate(String predicate, Long expectedPid) {
        ObjectNode value = DebugJson.MAPPER.createObjectNode();
        value.put("trajectory", trajectory);
        switch (predicate) {
            case "server.port-open" -> {
                boolean matched = serverPortIsOpen();
                value.put("portOpen", matched).put("address", serverAddress);
                return new PredicateObservation(matched, 0L, value);
            }
            case "server.debug-ready", "server.game-ready" -> {
                Optional<JsonNode> status = debugStatus(DebugEndpointRole.SERVER, expectedPid);
                boolean debugReady = status.isPresent();
                boolean protocolReady = minecraftStatusIsReady();
                boolean gameReady = serverGameIsReady(expectedPid);
                value.put("debugReady", debugReady).put("protocolReady", protocolReady).put("gameReady", gameReady);
                status.ifPresent(node -> value.set("status", node));
                return new PredicateObservation(predicate.equals("server.debug-ready") ? debugReady : gameReady, 0L, value);
            }
            case "client.debug-ready", "client.joined", "client.render-ready" -> {
                Optional<JsonNode> status = debugStatus(DebugEndpointRole.CLIENT, expectedPid);
                boolean debugReady = status.isPresent();
                boolean joined = status.map(node -> node.path("ready").asBoolean(false)).orElse(false);
                boolean renderReady = status.map(node -> node.path("renderReady").asBoolean(false)).orElse(false);
                value.put("debugReady", debugReady).put("joined", joined).put("renderReady", renderReady);
                status.ifPresent(node -> value.set("status", node));
                boolean matched = switch (predicate) {
                    case "client.debug-ready" -> debugReady;
                    case "client.joined" -> joined;
                    default -> renderReady;
                };
                return new PredicateObservation(matched, 0L, value);
            }
            case "both.ready" -> {
                PredicateObservation server = observePredicate("server.game-ready", null);
                PredicateObservation client = observePredicate("client.render-ready", null);
                value.set("server", server.value);
                value.set("client", client.value);
                return new PredicateObservation(server.matched && client.matched, 0L, value);
            }
            default -> throw failure("Unknown wait predicate '" + predicate + "'. Supported predicates: server.port-open, server.debug-ready, server.game-ready, client.debug-ready, client.joined, client.render-ready, both.ready.");
        }
    }

    private static long elapsedMillis(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(Math.max(0L, System.nanoTime() - startedNanos));
    }

    private static Duration parseDuration(String value, String label) {
        Matcher matcher = Pattern.compile("([0-9]+)(ms|s|m|h)?").matcher(value.toLowerCase(Locale.ROOT));
        require(matcher.matches(), label + " requires a duration such as 500ms, 30s, 2m, or 1h.");
        long amount = parseLong(matcher.group(1), label);
        return switch (matcher.group(2) == null ? "ms" : matcher.group(2)) {
            case "ms" -> Duration.ofMillis(amount);
            case "s" -> Duration.ofSeconds(amount);
            case "m" -> Duration.ofMinutes(amount);
            case "h" -> Duration.ofHours(amount);
            default -> throw failure("Invalid duration: " + value);
        };
    }

    private void debugCompare(DebugDiscovery discovery, DebugSelection selection, List<String> arguments) throws Exception {
        require(!arguments.isEmpty() && arguments.remove(0).equals("blocks"),
            "Usage: ./play.sh debug compare blocks MIN_X MIN_Y MIN_Z MAX_X MAX_Y MAX_Z");
        require(arguments.size() == 6, "debug compare blocks requires six coordinates.");
        int[] values = new int[6];
        for (int index = 0; index < values.length; index++) values[index] = Integer.parseInt(arguments.remove(0));
        ObjectNode body = DebugJson.MAPPER.createObjectNode();
        body.putObject("min").put("x", values[0]).put("y", values[1]).put("z", values[2]);
        body.putObject("max").put("x", values[3]).put("y", values[4]).put("z", values[5]);

        DebugSelection clientSelection = selection.copy(DebugEndpointRole.CLIENT);
        DebugSelection serverSelection = selection.copy(DebugEndpointRole.SERVER);
        DebugEndpointDescriptor clientEndpoint = selectDebugEndpoint(discovery, clientSelection, DebugEndpointRole.CLIENT);
        DebugEndpointDescriptor serverEndpoint = selectDebugEndpoint(discovery, serverSelection, DebugEndpointRole.SERVER);
        JsonNode clientSample;
        JsonNode serverSample;
        try (DebugClient client = DebugClient.connect(DebugPaths.system(), clientEndpoint);
             DebugClient server = DebugClient.connect(DebugPaths.system(), serverEndpoint)) {
            clientSample = client.request("world.blocks.sample", body, 10_000);
            serverSample = server.request("world.blocks.sample", body, 10_000);
        }
        List<String> clientCells = expandBlockSample(clientSample);
        List<String> serverCells = expandBlockSample(serverSample);
        require(clientCells.size() == serverCells.size(), "Client and server returned different block volumes.");
        ObjectNode result = DebugJson.MAPPER.createObjectNode();
        result.put("clientEndpoint", clientEndpoint.getId());
        result.put("serverEndpoint", serverEndpoint.getId());
        result.put("volume", clientCells.size());
        result.put("clientNotLoaded", clientSample.path("notLoaded").asInt());
        result.put("serverNotLoaded", serverSample.path("notLoaded").asInt());
        ArrayNode differences = result.putArray("differences");
        int differenceCount = 0;
        int sizeX = values[3] - values[0] + 1;
        int sizeZ = values[5] - values[2] + 1;
        for (int index = 0; index < clientCells.size(); index++) {
            if (clientCells.get(index).equals(serverCells.get(index))) continue;
            differenceCount++;
            if (differences.size() >= 64) continue;
            int xOffset = index % sizeX;
            int remainder = index / sizeX;
            int zOffset = remainder % sizeZ;
            int yOffset = remainder / sizeZ;
            differences.addObject()
                .put("x", values[0] + xOffset).put("y", values[1] + yOffset).put("z", values[2] + zOffset)
                .put("client", clientCells.get(index)).put("server", serverCells.get(index));
        }
        result.put("differenceCount", differenceCount);
        result.put("equal", differenceCount == 0);
        result.put("truncated", differenceCount > differences.size());
        printDebugJson(result, selection.json);
    }

    private static List<String> expandBlockSample(JsonNode sample) {
        List<String> palette = new ArrayList<>();
        sample.path("palette").forEach(value -> palette.add(normalizeBlockState(value.asText())));
        List<String> cells = new ArrayList<>(sample.path("volume").asInt());
        for (JsonNode run : sample.path("runs")) {
            int paletteIndex = run.path(0).asInt(-1);
            int count = run.path(1).asInt(0);
            require(paletteIndex >= 0 && paletteIndex < palette.size() && count > 0, "Endpoint returned an invalid block run.");
            for (int index = 0; index < count; index++) cells.add(palette.get(paletteIndex));
        }
        require(cells.size() == sample.path("volume").asInt(), "Endpoint block runs do not match declared volume.");
        return cells;
    }

    private static String normalizeBlockState(String state) {
        state = state.toLowerCase(Locale.ROOT);
        int bracket = state.indexOf('[');
        if (bracket < 0 || !state.endsWith("]")) return state;
        String[] properties = state.substring(bracket + 1, state.length() - 1).split(",");
        java.util.Arrays.sort(properties);
        return state.substring(0, bracket) + "[" + String.join(",", properties) + "]";
    }

    private DebugSelection parseDebugSelection(List<String> arguments) {
        DebugSelection selection = new DebugSelection();
        selection.trajectory = trajectory;
        for (int index = 0; index < arguments.size();) {
            String option = arguments.get(index);
            if (option.equals("--json")) {
                selection.json = true;
                arguments.remove(index);
            } else if (option.equals("--role") || option.equals("--trajectory") || option.equals("--endpoint")) {
                require(index + 1 < arguments.size(), option + " requires a value.");
                String value = arguments.remove(index + 1);
                arguments.remove(index);
                applyDebugSelection(selection, option, value);
            } else if (option.startsWith("--role=") || option.startsWith("--trajectory=") || option.startsWith("--endpoint=")) {
                arguments.remove(index);
                int split = option.indexOf('=');
                applyDebugSelection(selection, option.substring(0, split), option.substring(split + 1));
            } else {
                index++;
            }
        }
        return selection;
    }

    private void applyDebugSelection(DebugSelection selection, String option, String value) {
        switch (option) {
            case "--role" -> selection.role = DebugEndpointRole.fromWireName(value);
            case "--trajectory" -> {
                validateName("trajectory", value);
                selection.trajectory = value;
                selection.trajectorySpecified = true;
            }
            case "--endpoint" -> selection.endpointId = value;
            default -> throw failure("Unknown debug selector: " + option);
        }
    }

    private DebugEndpointDescriptor selectDebugEndpoint(DebugDiscovery discovery, DebugSelection selection, DebugEndpointRole defaultRole) throws IOException {
        DebugEndpointRole role = selection.role == null ? defaultRole : selection.role;
        List<DebugEndpointDescriptor> endpoints = discovery.list(true).stream()
            .filter(endpoint -> selection.endpointId == null || endpoint.getId().equals(selection.endpointId))
            .filter(endpoint -> role == null || endpoint.getRole() == role)
            .filter(endpoint -> endpoint.getTrajectory().equals(selection.trajectory))
            .sorted(Comparator.comparingInt(DebugEndpointDescriptor::getGeneration).reversed()
                .thenComparing(DebugEndpointDescriptor::getProcessStart, Comparator.reverseOrder()))
            .collect(Collectors.toList());
        require(!endpoints.isEmpty(), "No live debug endpoint matched trajectory " + selection.trajectory + (role == null ? "." : " and role " + role.wireName() + "."));
        if (endpoints.size() > 1 && selection.endpointId == null && endpoints.get(0).getGeneration() == endpoints.get(1).getGeneration()) {
            throw failure("Multiple debug endpoints match; select one with --endpoint. Use './play.sh debug endpoints'.");
        }
        return endpoints.get(0);
    }

    private void debugVisual(DebugClient client, List<String> arguments, String endpointTrajectory, boolean json) throws Exception {
        require(!arguments.isEmpty(), "Usage: ./play.sh debug visual capture [OUTPUT] | sample [--point X,Y] [--region X,Y,W,H] | motion-noise [OPTIONS]");
        String action = arguments.remove(0);
        if (action.equals("capture")) {
            require(arguments.size() <= 1, "Usage: ./play.sh debug visual capture [OUTPUT]");
            DebugResponse response = client.requestWithAttachment("visual.capture", DebugJson.MAPPER.createObjectNode(), 10_000);
            require(response.hasAttachment(), "visual.capture returned no image attachment.");
            require(response.attachment().length <= MAX_SCREENSHOT_FILE_BYTES, "visual.capture exceeded the screenshot byte limit.");
            String actualHash = HexFormat.of().formatHex(digest("sha256").digest(response.attachment()));
            String expectedHash = response.result().path("sha256").asText("");
            require(expectedHash.isBlank() || expectedHash.equals(actualHash), "visual.capture attachment hash did not match its metadata.");
            Path output = arguments.isEmpty()
                ? defaultAgentScreenshotPath(response, endpointTrajectory)
                : resolveProjectPath(arguments.remove(0));
            if (output.getParent() != null) Files.createDirectories(output.getParent());
            Files.write(output, response.attachment());
            ObjectNode result = response.result().deepCopy();
            result.put("output", output.toString());
            result.put("bytes", response.attachment().length);
            result.put("verifiedSha256", actualHash);
            printDebugJson(result, json);
            return;
        }
        require(action.equals("sample"), "Unknown debug visual action: " + action);
        ObjectNode body = DebugJson.MAPPER.createObjectNode();
        ArrayNode points = body.putArray("points");
        while (!arguments.isEmpty()) {
            String option = arguments.remove(0);
            if (option.equals("--point")) {
                require(!arguments.isEmpty(), "--point requires X,Y.");
                int[] value = parseInts(arguments.remove(0), 2, "--point");
                points.addObject().put("x", value[0]).put("y", value[1]);
            } else if (option.equals("--region")) {
                require(!arguments.isEmpty(), "--region requires X,Y,W,H.");
                int[] value = parseInts(arguments.remove(0), 4, "--region");
                body.putObject("region").put("x", value[0]).put("y", value[1]).put("width", value[2]).put("height", value[3]);
            } else throw failure("Unknown debug visual sample option: " + option);
        }
        printDebugJson(client.request("visual.sample", body, 10_000), json);
    }

    private void debugMotionNoise(DebugDiscovery discovery, DebugSelection selection, List<String> arguments) throws Exception {
        require(selection.role == null, "motion-noise requires both client and server endpoints; omit --role.");
        require(selection.endpointId == null, "motion-noise requires both client and server endpoints; select them with --trajectory.");
        MotionNoiseOptions options = parseMotionNoiseOptions(arguments, selection.trajectory);
        DebugEndpointDescriptor clientEndpoint = selectDebugEndpoint(discovery, selection.copy(DebugEndpointRole.CLIENT), DebugEndpointRole.CLIENT);
        DebugEndpointDescriptor serverEndpoint = selectDebugEndpoint(discovery, selection.copy(DebugEndpointRole.SERVER), DebugEndpointRole.SERVER);
        Files.createDirectories(options.output);

        ObjectNode report = DebugJson.MAPPER.createObjectNode();
        report.put("schemaVersion", 1);
        report.put("startedAt", Instant.now().toString());
        report.put("trajectory", selection.trajectory);
        report.set("clientEndpoint", endpointJson(clientEndpoint));
        report.set("serverEndpoint", endpointJson(serverEndpoint));
        report.put("output", options.output.toString());
        report.put("protocol", "same-pose-yaw-return-with-equal-frame-stationary-control");
        ObjectNode configuration = report.putObject("configuration");
        configuration.put("yawDelta", options.yawDelta);
        configuration.put("samples", options.samples);
        configuration.put("settleFrames", options.settleFrames);
        configuration.put("awayFrames", options.awayFrames);
        configuration.put("pixelThreshold", options.pixelThreshold);
        configuration.put("flatGradientThreshold", options.flatGradientThreshold);
        ArrayNode configuredRecovery = configuration.putArray("recoveryFrames");
        for (int frame : options.recoveryFrames) configuredRecovery.add(frame);
        if (options.region != null) configuration.set("region", motionRegionJson(options.region));

        double[][] motionLuma = new double[options.samples][options.recoveryFrames.length];
        double[][] controlLuma = new double[options.samples][options.recoveryFrames.length];
        double[][] motionFlat = new double[options.samples][options.recoveryFrames.length];
        double[][] controlFlat = new double[options.samples][options.recoveryFrames.length];
        ArrayNode samples = report.putArray("samples");
        MotionPose initialPose = null;
        MotionPose lastCommandedPose = null;
        boolean poseConflict = false;
        boolean backgroundThrottleOwned = false;
        int completedSamples = 0;

        try (DebugClient client = DebugClient.connect(DebugPaths.system(), clientEndpoint);
             DebugClient server = DebugClient.connect(DebugPaths.system(), serverEndpoint)) {
            ObjectNode disableThrottle = DebugJson.MAPPER.createObjectNode()
                .put("expected", "default")
                .put("value", "disabled");
            try {
                report.set("backgroundThrottleBegin", client.request("visual.background-throttle", disableThrottle, 5_000));
                backgroundThrottleOwned = true;
            } catch (DebugClientException conflict) {
                report.put("backgroundThrottleWarning", conflict.getMessage());
            }
            JsonNode initialStatus = client.request("core.status", DebugJson.MAPPER.createObjectNode(), 5_000);
            report.set("initialStatus", initialStatus);
            initialPose = sampleClientPose(client);
            report.set("initialPose", motionPoseJson(initialPose));
            MotionPose awayPose = initialPose.withYaw(initialPose.yaw + options.yawDelta);

            for (int sampleIndex = 0; sampleIndex < options.samples; sampleIndex++) {
                waitFrames(client, options.settleFrames, 15_000);
                MotionFrame motionBase = captureMotionFrame(client);

                teleport(server, awayPose);
                lastCommandedPose = awayPose;
                waitForPose(client, awayPose, 10_000);
                waitFrames(client, options.awayFrames, 10_000);

                teleport(server, initialPose);
                lastCommandedPose = initialPose;
                waitForPose(client, initialPose, 10_000);

                MotionFrame[] motionFrames = new MotionFrame[options.recoveryFrames.length];
                long returnFrame = currentFrame(client);
                for (int checkpoint = 0; checkpoint < options.recoveryFrames.length; checkpoint++) {
                    waitUntilFrame(client, returnFrame + options.recoveryFrames[checkpoint], 15_000);
                    motionFrames[checkpoint] = captureMotionFrame(client);
                }

                waitFrames(client, options.settleFrames, 15_000);
                MotionFrame controlBase = captureMotionFrame(client);
                MotionFrame[] controlFrames = new MotionFrame[options.recoveryFrames.length];
                long[] actualDeltas = new long[options.recoveryFrames.length];
                for (int checkpoint = 0; checkpoint < options.recoveryFrames.length; checkpoint++) {
                    actualDeltas[checkpoint] = Math.max(0L, motionFrames[checkpoint].frame - motionBase.frame);
                    waitUntilFrame(client, controlBase.frame + actualDeltas[checkpoint], 15_000);
                    controlFrames[checkpoint] = captureMotionFrame(client);
                }

                ObjectNode sample = samples.addObject();
                sample.put("index", sampleIndex);
                sample.put("motionBaseFrame", motionBase.frame);
                sample.put("returnFrame", returnFrame);
                sample.put("controlBaseFrame", controlBase.frame);
                ArrayNode checkpoints = sample.putArray("checkpoints");
                for (int checkpoint = 0; checkpoint < options.recoveryFrames.length; checkpoint++) {
                    MotionNoiseAnalyzer.Metrics motion = MotionNoiseAnalyzer.compare(
                        motionBase.image,
                        motionFrames[checkpoint].image,
                        options.region,
                        options.pixelThreshold,
                        options.flatGradientThreshold
                    );
                    MotionNoiseAnalyzer.Metrics control = MotionNoiseAnalyzer.compare(
                        controlBase.image,
                        controlFrames[checkpoint].image,
                        options.region,
                        options.pixelThreshold,
                        options.flatGradientThreshold
                    );
                    motionLuma[sampleIndex][checkpoint] = motion.meanAbsoluteLumaError();
                    controlLuma[sampleIndex][checkpoint] = control.meanAbsoluteLumaError();
                    motionFlat[sampleIndex][checkpoint] = motion.flatChangedRatio();
                    controlFlat[sampleIndex][checkpoint] = control.flatChangedRatio();

                    ObjectNode measurement = checkpoints.addObject();
                    measurement.put("requestedRecoveryFrames", options.recoveryFrames[checkpoint]);
                    measurement.put("actualRecoveryFrames", Math.max(0L, motionFrames[checkpoint].frame - returnFrame));
                    measurement.put("actualMotionFrameDelta", actualDeltas[checkpoint]);
                    measurement.put("motionFrame", motionFrames[checkpoint].frame);
                    measurement.put("controlFrame", controlFrames[checkpoint].frame);
                    measurement.set("motion", motionMetricsJson(motion));
                    measurement.set("control", motionMetricsJson(control));
                    ObjectNode excess = measurement.putObject("motionExcess");
                    excess.put("meanAbsoluteLumaError", motion.meanAbsoluteLumaError() - control.meanAbsoluteLumaError());
                    excess.put("flatChangedRatio", motion.flatChangedRatio() - control.flatChangedRatio());
                    excess.put("lumaRatio", safeMetricRatio(motion.meanAbsoluteLumaError(), control.meanAbsoluteLumaError()));
                    excess.put("flatChangedRatioRatio", safeMetricRatio(motion.flatChangedRatio(), control.flatChangedRatio()));
                }

                if (sampleIndex == 0) {
                    ArrayNode artifacts = sample.putArray("artifacts");
                    writeMotionArtifact(options.output, "sample-0-motion-base.png", motionBase.image, options.region, artifacts);
                    writeMotionArtifact(options.output, "sample-0-control-base.png", controlBase.image, options.region, artifacts);
                    for (int checkpoint = 0; checkpoint < options.recoveryFrames.length; checkpoint++) {
                        String suffix = Integer.toString(options.recoveryFrames[checkpoint]);
                        writeMotionArtifact(options.output, "sample-0-motion-" + suffix + ".png", motionFrames[checkpoint].image, options.region, artifacts);
                        writeMotionArtifact(options.output, "sample-0-control-" + suffix + ".png", controlFrames[checkpoint].image, options.region, artifacts);
                    }
                }
                completedSamples++;
            }

            JsonNode finalStatus = client.request("core.status", DebugJson.MAPPER.createObjectNode(), 5_000);
            report.set("finalStatus", finalStatus);
            ObjectNode validity = report.putObject("measurementValidity");
            double minimumFps = Math.min(initialStatus.path("fps").asDouble(0.0), finalStatus.path("fps").asDouble(0.0));
            long maximumMedianFrameNanos = Math.max(
                initialStatus.path("medianFrameNanos").asLong(Long.MAX_VALUE),
                finalStatus.path("medianFrameNanos").asLong(Long.MAX_VALUE)
            );
            boolean representativeFrameRate = minimumFps >= 20.0 && maximumMedianFrameNanos <= 50_000_000L;
            validity.put("representativeFrameRate", representativeFrameRate);
            validity.put("minimumObservedFps", minimumFps);
            validity.put("maximumMedianFrameNanos", maximumMedianFrameNanos);
            if (!representativeFrameRate) {
                validity.put("warning", "Client frame rate was below 20 FPS or median frame time exceeded 50 ms; repeat with the client focused before using this as a camera-motion acceptance result.");
            }
        } catch (Exception error) {
            report.put("error", error.getMessage() == null ? error.getClass().getName() : error.getMessage());
            throw error;
        } finally {
            if (initialPose != null && lastCommandedPose != null && !samePose(lastCommandedPose, initialPose)) {
                try {
                    DebugEndpointDescriptor liveClientEndpoint = selectDebugEndpoint(discovery, selection.copy(DebugEndpointRole.CLIENT), DebugEndpointRole.CLIENT);
                    DebugEndpointDescriptor liveServerEndpoint = selectDebugEndpoint(discovery, selection.copy(DebugEndpointRole.SERVER), DebugEndpointRole.SERVER);
                    try (DebugClient client = DebugClient.connect(DebugPaths.system(), liveClientEndpoint);
                         DebugClient server = DebugClient.connect(DebugPaths.system(), liveServerEndpoint)) {
                        MotionPose current = sampleClientPose(client);
                        if (samePose(current, lastCommandedPose)) {
                            teleport(server, initialPose);
                            waitForPose(client, initialPose, 10_000);
                        } else {
                            poseConflict = true;
                        }
                    }
                } catch (Exception restoreError) {
                    report.put("restoreError", restoreError.getMessage() == null ? restoreError.getClass().getName() : restoreError.getMessage());
                }
            }
            if (backgroundThrottleOwned) {
                try {
                    DebugEndpointDescriptor liveClientEndpoint = selectDebugEndpoint(discovery, selection.copy(DebugEndpointRole.CLIENT), DebugEndpointRole.CLIENT);
                    try (DebugClient client = DebugClient.connect(DebugPaths.system(), liveClientEndpoint)) {
                        ObjectNode restoreThrottle = DebugJson.MAPPER.createObjectNode()
                            .put("expected", "disabled")
                            .put("value", "default");
                        report.set("backgroundThrottleEnd", client.request("visual.background-throttle", restoreThrottle, 5_000));
                    }
                } catch (Exception restoreError) {
                    report.put("backgroundThrottleRestoreError", restoreError.getMessage() == null ? restoreError.getClass().getName() : restoreError.getMessage());
                }
            }
            report.put("poseConflict", poseConflict);
            report.put("completedSamples", completedSamples);
            report.put("finishedAt", Instant.now().toString());
            addMotionSummary(report, options, completedSamples, motionLuma, controlLuma, motionFlat, controlFlat);
            Path reportPath = options.output.resolve("report.json");
            DebugJson.MAPPER.writerWithDefaultPrettyPrinter().writeValue(reportPath.toFile(), report);
            report.put("report", reportPath.toString());
        }
        printDebugJson(report, selection.json);
    }

    private MotionNoiseOptions parseMotionNoiseOptions(List<String> arguments, String trajectory) {
        double yawDelta = 5.0;
        int samples = 2;
        int settleFrames = 32;
        int awayFrames = 4;
        int pixelThreshold = 8;
        int flatGradientThreshold = 12;
        int[] recoveryFrames = new int[]{0, 4, 16, 32};
        MotionNoiseAnalyzer.Region region = null;
        Path output = runDirectory.resolve("motion-noise").resolve(safeFileName(trajectory)).resolve(Instant.now().toString().replace(':', '-'));
        while (!arguments.isEmpty()) {
            String option = arguments.remove(0);
            require(!arguments.isEmpty(), option + " requires a value.");
            String value = arguments.remove(0);
            switch (option) {
                case "--yaw-delta" -> yawDelta = parseFiniteDouble(value, option);
                case "--samples" -> samples = parseBoundedInt(value, option, 1, 8);
                case "--settle-frames" -> settleFrames = parseBoundedInt(value, option, 0, 240);
                case "--away-frames" -> awayFrames = parseBoundedInt(value, option, 0, 240);
                case "--pixel-threshold" -> pixelThreshold = parseBoundedInt(value, option, 0, 255);
                case "--flat-gradient-threshold" -> flatGradientThreshold = parseBoundedInt(value, option, 0, 255);
                case "--recovery-frames" -> recoveryFrames = parseRecoveryFrames(value);
                case "--region" -> {
                    int[] parsed = parseInts(value, 4, option);
                    region = new MotionNoiseAnalyzer.Region(parsed[0], parsed[1], parsed[2], parsed[3]);
                }
                case "--output" -> output = resolveProjectPath(value);
                default -> throw failure("Unknown motion-noise option: " + option);
            }
        }
        require(yawDelta >= 0.1 && yawDelta <= 45.0, "--yaw-delta must be between 0.1 and 45 degrees.");
        return new MotionNoiseOptions(yawDelta, samples, settleFrames, awayFrames, pixelThreshold, flatGradientThreshold, recoveryFrames, region, output);
    }

    private static int[] parseRecoveryFrames(String value) {
        String[] parts = value.split(",", -1);
        require(parts.length >= 1 && parts.length <= 8, "--recovery-frames accepts one to eight comma-separated values.");
        TreeSet<Integer> frames = new TreeSet<>();
        for (String part : parts) frames.add(parseBoundedInt(part, "--recovery-frames", 0, 240));
        int[] result = new int[frames.size()];
        int index = 0;
        for (int frame : frames) result[index++] = frame;
        return result;
    }

    private static int parseBoundedInt(String value, String label, int minimum, int maximum) {
        try {
            int parsed = Integer.parseInt(value);
            require(parsed >= minimum && parsed <= maximum, label + " must be between " + minimum + " and " + maximum + ".");
            return parsed;
        } catch (NumberFormatException error) {
            throw failure(label + " requires an integer.");
        }
    }

    private static double parseFiniteDouble(String value, String label) {
        try {
            double parsed = Double.parseDouble(value);
            require(Double.isFinite(parsed), label + " requires a finite number.");
            return parsed;
        } catch (NumberFormatException error) {
            throw failure(label + " requires a number.");
        }
    }

    private static MotionPose sampleClientPose(DebugClient client) throws IOException {
        JsonNode state = client.request("state.sample", DebugJson.MAPPER.createObjectNode().put("view", "client.player"), 5_000);
        JsonNode player = state.path("player");
        JsonNode world = state.path("worldState");
        require(player.isObject() && world.isObject(), "Client did not return a playing player pose.");
        String dimension = world.path("dimension").asText("");
        require(!dimension.isBlank(), "Client player pose has no dimension.");
        return new MotionPose(
            dimension,
            player.path("x").asDouble(),
            player.path("y").asDouble(),
            player.path("z").asDouble(),
            player.path("yaw").asDouble(),
            player.path("pitch").asDouble()
        );
    }

    private static void teleport(DebugClient server, MotionPose pose) throws IOException {
        ObjectNode body = DebugJson.MAPPER.createObjectNode();
        body.put("dimension", pose.dimension);
        body.putObject("position").put("x", pose.x).put("y", pose.y).put("z", pose.z);
        body.put("yaw", pose.yaw).put("pitch", pose.pitch);
        server.request("world.teleport-player", body, 5_000);
    }

    private static void waitForPose(DebugClient client, MotionPose expected, long timeoutMillis) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        MotionPose current;
        do {
            current = sampleClientPose(client);
            if (samePose(current, expected)) return;
            Thread.sleep(20L);
        } while (System.nanoTime() < deadline);
        throw failure("Timed out waiting for the client camera pose; another session may have moved it.");
    }

    private static boolean samePose(MotionPose first, MotionPose second) {
        return first.dimension.equals(second.dimension) &&
            Math.abs(first.x - second.x) <= 0.01 &&
            Math.abs(first.y - second.y) <= 0.01 &&
            Math.abs(first.z - second.z) <= 0.01 &&
            angleDistance(first.yaw, second.yaw) <= 0.05 &&
            Math.abs(first.pitch - second.pitch) <= 0.05;
    }

    private static double angleDistance(double first, double second) {
        double delta = (first - second) % 360.0;
        if (delta > 180.0) delta -= 360.0;
        if (delta < -180.0) delta += 360.0;
        return Math.abs(delta);
    }

    private static long currentFrame(DebugClient client) throws IOException {
        return client.request("core.status", DebugJson.MAPPER.createObjectNode(), 5_000).path("frame").asLong(-1L);
    }

    private static void waitFrames(DebugClient client, long frames, long timeoutMillis) throws Exception {
        long current = currentFrame(client);
        require(current >= 0L, "Client status did not expose a render frame.");
        waitUntilFrame(client, current + frames, timeoutMillis);
    }

    private static void waitUntilFrame(DebugClient client, long targetFrame, long timeoutMillis) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        long current;
        do {
            current = currentFrame(client);
            if (current >= targetFrame) return;
            Thread.sleep(5L);
        } while (System.nanoTime() < deadline);
        throw failure("Timed out waiting for render frame " + targetFrame + " (last frame " + current + ").");
    }

    private static MotionFrame captureMotionFrame(DebugClient client) throws IOException {
        DebugResponse response = client.requestWithAttachment("visual.capture", DebugJson.MAPPER.createObjectNode(), 15_000);
        require(response.hasAttachment(), "visual.capture returned no image attachment.");
        require(response.attachment().length <= MAX_SCREENSHOT_FILE_BYTES, "visual.capture exceeded the screenshot byte limit.");
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(response.attachment()));
        require(image != null, "visual.capture did not return a decodable PNG.");
        require((long) image.getWidth() * image.getHeight() <= MAX_SCREENSHOT_PIXELS, "visual.capture exceeded the screenshot pixel limit.");
        return new MotionFrame(response.result().path("frame").asLong(-1L), image);
    }

    private static ObjectNode motionMetricsJson(MotionNoiseAnalyzer.Metrics metrics) {
        return DebugJson.MAPPER.createObjectNode()
            .put("pixels", metrics.pixels())
            .put("changedPixels", metrics.changedPixels())
            .put("changedRatio", metrics.changedRatio())
            .put("meanAbsoluteRgbError", metrics.meanAbsoluteRgbError())
            .put("meanAbsoluteLumaError", metrics.meanAbsoluteLumaError())
            .put("rootMeanSquareLumaError", metrics.rootMeanSquareLumaError())
            .put("p95LumaError", metrics.p95LumaError())
            .put("flatPixels", metrics.flatPixels())
            .put("flatChangedPixels", metrics.flatChangedPixels())
            .put("flatChangedRatio", metrics.flatChangedRatio())
            .put("flatMeanAbsoluteLumaError", metrics.flatMeanAbsoluteLumaError());
    }

    private static ObjectNode motionPoseJson(MotionPose pose) {
        return DebugJson.MAPPER.createObjectNode()
            .put("dimension", pose.dimension)
            .put("x", pose.x).put("y", pose.y).put("z", pose.z)
            .put("yaw", pose.yaw).put("pitch", pose.pitch);
    }

    private static ObjectNode motionRegionJson(MotionNoiseAnalyzer.Region region) {
        return DebugJson.MAPPER.createObjectNode()
            .put("x", region.x()).put("y", region.y()).put("width", region.width()).put("height", region.height());
    }

    private static double safeMetricRatio(double numerator, double denominator) {
        if (denominator == 0.0) return numerator == 0.0 ? 1.0 : Double.POSITIVE_INFINITY;
        return numerator / denominator;
    }

    private static void writeMotionArtifact(
        Path directory,
        String name,
        BufferedImage image,
        MotionNoiseAnalyzer.Region region,
        ArrayNode artifacts
    ) throws IOException {
        BufferedImage output = image;
        if (region != null) {
            require(
                region.x() >= 0 && region.y() >= 0 && region.width() > 0 && region.height() > 0 &&
                    (long) region.x() + region.width() <= image.getWidth() &&
                    (long) region.y() + region.height() <= image.getHeight(),
                "Motion-noise region exceeds captured image dimensions."
            );
            output = image.getSubimage(region.x(), region.y(), region.width(), region.height());
        }
        Path path = directory.resolve(name);
        require(ImageIO.write(output, "png", path.toFile()), "No PNG writer is available.");
        artifacts.add(path.toString());
    }

    private static void addMotionSummary(
        ObjectNode report,
        MotionNoiseOptions options,
        int completedSamples,
        double[][] motionLuma,
        double[][] controlLuma,
        double[][] motionFlat,
        double[][] controlFlat
    ) {
        ArrayNode summary = report.putArray("summary");
        if (completedSamples == 0) return;
        for (int checkpoint = 0; checkpoint < options.recoveryFrames.length; checkpoint++) {
            double meanMotionLuma = columnMean(motionLuma, completedSamples, checkpoint);
            double meanControlLuma = columnMean(controlLuma, completedSamples, checkpoint);
            double meanMotionFlat = columnMean(motionFlat, completedSamples, checkpoint);
            double meanControlFlat = columnMean(controlFlat, completedSamples, checkpoint);
            summary.addObject()
                .put("requestedRecoveryFrames", options.recoveryFrames[checkpoint])
                .put("motionMeanAbsoluteLumaError", meanMotionLuma)
                .put("controlMeanAbsoluteLumaError", meanControlLuma)
                .put("excessMeanAbsoluteLumaError", meanMotionLuma - meanControlLuma)
                .put("lumaRatio", safeMetricRatio(meanMotionLuma, meanControlLuma))
                .put("motionFlatChangedRatio", meanMotionFlat)
                .put("controlFlatChangedRatio", meanControlFlat)
                .put("excessFlatChangedRatio", meanMotionFlat - meanControlFlat)
                .put("flatChangedRatioRatio", safeMetricRatio(meanMotionFlat, meanControlFlat));
        }
    }

    private static double columnMean(double[][] values, int rows, int column) {
        double sum = 0.0;
        for (int row = 0; row < rows; row++) sum += values[row][column];
        return sum / rows;
    }

    private Path defaultAgentScreenshotPath(DebugResponse response, String endpointTrajectory) {
        Path directory = runDirectory.resolve("agent-screenshots").resolve(safeFileName(endpointTrajectory));
        String attachmentName = response.attachmentName();
        if (attachmentName == null || attachmentName.isBlank()) {
            attachmentName = "minosoft-" + response.result().path("frame").asLong() + ".png";
        }
        String filename = safeFileName(Path.of(attachmentName).getFileName().toString());
        if (filename.isBlank()) filename = "minosoft-capture.png";
        if (!filename.toLowerCase(Locale.ROOT).endsWith(".png")) filename += ".png";

        Path output = directory.resolve(filename);
        String stem = filename.substring(0, filename.length() - 4);
        for (int index = 1; Files.exists(output); index++) {
            require(index <= 10_000, "Too many agent screenshots have the same filename in " + directory + ".");
            output = directory.resolve(stem + "_" + index + ".png");
        }
        return output;
    }

    private void debugInput(DebugClient client, List<String> arguments, boolean json) throws IOException {
        require(!arguments.isEmpty(), "Usage: ./play.sh debug input key CODE ACTION | text TEXT | mouse X Y | scroll X Y");
        String type = arguments.remove(0);
        ObjectNode body = DebugJson.MAPPER.createObjectNode();
        ObjectNode event = body.putArray("events").addObject();
        switch (type) {
            case "key" -> {
                require(arguments.size() == 2, "Usage: ./play.sh debug input key CODE ACTION");
                event.put("type", "key").put("code", arguments.remove(0)).put("action", arguments.remove(0));
            }
            case "text" -> {
                require(arguments.size() == 1, "Usage: ./play.sh debug input text TEXT");
                event.put("type", "text").put("text", arguments.remove(0));
            }
            case "mouse", "scroll" -> {
                require(arguments.size() == 2, "Usage: ./play.sh debug input " + type + " X Y");
                event.put("type", type.equals("mouse") ? "mouse_move" : "scroll")
                    .put("x", Double.parseDouble(arguments.remove(0))).put("y", Double.parseDouble(arguments.remove(0)));
            }
            default -> throw failure("Unknown debug input type: " + type);
        }
        printDebugJson(client.request("input.inject", body, 5_000), json);
    }

    private void debugBlocks(DebugClient client, String operation, List<String> arguments, boolean json) throws IOException {
        require(arguments.size() == 6, "Usage: ./play.sh debug " + (operation.equals("world.aoi") ? "aoi" : "blocks") + " MIN_X MIN_Y MIN_Z MAX_X MAX_Y MAX_Z");
        int[] values = new int[6];
        for (int index = 0; index < values.length; index++) values[index] = Integer.parseInt(arguments.remove(0));
        ObjectNode body = DebugJson.MAPPER.createObjectNode();
        body.putObject("min").put("x", values[0]).put("y", values[1]).put("z", values[2]);
        body.putObject("max").put("x", values[3]).put("y", values[4]).put("z", values[5]);
        printDebugJson(client.request(operation, body, 10_000), json);
    }

    private void runScenario(List<String> rawArguments) throws Exception {
        List<String> arguments = new ArrayList<>(rawArguments);
        require(!arguments.isEmpty() && arguments.remove(0).equals("run"),
            "Usage: ./play.sh scenario run FILE [--artifacts PATH] [--update-screenshots] [--jfr MODE] [--json]");
        require(!arguments.isEmpty(), "scenario run requires a JSON scenario file.");
        Path scenarioFile = resolveProjectPath(arguments.remove(0));
        require(Files.isRegularFile(scenarioFile), "Scenario file not found: " + scenarioFile);
        Path artifacts = null;
        boolean updateScreenshots = false;
        boolean json = false;
        String jfrOverride = null;
        Duration slowOverride = null;
        while (!arguments.isEmpty()) {
            String option = arguments.remove(0);
            if (option.equals("--artifacts")) {
                require(!arguments.isEmpty(), "--artifacts requires a path.");
                artifacts = resolveProjectPath(arguments.remove(0));
            } else if (option.startsWith("--artifacts=")) {
                artifacts = resolveProjectPath(option.substring("--artifacts=".length()));
            } else if (option.equals("--update-screenshots")) {
                updateScreenshots = true;
            } else if (option.equals("--jfr")) {
                require(!arguments.isEmpty(), "--jfr requires off, always, on-failure, or slow.");
                jfrOverride = arguments.remove(0);
            } else if (option.startsWith("--jfr=")) {
                jfrOverride = option.substring("--jfr=".length());
            } else if (option.equals("--slow-threshold")) {
                require(!arguments.isEmpty(), "--slow-threshold requires a duration.");
                slowOverride = parseDuration(arguments.remove(0), "--slow-threshold");
            } else if (option.startsWith("--slow-threshold=")) {
                slowOverride = parseDuration(option.substring("--slow-threshold=".length()), "--slow-threshold");
            } else if (option.equals("--trajectory")) {
                require(!arguments.isEmpty(), "--trajectory requires a name.");
                trajectory = arguments.remove(0);
            } else if (option.startsWith("--trajectory=")) {
                trajectory = option.substring("--trajectory=".length());
            } else if (option.equals("--json")) {
                json = true;
            } else {
                throw failure("Unknown scenario option: " + option);
            }
        }
        validateName("trajectory", trajectory);

        byte[] scenarioBytes = readBoundedFile(scenarioFile, MAX_SCENARIO_BYTES, "Scenario");
        JsonNode parsed = DebugJson.MAPPER.readTree(scenarioBytes);
        require(parsed.isObject(), "Scenario root must be a JSON object.");
        ObjectNode scenario = (ObjectNode) parsed;
        String scenarioName = scenario.path("name").asText(scenarioFile.getFileName().toString().replaceFirst("\\.json$", ""));
        require(SAFE_NAME.matcher(scenarioName).matches(), "Scenario name must use letters, digits, dot, underscore, or hyphen.");
        ArrayNode steps = requireArray(scenario, "steps");
        require(!steps.isEmpty(), "Scenario must contain at least one step.");
        require(steps.size() <= 10_000, "Scenario must not contain more than 10000 steps.");
        int repeat = scenario.path("repeat").asInt(1);
        require(repeat >= 1 && repeat <= 10_000, "Scenario repeat must be between 1 and 10000.");
        int maxCases = scenario.path("maxCases").asInt(10_000);
        require(maxCases >= 1 && maxCases <= 100_000, "Scenario maxCases must be between 1 and 100000.");
        Duration soakDuration = scenario.has("duration") ? parseDuration(scenario.path("duration").asText(), "scenario duration") : Duration.ZERO;
        require(soakDuration.compareTo(Duration.ofHours(24)) <= 0, "Scenario duration must not exceed 24 hours.");
        List<Map<String, String>> matrix = expandMatrix(scenario.path("matrix"));
        require(matrix.size() <= 64, "Scenario matrix expands to more than 64 cases.");

        String runId = safeRunId(scenarioName);
        if (artifacts == null) artifacts = runDirectory.resolve("acceptance").resolve(runId);
        Files.createDirectories(artifacts);
        Files.createDirectories(artifacts.resolve("screenshots"));
        Files.createDirectories(artifacts.resolve("metrics"));
        Files.createDirectories(artifacts.resolve("jfr"));

        String jfrMode = jfrOverride == null ? scenario.path("jfr").path("mode").asText("off") : jfrOverride;
        require(Set.of("off", "always", "on-failure", "slow").contains(jfrMode), "JFR mode must be off, always, on-failure, or slow.");
        Duration slowThreshold = slowOverride != null ? slowOverride :
            (scenario.path("jfr").has("slowThreshold") ? parseDuration(scenario.path("jfr").path("slowThreshold").asText(), "JFR slowThreshold") : Duration.ofMinutes(2));

        ObjectNode report = DebugJson.MAPPER.createObjectNode();
        report.put("schema", 1).put("name", scenarioName).put("runId", runId);
        report.put("scenario", scenarioFile.toString()).put("trajectory", trajectory);
        report.put("startedAt", Instant.now().toString()).put("artifactDirectory", artifacts.toString());
        ArrayNode cases = report.putArray("cases");
        ArrayNode warnings = report.putArray("warnings");
        long suiteStarted = System.nanoTime();
        List<JfrCapture> jfr = startJfr(jfrMode, runId, warnings);
        boolean passed = true;
        int caseNumber = 0;
        int cycle = 0;
        soak:
        do {
            for (Map<String, String> variables : matrix) {
                for (int iteration = 1; iteration <= repeat; iteration++) {
                    if (caseNumber >= maxCases) {
                        passed = false;
                        warnings.add("Scenario stopped after reaching maxCases=" + maxCases + ".");
                        break soak;
                    }
                    caseNumber++;
                    ObjectNode caseReport = cases.addObject();
                    String caseName = caseName(scenarioName, variables, iteration, cycle);
                    caseReport.put("name", caseName).put("iteration", iteration).put("cycle", cycle);
                    ObjectNode variableNode = caseReport.putObject("variables");
                    variables.forEach(variableNode::put);
                    long caseStarted = System.nanoTime();
                    ArrayNode stepReports = caseReport.putArray("steps");
                    boolean casePassed = true;
                    for (int index = 0; index < steps.size(); index++) {
                        JsonNode expanded = substitute(steps.get(index), variables);
                        ObjectNode stepReport = stepReports.addObject();
                        try {
                            executeScenarioStep(expanded, scenarioFile, artifacts, caseName, index, updateScreenshots, stepReport);
                            stepReport.put("passed", true);
                        } catch (Exception error) {
                            casePassed = false;
                            passed = false;
                            stepReport.put("passed", false).put("error", conciseError(error));
                            break;
                        }
                    }
                    caseReport.put("passed", casePassed);
                    caseReport.put("durationMs", elapsedMillis(caseStarted));
                    if (!casePassed && !soakDuration.isZero()) {
                        warnings.add("Soak stopped after the first failed case.");
                        break soak;
                    }
                }
            }
            cycle++;
        } while (!soakDuration.isZero() && System.nanoTime() - suiteStarted < soakDuration.toNanos());
        long durationMs = elapsedMillis(suiteStarted);
        report.put("finishedAt", Instant.now().toString());
        report.put("durationMs", durationMs).put("passed", passed).put("caseCount", caseNumber).put("maxCases", maxCases);

        boolean retainJfr = jfrMode.equals("always") || (jfrMode.equals("on-failure") && !passed)
            || (jfrMode.equals("slow") && durationMs >= slowThreshold.toMillis());
        finishJfr(jfr, retainJfr, artifacts.resolve("jfr"), warnings);
        snapshotScenarioMetrics(artifacts.resolve("metrics"), warnings);
        report.put("jfrMode", jfrMode).put("jfrStarted", jfr.size()).put("jfrRetained", retainJfr);
        Path reportFile = artifacts.resolve("report.json");
        DebugJson.MAPPER.writerWithDefaultPrettyPrinter().writeValue(reportFile.toFile(), report);
        writeJUnit(artifacts.resolve("junit.xml"), scenarioName, cases, durationMs);

        ObjectNode summary = DebugJson.MAPPER.createObjectNode()
            .put("name", scenarioName).put("runId", runId).put("passed", passed)
            .put("cases", caseNumber).put("durationMs", durationMs)
            .put("report", reportFile.toString()).put("junit", artifacts.resolve("junit.xml").toString());
        printDebugJson(summary, json);
        require(passed, "Scenario failed; see " + reportFile + ".");
    }

    private void runScreenshot(List<String> rawArguments) throws Exception {
        List<String> arguments = new ArrayList<>(rawArguments);
        require(!arguments.isEmpty(),
            "Usage: ./play.sh screenshot compare BASELINE ACTUAL [THRESHOLDS] | crop INPUT OUTPUT --region X,Y,W,H [--json]");
        String action = arguments.remove(0);
        if (action.equals("crop")) {
            runScreenshotCrop(arguments);
            return;
        }
        require(action.equals("compare"),
            "Usage: ./play.sh screenshot compare BASELINE ACTUAL [--pixel-threshold N] [--max-changed-ratio N] [--max-mean-error N] [--json]");
        require(arguments.size() >= 2, "screenshot compare requires BASELINE and ACTUAL PNG files.");
        Path baseline = resolveProjectPath(arguments.remove(0));
        Path actual = resolveProjectPath(arguments.remove(0));
        int pixelThreshold = 0;
        double maxChangedRatio = 0.0;
        double maxMeanError = 0.0;
        boolean json = false;
        while (!arguments.isEmpty()) {
            String option = arguments.remove(0);
            if (option.equals("--pixel-threshold")) {
                require(!arguments.isEmpty(), "--pixel-threshold requires an integer.");
                pixelThreshold = Integer.parseInt(arguments.remove(0));
            } else if (option.startsWith("--pixel-threshold=")) {
                pixelThreshold = Integer.parseInt(option.substring("--pixel-threshold=".length()));
            } else if (option.equals("--max-changed-ratio")) {
                require(!arguments.isEmpty(), "--max-changed-ratio requires a number.");
                maxChangedRatio = Double.parseDouble(arguments.remove(0));
            } else if (option.startsWith("--max-changed-ratio=")) {
                maxChangedRatio = Double.parseDouble(option.substring("--max-changed-ratio=".length()));
            } else if (option.equals("--max-mean-error")) {
                require(!arguments.isEmpty(), "--max-mean-error requires a number.");
                maxMeanError = Double.parseDouble(arguments.remove(0));
            } else if (option.startsWith("--max-mean-error=")) {
                maxMeanError = Double.parseDouble(option.substring("--max-mean-error=".length()));
            } else if (option.equals("--json")) {
                json = true;
            } else {
                throw failure("Unknown screenshot compare option: " + option);
            }
        }
        ScreenshotComparison comparison = compareScreenshots(baseline, actual, pixelThreshold);
        boolean passed = comparison.changedRatio <= maxChangedRatio && comparison.meanAbsoluteError <= maxMeanError;
        ObjectNode result = DebugJson.MAPPER.createObjectNode();
        result.put("baseline", baseline.toString()).put("actual", actual.toString());
        result.put("width", comparison.width).put("height", comparison.height);
        result.put("changedPixels", comparison.changedPixels).put("changedRatio", comparison.changedRatio);
        result.put("meanAbsoluteError", comparison.meanAbsoluteError).put("maxChannelError", comparison.maxChannelError);
        result.put("pixelThreshold", pixelThreshold).put("maxChangedRatio", maxChangedRatio).put("maxMeanError", maxMeanError);
        result.put("passed", passed);
        printDebugJson(result, json);
        require(passed, "Screenshot regression comparison failed.");
    }

    private void runScreenshotCrop(List<String> arguments) throws Exception {
        require(arguments.size() >= 2, "screenshot crop requires INPUT and OUTPUT PNG files.");
        Path input = resolveProjectPath(arguments.remove(0));
        Path output = resolveProjectPath(arguments.remove(0));
        ScreenshotRegion region = null;
        boolean json = false;
        while (!arguments.isEmpty()) {
            String option = arguments.remove(0);
            if (option.equals("--region")) {
                require(!arguments.isEmpty(), "--region requires X,Y,W,H.");
                region = parseScreenshotRegion(arguments.remove(0));
            } else if (option.startsWith("--region=")) {
                region = parseScreenshotRegion(option.substring("--region=".length()));
            } else if (option.equals("--json")) {
                json = true;
            } else {
                throw failure("Unknown screenshot crop option: " + option);
            }
        }
        require(region != null, "screenshot crop requires --region X,Y,W,H.");
        ScreenshotCrop crop = cropScreenshot(input, output, region);
        ObjectNode result = DebugJson.MAPPER.createObjectNode();
        result.put("input", input.toString()).put("output", output.toString());
        result.put("sourceWidth", crop.sourceWidth).put("sourceHeight", crop.sourceHeight);
        result.put("x", region.x).put("y", region.y);
        result.put("width", region.width).put("height", region.height);
        printDebugJson(result, json);
    }

    private void executeScenarioStep(JsonNode step, Path scenarioFile, Path artifacts, String caseName, int index,
                                     boolean updateScreenshots, ObjectNode report) throws Exception {
        require(step.isObject(), "Scenario step " + (index + 1) + " must be an object.");
        String type = step.path("type").asText("");
        String name = step.path("name").asText(type.isBlank() ? "step-" + (index + 1) : type);
        report.put("name", name).put("type", type);
        long started = System.nanoTime();
        try {
            switch (type) {
                case "wait" -> {
                    String predicate = requiredText(step, "predicate");
                    Duration timeout = step.has("timeout") ? parseDuration(step.path("timeout").asText(), "step timeout") : Duration.ofSeconds(120);
                    PredicateObservation observation = waitForPredicate(predicate, timeout, null);
                    report.set("result", observation.value);
                    require(observation.matched, "predicate did not match before timeout: " + predicate);
                }
                case "request" -> {
                    DebugEndpointRole role = parseRole(step.path("role").asText("client"));
                    String operation = requiredText(step, "operation");
                    JsonNode body = step.has("body") ? step.path("body") : DebugJson.MAPPER.createObjectNode();
                    long deadlineMs = step.path("deadlineMs").asLong(5_000);
                    JsonNode result = scenarioRequest(role, operation, body, deadlineMs);
                    report.set("result", result);
                    assertScenario(result, step.path("assert"));
                }
                case "screenshot" -> {
                    String baselineValue = requiredText(step, "baseline");
                    Path baseline = scenarioFile.getParent().resolve(baselineValue).normalize();
                    require(baseline.startsWith(project), "Screenshot baseline must remain beneath the project directory.");
                    String fileName = safeFileName(caseName + "-" + (index + 1) + "-" + name) + ".png";
                    Path actual = artifacts.resolve("screenshots").resolve(fileName);
                    DebugEndpointDescriptor endpoint = selectedEndpoint(DebugEndpointRole.CLIENT);
                    try (DebugClient client = DebugClient.connect(DebugPaths.system(), endpoint)) {
                        DebugResponse response = client.requestWithAttachment("visual.capture", DebugJson.MAPPER.createObjectNode(), 10_000);
                        require(response.hasAttachment(), "visual.capture returned no PNG.");
                        Files.write(actual, response.attachment());
                    }
                    JsonNode regionNode = step.path("region");
                    if (!regionNode.isMissingNode()) {
                        ScreenshotRegion region = parseScreenshotRegion(regionNode);
                        ScreenshotCrop crop = cropScreenshot(actual, actual, region);
                        if (step.has("sourceWidth")) {
                            require(crop.sourceWidth == step.path("sourceWidth").asInt(),
                                "Screenshot source width differs: expected " + step.path("sourceWidth").asInt()
                                    + ", got " + crop.sourceWidth);
                        }
                        if (step.has("sourceHeight")) {
                            require(crop.sourceHeight == step.path("sourceHeight").asInt(),
                                "Screenshot source height differs: expected " + step.path("sourceHeight").asInt()
                                    + ", got " + crop.sourceHeight);
                        }
                        report.putArray("region")
                            .add(region.x).add(region.y).add(region.width).add(region.height);
                        report.put("sourceWidth", crop.sourceWidth).put("sourceHeight", crop.sourceHeight);
                    }
                    if (updateScreenshots) {
                        Files.createDirectories(baseline.getParent());
                        Files.copy(actual, baseline, StandardCopyOption.REPLACE_EXISTING);
                    }
                    require(Files.isRegularFile(baseline), "Screenshot baseline is missing: " + baseline + " (use --update-screenshots to create it)");
                    ScreenshotComparison comparison = compareScreenshots(baseline, actual, step.path("pixelThreshold").asInt(0));
                    double maxChangedRatio = step.path("maxChangedRatio").asDouble(0.0);
                    double maxMeanError = step.path("maxMeanError").asDouble(0.0);
                    ObjectNode result = report.putObject("result");
                    result.put("baseline", baseline.toString()).put("actual", actual.toString());
                    result.put("width", comparison.width).put("height", comparison.height);
                    result.put("changedPixels", comparison.changedPixels).put("changedRatio", comparison.changedRatio);
                    result.put("meanAbsoluteError", comparison.meanAbsoluteError).put("maxChannelError", comparison.maxChannelError);
                    require(comparison.changedRatio <= maxChangedRatio,
                        "screenshot changed ratio " + comparison.changedRatio + " exceeds " + maxChangedRatio);
                    require(comparison.meanAbsoluteError <= maxMeanError,
                        "screenshot mean error " + comparison.meanAbsoluteError + " exceeds " + maxMeanError);
                }
                case "sleep" -> {
                    Duration duration = parseDuration(requiredText(step, "duration"), "sleep duration");
                    require(duration.compareTo(Duration.ofMinutes(10)) <= 0, "One sleep step must not exceed 10 minutes.");
                    Thread.sleep(duration.toMillis());
                }
                default -> throw failure("Unknown scenario step type: " + type);
            }
        } finally {
            report.put("durationMs", elapsedMillis(started));
        }
    }

    private JsonNode scenarioRequest(DebugEndpointRole role, String operation, JsonNode body, long deadlineMs) throws Exception {
        DebugEndpointDescriptor endpoint = selectedEndpoint(role);
        try (DebugClient client = DebugClient.connect(DebugPaths.system(), endpoint)) {
            return client.request(operation, body, deadlineMs);
        }
    }

    private DebugEndpointDescriptor selectedEndpoint(DebugEndpointRole role) throws IOException {
        DebugSelection selection = new DebugSelection();
        selection.role = role;
        selection.trajectory = trajectory;
        selection.trajectorySpecified = true;
        return selectDebugEndpoint(new DebugDiscovery(DebugPaths.system()), selection, role);
    }

    private static DebugEndpointRole parseRole(String value) {
        return switch (value) {
            case "client" -> DebugEndpointRole.CLIENT;
            case "server" -> DebugEndpointRole.SERVER;
            default -> throw failure("Scenario role must be client or server: " + value);
        };
    }

    private static void assertScenario(JsonNode result, JsonNode assertions) {
        if (assertions.isMissingNode() || assertions.isNull()) return;
        require(assertions.isArray(), "Scenario assert must be an array.");
        for (JsonNode assertion : assertions) {
            String pointer = assertion.path("pointer").asText("");
            require(pointer.isEmpty() || pointer.startsWith("/"), "Assertion pointer must be a JSON Pointer.");
            JsonNode actual = result.at(pointer);
            if (assertion.has("exists")) {
                boolean exists = !actual.isMissingNode();
                require(exists == assertion.path("exists").asBoolean(), "Assertion failed at " + pointer + ": exists=" + exists);
            }
            if (assertion.has("equals")) {
                require(actual.equals(assertion.path("equals")), "Assertion failed at " + pointer + ": expected " + assertion.path("equals") + ", got " + actual);
            }
            if (assertion.has("contains")) {
                String expected = assertion.path("contains").asText();
                require(actual.asText("").contains(expected), "Assertion failed at " + pointer + ": value does not contain " + expected);
            }
            if (assertion.has("min")) {
                require(actual.isNumber() && actual.asDouble() >= assertion.path("min").asDouble(),
                    "Assertion failed at " + pointer + ": value is below minimum");
            }
            if (assertion.has("max")) {
                require(actual.isNumber() && actual.asDouble() <= assertion.path("max").asDouble(),
                    "Assertion failed at " + pointer + ": value is above maximum");
            }
        }
    }

    private static ScreenshotComparison compareScreenshots(Path baseline, Path actual, int threshold) throws IOException {
        require(threshold >= 0 && threshold <= 255, "pixelThreshold must be between 0 and 255.");
        BufferedImage expected = readBoundedImage(baseline);
        BufferedImage observed = readBoundedImage(actual);
        require(expected.getWidth() == observed.getWidth() && expected.getHeight() == observed.getHeight(),
            "Screenshot dimensions differ: expected " + expected.getWidth() + "x" + expected.getHeight()
                + ", got " + observed.getWidth() + "x" + observed.getHeight());
        long changed = 0;
        long totalError = 0;
        int maxError = 0;
        long pixels = (long) expected.getWidth() * expected.getHeight();
        for (int y = 0; y < expected.getHeight(); y++) {
            for (int x = 0; x < expected.getWidth(); x++) {
                int left = expected.getRGB(x, y);
                int right = observed.getRGB(x, y);
                boolean pixelChanged = false;
                for (int shift : ARGB_CHANNEL_SHIFTS) {
                    int error = Math.abs(((left >>> shift) & 0xFF) - ((right >>> shift) & 0xFF));
                    totalError += error;
                    maxError = Math.max(maxError, error);
                    if (error > threshold) pixelChanged = true;
                }
                if (pixelChanged) changed++;
            }
        }
        return new ScreenshotComparison(expected.getWidth(), expected.getHeight(), changed,
            pixels == 0 ? 0.0 : (double) changed / pixels,
            pixels == 0 ? 0.0 : (double) totalError / (pixels * 4L), maxError);
    }

    private static ScreenshotRegion parseScreenshotRegion(String value) {
        String[] fields = value.split(",", -1);
        require(fields.length == 4, "Screenshot region must be X,Y,W,H.");
        try {
            return validatedScreenshotRegion(
                Integer.parseInt(fields[0]),
                Integer.parseInt(fields[1]),
                Integer.parseInt(fields[2]),
                Integer.parseInt(fields[3])
            );
        } catch (NumberFormatException error) {
            throw failure("Screenshot region must contain integers: " + value);
        }
    }

    private static ScreenshotRegion parseScreenshotRegion(JsonNode value) {
        require(value.isArray() && value.size() == 4, "Screenshot region must be [x,y,width,height].");
        for (JsonNode field : value) {
            require(field.isIntegralNumber() && field.canConvertToInt(),
                "Screenshot region fields must be integers.");
        }
        return validatedScreenshotRegion(
            value.get(0).asInt(),
            value.get(1).asInt(),
            value.get(2).asInt(),
            value.get(3).asInt()
        );
    }

    private static ScreenshotRegion validatedScreenshotRegion(int x, int y, int width, int height) {
        require(x >= 0 && y >= 0, "Screenshot region origin must be nonnegative.");
        require(width > 0 && height > 0, "Screenshot region dimensions must be positive.");
        require((long) width * height <= MAX_SCREENSHOT_PIXELS,
            "Screenshot region exceeds the " + MAX_SCREENSHOT_PIXELS + " pixel limit.");
        return new ScreenshotRegion(x, y, width, height);
    }

    private static ScreenshotCrop cropScreenshot(Path input, Path output, ScreenshotRegion region) throws IOException {
        BufferedImage source = readBoundedImage(input);
        require((long) region.x + region.width <= source.getWidth()
                && (long) region.y + region.height <= source.getHeight(),
            "Screenshot region " + region.x + "," + region.y + "," + region.width + "," + region.height
                + " exceeds source dimensions " + source.getWidth() + "x" + source.getHeight() + ".");
        BufferedImage cropped = new BufferedImage(region.width, region.height, BufferedImage.TYPE_INT_ARGB);
        var graphics = cropped.createGraphics();
        try {
            graphics.drawImage(
                source,
                0,
                0,
                region.width,
                region.height,
                region.x,
                region.y,
                region.x + region.width,
                region.y + region.height,
                null
            );
        } finally {
            graphics.dispose();
        }
        Path parent = output.toAbsolutePath().normalize().getParent();
        if (parent != null) Files.createDirectories(parent);
        require(ImageIO.write(cropped, "png", output.toFile()), "Could not encode cropped screenshot: " + output);
        return new ScreenshotCrop(source.getWidth(), source.getHeight());
    }

    private static BufferedImage readBoundedImage(Path path) throws IOException {
        require(Files.isRegularFile(path), "Screenshot is missing: " + path);
        require(Files.size(path) <= MAX_SCREENSHOT_FILE_BYTES,
            "Screenshot exceeds the " + MAX_SCREENSHOT_FILE_BYTES + " byte file limit: " + path);
        try (ImageInputStream input = ImageIO.createImageInputStream(path.toFile())) {
            require(input != null, "Could not open screenshot: " + path);
            var readers = ImageIO.getImageReaders(input);
            require(readers.hasNext(), "Could not decode screenshot: " + path);
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                long pixels = (long) width * height;
                require(width > 0 && height > 0 && pixels <= MAX_SCREENSHOT_PIXELS,
                    "Screenshot dimensions exceed the " + MAX_SCREENSHOT_PIXELS + " pixel limit: " + path);
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        }
    }

    private static byte[] readBoundedFile(Path path, int limit, String kind) throws IOException {
        require(Files.size(path) <= limit, kind + " exceeds the " + limit + " byte limit: " + path);
        try (InputStream input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(limit + 1);
            require(bytes.length <= limit, kind + " exceeds the " + limit + " byte limit: " + path);
            return bytes;
        }
    }

    private void snapshotScenarioMetrics(Path directory, ArrayNode warnings) {
        for (DebugEndpointRole role : DebugEndpointRole.values()) {
            try {
                JsonNode metrics = scenarioRequest(role, "metrics.snapshot", DebugJson.MAPPER.createObjectNode(), 5_000);
                DebugJson.MAPPER.writerWithDefaultPrettyPrinter().writeValue(directory.resolve(role.wireName() + ".json").toFile(), metrics);
            } catch (Exception error) {
                warnings.add("Could not snapshot " + role.wireName() + " metrics: " + conciseError(error));
            }
        }
    }

    private List<JfrCapture> startJfr(String mode, String runId, ArrayNode warnings) {
        if (mode.equals("off")) return List.of();
        Path jcmd = javaHome.resolve("bin").resolve(isWindows() ? "jcmd.exe" : "jcmd");
        if (!Files.isExecutable(jcmd)) {
            warnings.add("JFR requested but jcmd is unavailable at " + jcmd);
            return List.of();
        }
        List<JfrCapture> captures = new ArrayList<>();
        for (DebugEndpointRole role : DebugEndpointRole.values()) {
            try {
                DebugEndpointDescriptor endpoint = selectedEndpoint(role);
                String name = safeFileName("minosoft-" + runId + "-" + role.wireName());
                CommandResult result = runCommand(List.of(jcmd.toString(), Long.toString(endpoint.getPid()), "JFR.start",
                    "name=" + name, "settings=profile", "disk=true", "maxsize=256m"), project, Duration.ofSeconds(15));
                if (result.exitCode == 0) captures.add(new JfrCapture(endpoint.getPid(), role.wireName(), name, jcmd));
                else warnings.add("Could not start " + role.wireName() + " JFR: " + result.output);
            } catch (Exception error) {
                warnings.add("Could not start " + role.wireName() + " JFR: " + conciseError(error));
            }
        }
        return captures;
    }

    private void finishJfr(List<JfrCapture> captures, boolean retain, Path directory, ArrayNode warnings) {
        for (JfrCapture capture : captures) {
            try {
                if (retain) {
                    Path output = directory.resolve(capture.role + ".jfr").toAbsolutePath();
                    CommandResult dump = runCommand(List.of(capture.jcmd.toString(), Long.toString(capture.pid), "JFR.dump",
                        "name=" + capture.name, "filename=" + output), project, Duration.ofSeconds(30));
                    if (dump.exitCode != 0) warnings.add("Could not dump " + capture.role + " JFR: " + dump.output);
                }
                CommandResult stop = runCommand(List.of(capture.jcmd.toString(), Long.toString(capture.pid), "JFR.stop",
                    "name=" + capture.name), project, Duration.ofSeconds(15));
                if (stop.exitCode != 0) warnings.add("Could not stop " + capture.role + " JFR: " + stop.output);
            } catch (Exception error) {
                warnings.add("Could not finish " + capture.role + " JFR: " + conciseError(error));
            }
        }
    }

    private static CommandResult runCommand(List<String> command, Path directory, Duration timeout) throws Exception {
        Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        AtomicBoolean truncated = new AtomicBoolean();
        AtomicReference<IOException> readFailure = new AtomicReference<>();
        Thread reader = new Thread(() -> {
            byte[] chunk = new byte[8192];
            try (InputStream input = process.getInputStream()) {
                for (int count; (count = input.read(chunk)) >= 0;) {
                    int remaining = MAX_COMMAND_OUTPUT_BYTES - captured.size();
                    if (remaining > 0) captured.write(chunk, 0, Math.min(remaining, count));
                    if (count > remaining) truncated.set(true);
                }
            } catch (IOException error) {
                readFailure.set(error);
            }
        }, "minosoft-play-command-output");
        reader.setDaemon(true);
        reader.start();
        boolean finished;
        try {
            finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException error) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw error;
        }
        if (!finished) {
            process.destroy();
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
        }
        reader.join(5_000);
        if (reader.isAlive()) {
            process.getInputStream().close();
            reader.join(1_000);
        }
        IOException outputError = readFailure.get();
        if (outputError != null && finished) throw outputError;
        String output = new String(captured.toByteArray(), StandardCharsets.UTF_8).trim();
        if (truncated.get()) output += "\n[output truncated]";
        return new CommandResult(finished ? process.exitValue() : 124, finished ? output : "timed out\n" + output);
    }

    private static List<Map<String, String>> expandMatrix(JsonNode matrixNode) {
        List<Map<String, String>> result = new ArrayList<>();
        result.add(new LinkedHashMap<>());
        if (matrixNode.isMissingNode() || matrixNode.isNull()) return result;
        require(matrixNode.isObject(), "Scenario matrix must be an object of string arrays.");
        List<String> keys = new ArrayList<>();
        matrixNode.fieldNames().forEachRemaining(keys::add);
        keys.sort(Comparator.naturalOrder());
        for (String key : keys) {
            require(SAFE_NAME.matcher(key).matches(), "Invalid matrix variable name: " + key);
            JsonNode values = matrixNode.path(key);
            require(values.isArray() && !values.isEmpty(), "Matrix variable " + key + " must be a non-empty array.");
            List<Map<String, String>> expanded = new ArrayList<>();
            for (Map<String, String> existing : result) {
                for (JsonNode value : values) {
                    require(value.isValueNode(), "Matrix values must be scalar.");
                    Map<String, String> copy = new LinkedHashMap<>(existing);
                    copy.put(key, value.asText());
                    expanded.add(copy);
                    require(expanded.size() <= 64, "Scenario matrix expands to more than 64 cases.");
                }
            }
            result = expanded;
        }
        return result;
    }

    private static JsonNode substitute(JsonNode node, Map<String, String> variables) {
        if (node.isTextual()) {
            String value = node.asText();
            for (Map.Entry<String, String> variable : variables.entrySet()) {
                value = value.replace("${" + variable.getKey() + "}", variable.getValue());
            }
            return DebugJson.MAPPER.getNodeFactory().textNode(value);
        }
        if (node.isArray()) {
            ArrayNode result = DebugJson.MAPPER.createArrayNode();
            node.forEach(value -> result.add(substitute(value, variables)));
            return result;
        }
        if (node.isObject()) {
            ObjectNode result = DebugJson.MAPPER.createObjectNode();
            node.fields().forEachRemaining(entry -> result.set(entry.getKey(), substitute(entry.getValue(), variables)));
            return result;
        }
        return node.deepCopy();
    }

    private static String caseName(String scenario, Map<String, String> variables, int iteration, int cycle) {
        String suffix = variables.entrySet().stream().map(entry -> entry.getKey() + "=" + entry.getValue()).collect(Collectors.joining(","));
        if (!suffix.isBlank()) suffix = "[" + suffix + "]";
        if (iteration > 1 || cycle > 0) suffix += "#c" + cycle + "i" + iteration;
        return scenario + suffix;
    }

    private static String safeRunId(String scenario) {
        return scenario + "-" + Instant.now().toString().replaceAll("[:.]", "-") + "-" + ProcessHandle.current().pid();
    }

    private static String safeFileName(String value) {
        String safe = value.replaceAll("[^A-Za-z0-9._-]+", "_");
        return safe.length() <= 160 ? safe : safe.substring(0, 160);
    }

    private static String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText("");
        require(!value.isBlank(), "Scenario field '" + field + "' is required.");
        return value;
    }

    private static ArrayNode requireArray(JsonNode node, String field) {
        JsonNode value = node.path(field);
        require(value.isArray(), "Scenario field '" + field + "' must be an array.");
        return (ArrayNode) value;
    }

    private static String conciseError(Throwable error) {
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private static void writeJUnit(Path file, String suite, ArrayNode cases, long durationMs) throws IOException {
        int failures = 0;
        for (JsonNode testCase : cases) if (!testCase.path("passed").asBoolean()) failures++;
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<testsuite name=\"").append(xmlEscape(suite)).append("\" tests=\"").append(cases.size())
            .append("\" failures=\"").append(failures).append("\" time=\"").append(durationMs / 1000.0).append("\">\n");
        for (JsonNode testCase : cases) {
            xml.append("  <testcase classname=\"minosoft.acceptance\" name=\"").append(xmlEscape(testCase.path("name").asText()))
                .append("\" time=\"").append(testCase.path("durationMs").asDouble() / 1000.0).append("\">");
            if (!testCase.path("passed").asBoolean()) {
                JsonNode failed = null;
                for (JsonNode step : testCase.path("steps")) if (!step.path("passed").asBoolean()) { failed = step; break; }
                String message = failed == null ? "scenario failed" : failed.path("error").asText("scenario failed");
                xml.append("<failure message=\"").append(xmlEscape(message)).append("\">")
                    .append(xmlEscape(failed == null ? testCase.toString() : failed.toString())).append("</failure>");
            }
            xml.append("</testcase>\n");
        }
        xml.append("</testsuite>\n");
        Files.writeString(file, xml, StandardCharsets.UTF_8);
    }

    private static String xmlEscape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static int[] parseInts(String value, int count, String label) {
        String[] parts = value.split(",", -1);
        require(parts.length == count, label + " requires " + count + " comma-separated integers.");
        int[] result = new int[count];
        try {
            for (int index = 0; index < count; index++) result[index] = Integer.parseInt(parts[index]);
        } catch (NumberFormatException error) {
            throw failure(label + " requires comma-separated integers.");
        }
        return result;
    }

    private static ObjectNode endpointJson(DebugEndpointDescriptor endpoint) {
        return DebugJson.MAPPER.createObjectNode()
            .put("id", endpoint.getId()).put("role", endpoint.getRole().wireName()).put("pid", endpoint.getPid())
            .put("processStart", endpoint.getProcessStart().toString()).put("trajectory", endpoint.getTrajectory())
            .put("generation", endpoint.getGeneration()).put("transport", endpoint.getTransport())
            .put("address", endpoint.getAddress()).put("protocolMin", endpoint.getProtocolMin()).put("protocolMax", endpoint.getProtocolMax());
    }

    private static void printDebugJson(JsonNode value, boolean compact) throws IOException {
        System.out.println(compact ? DebugJson.MAPPER.writeValueAsString(value) : DebugJson.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value));
    }

    private void runModpack(List<String> rawArguments) throws Exception {
        List<String> arguments = new ArrayList<>(rawArguments);
        String action = arguments.isEmpty() ? "list" : arguments.remove(0);
        if (action.equals("list")) {
            require(arguments.isEmpty(), "modpack list does not accept additional arguments.");
            listModpacks();
            return;
        }
        if (action.equals("cache")) {
            runModpackCache(arguments);
            return;
        }
        require(action.equals("prepare") || action.equals("inspect"), "Unknown modpack action: " + action);
        require(!arguments.isEmpty(), "Usage: ./play.sh modpack " + action + " NAME [--trajectory NAME]");
        modpackName = arguments.remove(0);
        parseTrajectoryOption(arguments);
        PreparedPack pack = prepareModpack(modpackName, trajectory);
        if (action.equals("inspect")) {
            installDistribution();
            runInherited(List.of(javaBin.toString(), "-cp", project.resolve("build/install/minosoft/lib/*").toString(), FABRIC_PREFLIGHT, pack.view.toString()), project);
        }
    }

    private void runModpackCache(List<String> arguments) throws Exception {
        require(!arguments.isEmpty() && arguments.remove(0).equals("add"),
            "Usage: ./play.sh modpack cache add FILE [--hash-format sha256|sha512]");
        require(!arguments.isEmpty(), "modpack cache add requires a local artifact path.");
        Path source = resolveProjectPath(arguments.remove(0));
        String hashFormat = "sha512";
        while (!arguments.isEmpty()) {
            String option = arguments.remove(0);
            if (option.equals("--hash-format")) {
                require(!arguments.isEmpty(), "--hash-format requires sha256 or sha512.");
                hashFormat = arguments.remove(0);
            } else if (option.startsWith("--hash-format=")) {
                hashFormat = option.substring("--hash-format=".length());
            } else {
                throw failure("Unknown modpack cache option: " + option);
            }
        }
        require(Set.of("sha256", "sha512").contains(hashFormat), "--hash-format requires sha256 or sha512.");
        require(modpackCache != null, "Set MINOSOFT_MODPACK_CACHE to the portable cache directory.");
        require(Files.isRegularFile(source), "Portable cache source is not a regular file: " + source);
        require(Files.size(source) <= MAX_MODPACK_ARTIFACT_BYTES,
            "Portable cache source exceeds the 1 GiB artifact limit: " + source);
        String filename = source.getFileName().toString();
        require(SAFE_MANAGED_FILENAME.matcher(filename).matches(), "Unsafe portable cache filename: " + filename);
        String fingerprint = hash(hashFormat, source);
        Path artifact = portableCacheArtifact(hashFormat, fingerprint, filename);
        publishPortableCacheArtifact(source, artifact, hashFormat, fingerprint);
        System.out.printf("Cached %s in the portable modpack cache.%n", filename);
        System.out.println("  " + hashFormat + ": " + fingerprint);
        System.out.println("  path: " + artifact);
    }

    private void runWorldgen(List<String> rawArguments) throws Exception {
        List<String> arguments = new ArrayList<>(rawArguments);
        require(!arguments.isEmpty(), "Usage: ./play.sh worldgen inspect [WORLD] | compare BASELINE CANDIDATE [--json]");
        String action = arguments.remove(0);
        boolean json = false;
        boolean allowDifferentSeed = false;
        int maxChunks = 4096;
        List<String> paths = new ArrayList<>();
        while (!arguments.isEmpty()) {
            String option = arguments.remove(0);
            if (option.equals("--json")) {
                json = true;
            } else if (option.equals("--allow-different-seed")) {
                allowDifferentSeed = true;
            } else if (option.equals("--max-chunks")) {
                require(!arguments.isEmpty(), "--max-chunks requires an integer.");
                maxChunks = Integer.parseInt(arguments.remove(0));
            } else if (option.startsWith("--max-chunks=")) {
                maxChunks = Integer.parseInt(option.substring("--max-chunks=".length()));
            } else {
                paths.add(option);
            }
        }
        require(maxChunks >= 1 && maxChunks <= 1_000_000, "--max-chunks must be between 1 and 1000000.");
        if (action.equals("inspect")) {
            require(paths.size() <= 1, "worldgen inspect accepts at most one world directory.");
            Path world = paths.isEmpty() ? defaultWorldDirectory() : resolveProjectPath(paths.get(0));
            WorldgenInspection inspection = inspectWorld(world, maxChunks);
            printDebugJson(inspection.json, json);
            return;
        }
        require(action.equals("compare"), "Unknown worldgen command: " + action);
        require(paths.size() == 2, "worldgen compare requires BASELINE and CANDIDATE world directories.");
        WorldgenInspection baseline = inspectWorld(resolveProjectPath(paths.get(0)), maxChunks);
        WorldgenInspection candidate = inspectWorld(resolveProjectPath(paths.get(1)), maxChunks);
        boolean seedEqual = baseline.seed == candidate.seed;
        boolean chunkCoordinatesEqual = baseline.chunkCoordinates.equals(candidate.chunkCoordinates);
        boolean terrainEqual = baseline.terrainHash.equals(candidate.terrainHash);
        ObjectNode result = DebugJson.MAPPER.createObjectNode();
        result.put("schema", 1).put("baseline", baseline.world.toString()).put("candidate", candidate.world.toString());
        result.put("baselineSeed", baseline.seed).put("candidateSeed", candidate.seed).put("seedEqual", seedEqual);
        result.put("chunkCoordinatesEqual", chunkCoordinatesEqual).put("terrainEqual", terrainEqual);
        result.put("baselineTerrainSha256", baseline.terrainHash).put("candidateTerrainSha256", candidate.terrainHash);
        result.put("baselineChunks", baseline.chunkCoordinates.size()).put("candidateChunks", candidate.chunkCoordinates.size());
        boolean equal = (seedEqual || allowDifferentSeed) && chunkCoordinatesEqual && terrainEqual;
        result.put("equal", equal).put("allowDifferentSeed", allowDifferentSeed);
        printDebugJson(result, json);
        require(equal, "World-generation A/B comparison failed.");
    }

    private Path defaultWorldDirectory() throws IOException {
        Path properties = serverDirectory.resolve("server.properties");
        String levelName = "world";
        if (Files.isRegularFile(properties)) {
            for (String line : Files.readAllLines(properties, StandardCharsets.UTF_8)) {
                if (line.startsWith("level-name=")) levelName = line.substring("level-name=".length()).trim();
            }
        }
        return serverDirectory.resolve(levelName).toAbsolutePath().normalize();
    }

    private WorldgenInspection inspectWorld(Path world, int maxChunks) throws Exception {
        world = world.toAbsolutePath().normalize();
        require(Files.isDirectory(world), "World directory not found: " + world);
        Path levelFile = world.resolve("level.dat");
        require(Files.isRegularFile(levelFile), "World level.dat not found: " + levelFile);
        Map<String, Object> level = rootCompound(readNbt(new GZIPInputStream(Files.newInputStream(levelFile))));
        Map<String, Object> data = compound(level.get("Data"));
        long seed = number(compound(data.get("WorldGenSettings")).get("seed"), number(data.get("RandomSeed"), 0L));
        TreeSet<String> dataPacks = new TreeSet<>();
        Object enabled = compound(data.get("DataPacks")).get("Enabled");
        if (enabled instanceof List<?>) for (Object value : (List<?>) enabled) dataPacks.add(String.valueOf(value));

        List<Path> regionFiles = new ArrayList<>();
        Path regionDirectory = world.resolve("region");
        require(Files.isDirectory(regionDirectory), "World has no overworld region directory: " + regionDirectory);
        try (var paths = Files.list(regionDirectory)) {
            paths.filter(path -> path.getFileName().toString().matches("r\\.-?[0-9]+\\.-?[0-9]+\\.mca"))
                .sorted().forEach(regionFiles::add);
        }
        MessageDigest terrain = digest("sha256");
        TreeSet<String> chunkCoordinates = new TreeSet<>();
        TreeSet<String> biomes = new TreeSet<>();
        List<Integer> allHeights = new ArrayList<>();
        Map<Long, Integer> surface = new HashMap<>();
        int chunks = 0;
        int fullChunks = 0;
        int failedChunks = 0;
        int maxChunkRelief = 0;
        outer:
        for (Path region : regionFiles) {
            String[] regionName = region.getFileName().toString().split("\\.");
            int regionX = Integer.parseInt(regionName[1]);
            int regionZ = Integer.parseInt(regionName[2]);
            try (RandomAccessFile file = new RandomAccessFile(region.toFile(), "r")) {
                for (int slot = 0; slot < 1024; slot++) {
                    file.seek(slot * 4L);
                    int location = file.readInt();
                    int sectorOffset = location >>> 8;
                    int sectorCount = location & 0xFF;
                    if (sectorOffset == 0 || sectorCount == 0) continue;
                    if (chunks >= maxChunks) break outer;
                    try {
                        file.seek(sectorOffset * 4096L);
                        int length = file.readInt();
                        require(length >= 2 && length <= sectorCount * 4096 - 4 && length <= 64 * 1024 * 1024,
                            "Invalid chunk length in " + region + " slot " + slot);
                        int compression = file.readUnsignedByte();
                        require((compression & 0x80) == 0, "External .mcc chunks are not supported: " + region + " slot " + slot);
                        byte[] compressed = new byte[length - 1];
                        file.readFully(compressed);
                        InputStream payload = switch (compression) {
                            case 1 -> new GZIPInputStream(new ByteArrayInputStream(compressed));
                            case 2 -> new InflaterInputStream(new ByteArrayInputStream(compressed));
                            case 3 -> new ByteArrayInputStream(compressed);
                            default -> throw failure("Unsupported region compression " + compression);
                        };
                        Map<String, Object> root = rootCompound(readNbt(payload));
                        Map<String, Object> chunk = compound(root.containsKey("Level") ? root.get("Level") : root);
                        int fallbackX = regionX * 32 + slot % 32;
                        int fallbackZ = regionZ * 32 + slot / 32;
                        int chunkX = (int) number(chunk.get("xPos"), fallbackX);
                        int chunkZ = (int) number(chunk.get("zPos"), fallbackZ);
                        String coordinate = chunkX + "," + chunkZ;
                        chunkCoordinates.add(coordinate);
                        String status = String.valueOf(chunk.getOrDefault("Status", ""));
                        if (status.endsWith(":full") || status.equals("full")) fullChunks++;
                        chunks++;

                        updateDigest(terrain, "chunk", coordinate, status);
                        List<Map<String, Object>> sections = compoundList(chunk.get("sections"));
                        if (sections.isEmpty()) sections = compoundList(chunk.get("Sections"));
                        sections.sort(Comparator.comparingInt(section -> (int) number(section.get("Y"), 0)));
                        int minSectionY = sections.stream().mapToInt(section -> (int) number(section.get("Y"), 0)).min().orElse(-4);
                        for (Map<String, Object> section : sections) {
                            int sectionY = (int) number(section.get("Y"), 0);
                            updateDigest(terrain, "section", Integer.toString(sectionY));
                            Map<String, Object> sectionBiomes = compound(section.get("biomes"));
                            List<Object> biomePalette = list(sectionBiomes.get("palette"));
                            for (Object biome : biomePalette) biomes.add(String.valueOf(biome));
                            updateDigest(terrain, "biomes", canonicalNbt(biomePalette));
                            updateLongArrayDigest(terrain, longArray(sectionBiomes.get("data")));
                            Map<String, Object> blockStates = compound(section.get("block_states"));
                            updateDigest(terrain, "blocks", canonicalNbt(blockStates.get("palette")));
                            updateLongArrayDigest(terrain, longArray(blockStates.get("data")));
                        }
                        Map<String, Object> heightmaps = compound(chunk.get("Heightmaps"));
                        long[] heights = longArray(heightmaps.get("WORLD_SURFACE"));
                        if (heights.length == 0) heights = longArray(heightmaps.get("MOTION_BLOCKING"));
                        updateLongArrayDigest(terrain, heights);
                        int[] decoded = decodeHeightmap(heights, minSectionY * 16);
                        if (decoded.length == 256) {
                            int chunkMin = Integer.MAX_VALUE;
                            int chunkMax = Integer.MIN_VALUE;
                            for (int localZ = 0; localZ < 16; localZ++) {
                                for (int localX = 0; localX < 16; localX++) {
                                    int height = decoded[localZ * 16 + localX];
                                    allHeights.add(height);
                                    chunkMin = Math.min(chunkMin, height);
                                    chunkMax = Math.max(chunkMax, height);
                                    long key = (((long) chunkX * 16 + localX) << 32) ^ (((long) chunkZ * 16 + localZ) & 0xFFFFFFFFL);
                                    surface.put(key, height);
                                }
                            }
                            maxChunkRelief = Math.max(maxChunkRelief, chunkMax - chunkMin);
                        }
                    } catch (Exception error) {
                        failedChunks++;
                    }
                }
            }
        }
        require(chunks > 0, "No readable chunks were found in " + regionDirectory);
        int maxAdjacentGradient = 0;
        for (Map.Entry<Long, Integer> entry : surface.entrySet()) {
            long x = entry.getKey() >> 32;
            long z = (int) (long) entry.getKey();
            Integer east = surface.get(((x + 1) << 32) ^ (z & 0xFFFFFFFFL));
            Integer south = surface.get((x << 32) ^ ((z + 1) & 0xFFFFFFFFL));
            if (east != null) maxAdjacentGradient = Math.max(maxAdjacentGradient, Math.abs(entry.getValue() - east));
            if (south != null) maxAdjacentGradient = Math.max(maxAdjacentGradient, Math.abs(entry.getValue() - south));
        }
        allHeights.sort(Comparator.naturalOrder());
        long sum = 0;
        for (int height : allHeights) sum += height;
        String terrainHash = HexFormat.of().formatHex(terrain.digest());
        ObjectNode result = DebugJson.MAPPER.createObjectNode();
        result.put("schema", 1).put("world", world.toString()).put("seed", seed);
        result.put("regionFiles", regionFiles.size()).put("chunks", chunks).put("fullChunks", fullChunks);
        result.put("failedChunks", failedChunks).put("maxChunks", maxChunks).put("truncated", chunks >= maxChunks);
        result.put("terrainSha256", terrainHash);
        ArrayNode packs = result.putArray("dataPacks"); dataPacks.forEach(packs::add);
        ArrayNode biomeArray = result.putArray("biomes"); biomes.forEach(biomeArray::add);
        ObjectNode shape = result.putObject("surface");
        shape.put("columns", allHeights.size());
        if (!allHeights.isEmpty()) {
            shape.put("minY", allHeights.get(0)).put("maxY", allHeights.get(allHeights.size() - 1));
            shape.put("medianY", percentile(allHeights, 0.5)).put("p90Y", percentile(allHeights, 0.9));
            shape.put("meanY", (double) sum / allHeights.size());
        }
        shape.put("maxChunkRelief", maxChunkRelief).put("maxAdjacentGradient", maxAdjacentGradient);
        return new WorldgenInspection(world, seed, terrainHash, chunkCoordinates, result);
    }

    private static int percentile(List<Integer> values, double percentile) {
        if (values.isEmpty()) return 0;
        return values.get((int) Math.min(values.size() - 1, Math.round((values.size() - 1) * percentile)));
    }

    private static int[] decodeHeightmap(long[] packed, int minY) {
        if (packed.length == 0) return new int[0];
        int bits = 9;
        int valuesPerLong = 64 / bits;
        if (packed.length * valuesPerLong < 256) return new int[0];
        int[] result = new int[256];
        long mask = (1L << bits) - 1L;
        for (int index = 0; index < result.length; index++) {
            int packedIndex = index / valuesPerLong;
            int bitIndex = (index % valuesPerLong) * bits;
            result[index] = (int) ((packed[packedIndex] >>> bitIndex) & mask) + minY - 1;
        }
        return result;
    }

    private static void updateDigest(MessageDigest digest, String... values) {
        for (String value : values) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            digest.update((byte) (bytes.length >>> 24));
            digest.update((byte) (bytes.length >>> 16));
            digest.update((byte) (bytes.length >>> 8));
            digest.update((byte) bytes.length);
            digest.update(bytes);
        }
    }

    private static void updateLongArrayDigest(MessageDigest digest, long[] values) {
        updateDigest(digest, "longs", Integer.toString(values.length));
        for (long value : values) {
            for (int shift = 56; shift >= 0; shift -= 8) digest.update((byte) (value >>> shift));
        }
    }

    private static String canonicalNbt(Object value) {
        if (value instanceof Map<?, ?>) {
            Map<?, ?> map = (Map<?, ?>) value;
            return map.entrySet().stream().sorted(Comparator.comparing(entry -> String.valueOf(entry.getKey())))
                .map(entry -> String.valueOf(entry.getKey()) + ":" + canonicalNbt(entry.getValue()))
                .collect(Collectors.joining(",", "{", "}"));
        }
        if (value instanceof List<?>) return ((List<?>) value).stream().map(Play::canonicalNbt).collect(Collectors.joining(",", "[", "]"));
        if (value instanceof long[]) return java.util.Arrays.toString((long[]) value);
        if (value instanceof int[]) return java.util.Arrays.toString((int[]) value);
        if (value instanceof byte[]) return HexFormat.of().formatHex((byte[]) value);
        return String.valueOf(value);
    }

    private static Object readNbt(InputStream stream) throws IOException {
        try (DataInputStream input = new DataInputStream(stream)) {
            int type = input.readUnsignedByte();
            require(type == 10, "NBT root must be a compound.");
            readNbtString(input);
            return readNbtPayload(input, type, 0);
        }
    }

    private static Object readNbtPayload(DataInputStream input, int type, int depth) throws IOException {
        require(depth <= 64, "NBT nesting exceeds 64 levels.");
        return switch (type) {
            case 0 -> null;
            case 1 -> input.readByte();
            case 2 -> input.readShort();
            case 3 -> input.readInt();
            case 4 -> input.readLong();
            case 5 -> input.readFloat();
            case 6 -> input.readDouble();
            case 7 -> {
                int length = boundedNbtLength(input.readInt(), 64 * 1024 * 1024);
                byte[] values = new byte[length]; input.readFully(values); yield values;
            }
            case 8 -> readNbtString(input);
            case 9 -> {
                int elementType = input.readUnsignedByte();
                int length = boundedNbtLength(input.readInt(), 16 * 1024 * 1024);
                List<Object> values = new ArrayList<>(Math.min(length, 1_000_000));
                for (int index = 0; index < length; index++) values.add(readNbtPayload(input, elementType, depth + 1));
                yield values;
            }
            case 10 -> {
                Map<String, Object> values = new LinkedHashMap<>();
                while (true) {
                    int childType = input.readUnsignedByte();
                    if (childType == 0) break;
                    String name = readNbtString(input);
                    values.put(name, readNbtPayload(input, childType, depth + 1));
                }
                yield values;
            }
            case 11 -> {
                int length = boundedNbtLength(input.readInt(), 16 * 1024 * 1024);
                int[] values = new int[length];
                for (int index = 0; index < length; index++) values[index] = input.readInt();
                yield values;
            }
            case 12 -> {
                int length = boundedNbtLength(input.readInt(), 8 * 1024 * 1024);
                long[] values = new long[length];
                for (int index = 0; index < length; index++) values[index] = input.readLong();
                yield values;
            }
            default -> throw new IOException("Unknown NBT tag type " + type);
        };
    }

    private static int boundedNbtLength(int value, int maximum) throws IOException {
        if (value < 0 || value > maximum) throw new IOException("Invalid NBT collection length " + value);
        return value;
    }

    private static String readNbtString(DataInputStream input) throws IOException {
        int length = input.readUnsignedShort();
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> rootCompound(Object value) {
        require(value instanceof Map<?, ?>, "NBT value is not a compound.");
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> compound(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : new LinkedHashMap<>();
    }

    private static List<Object> list(Object value) {
        return value instanceof List<?> ? new ArrayList<>((List<?>) value) : new ArrayList<>();
    }

    private static List<Map<String, Object>> compoundList(Object value) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (value instanceof List<?>) for (Object item : (List<?>) value) if (item instanceof Map<?, ?>) result.add(compound(item));
        return result;
    }

    private static long[] longArray(Object value) {
        return value instanceof long[] ? (long[]) value : new long[0];
    }

    private static long number(Object value, long fallback) {
        return value instanceof Number ? ((Number) value).longValue() : fallback;
    }

    private void parsePackOptions(List<String> arguments) {
        while (!arguments.isEmpty()) {
            String option = arguments.remove(0);
            if (option.equals("--help") || option.equals("-h")) {
                usage();
                System.exit(0);
            } else if (option.equals("--modpack")) {
                require(!arguments.isEmpty(), "--modpack requires a name.");
                modpackName = arguments.remove(0);
            } else if (option.startsWith("--modpack=")) {
                modpackName = option.substring("--modpack=".length());
            } else if (option.equals("--trajectory")) {
                require(!arguments.isEmpty(), "--trajectory requires a name.");
                trajectory = arguments.remove(0);
            } else if (option.startsWith("--trajectory=")) {
                trajectory = option.substring("--trajectory=".length());
            } else if (option.equals("--json")) {
                jsonOutput = true;
            } else if (option.equals("--canary")) {
                canaryEnabled = true;
            } else if (option.equals("--local-world")) {
                localWorld = true;
            } else if (option.equals("--debug-gpu-memory-leaks")) {
                debugGpuMemoryLeaks = true;
            } else if (option.equals("--world-seed")) {
                require(!arguments.isEmpty(), "--world-seed requires a signed integer.");
                worldSeed = parseLong(arguments.remove(0), "--world-seed");
            } else if (option.startsWith("--world-seed=")) {
                worldSeed = parseLong(option.substring("--world-seed=".length()), "--world-seed");
            } else if (option.equals("--world-generator")) {
                require(!arguments.isEmpty(), "--world-generator requires flat, debug, void, or tech_reborn.");
                worldGenerator = normalizeWorldGenerator(arguments.remove(0));
            } else if (option.startsWith("--world-generator=")) {
                worldGenerator = normalizeWorldGenerator(option.substring("--world-generator=".length()));
            } else {
                throw failure("Unknown option: " + option);
            }
        }
    }

    private String normalizeWorldGenerator(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        require(normalized.isEmpty() || Set.of("flat", "debug", "void", "tech_reborn").contains(normalized),
            "World generator must be flat, debug, void, or tech_reborn.");
        return normalized;
    }

    private String selectedWorldGenerator() {
        if (!worldGenerator.isEmpty()) return worldGenerator;
        return modpackName.equals("tech-reborn") ? "tech_reborn" : "flat";
    }

    private void parseTrajectoryOption(List<String> arguments) {
        while (!arguments.isEmpty()) {
            String option = arguments.remove(0);
            if (option.equals("--trajectory")) {
                require(!arguments.isEmpty(), "--trajectory requires a name.");
                trajectory = arguments.remove(0);
            } else if (option.startsWith("--trajectory=")) {
                trajectory = option.substring("--trajectory=".length());
            } else {
                throw failure("Unknown modpack option: " + option);
            }
        }
    }

    private void startServer() throws Exception {
        Optional<Long> managed = managedPid(serverPidFile, this::isServerCommand);
        if (managed.isPresent()) {
            System.out.printf("Minecraft server is already running (PID %d).%n", managed.get());
            PredicateObservation ready = waitForPredicate("server.game-ready", Duration.ofMinutes(3), managed.get());
            require(ready.matched, "Managed Minecraft server did not become game-ready within 3 minutes.");
            return;
        }
        if (serverPortIsOpen()) {
            System.out.printf("A server outside this launcher is already reachable at %s; leaving it untouched.%n", serverAddress);
            return;
        }
        require(Set.of("fabric", "vanilla").contains(serverFlavor), "MINECRAFT_SERVER_FLAVOR must be fabric or vanilla.");
        Path launchJar;
        if (serverFlavor.equals("fabric")) {
            launchJar = prepareFabricServer();
        } else {
            require(Files.isRegularFile(serverJar), "Server JAR not found at " + serverJar + ". Place a Minecraft 1.20.4-compatible server there or set MINECRAFT_SERVER_JAR.");
            String jarVersion = serverJarVersion();
            require(jarVersion == null || jarVersion.equals(minecraftVersion), "Server JAR is Minecraft " + jarVersion + ", but Minosoft is configured for " + minecraftVersion + ".");
            launchJar = serverJar;
        }
        Files.createDirectories(serverDirectory);
        Files.createDirectories(runDirectory);
        acceptEula();

        System.out.printf("Starting Minecraft server on port %d (log: %s)...%n", serverPort, serverLog);
        List<String> command = List.of(javaBin.toString(), "-Xms1G", "-Xmx" + serverMemory, "-jar", launchJar.toString(), "nogui", "--port", Integer.toString(serverPort));
        Process process = loggedChild(command, serverDirectory, serverLog);
        supervisedServer = process;
        long pid = process.pid();
        Files.writeString(serverPidFile, pid + System.lineSeparator(), StandardCharsets.UTF_8);
        boolean portReported = false;
        boolean debugReported = false;
        for (int attempt = 0; attempt < 180; attempt++) {
            if (!portReported && serverPortIsOpen()) {
                portReported = true;
                System.out.printf("Minecraft server port is open (PID %d); waiting for game readiness...%n", pid);
                emitEvent("server_port_open", "serverPid", Long.toString(pid));
            }
            if (!debugReported && debugStatus(DebugEndpointRole.SERVER, pid).isPresent()) {
                debugReported = true;
                emitEvent("server_debug_ready", "serverPid", Long.toString(pid));
            }
            if (serverGameIsReady(pid)) {
                System.out.printf("Minecraft server is game-ready (PID %d).%n", pid);
                emitEvent("server_game_ready", "serverPid", Long.toString(pid));
                emitEvent("server_ready", "serverPid", Long.toString(pid), "semantic", "game_ready");
                return;
            }
            if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) == false) {
                Files.deleteIfExists(serverPidFile);
                printTail(serverLog, 40);
                throw failure("Minecraft server exited before it became ready.");
            }
            Thread.sleep(1_000);
        }
        throw failure("Timed out waiting for the Minecraft server to become game-ready at " + serverAddress + ". See " + serverLog);
    }

    private Path prepareFabricServer() throws Exception {
        require(minecraftVersion.equals("1.20.4"), "The owned Fabric server bridge is pinned to Minecraft 1.20.4.");
        Files.createDirectories(serverDirectory);
        if (!Files.isRegularFile(fabricServerJar)) {
            Path partial = serverDirectory.resolve("." + fabricServerJar.getFileName() + ".part." + ProcessHandle.current().pid());
            URI launcher = URI.create("https://meta.fabricmc.net/v2/versions/loader/1.20.4/0.15.11/1.0.1/server/jar");
            System.out.println("Downloading pinned Fabric 1.20.4 server launcher...");
            download(launcher, partial);
            verifyFabricServerLauncher(partial);
            try {
                Files.move(partial, fabricServerJar, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException error) {
                Files.move(partial, fabricServerJar);
            }
        }
        verifyFabricServerLauncher(fabricServerJar);

        runInherited(List.of(project.resolve(isWindows() ? "gradlew.bat" : "gradlew").toString(), "--quiet", ":debug-server-fabric:remapJar"), project);
        Path bridge = project.resolve("debug-server-fabric/build/libs/minosoft-debug-bridge-fabric-1.20.4-0.1.0.jar");
        require(Files.isRegularFile(bridge), "Fabric debug bridge build did not produce " + bridge + ".");
        PreparedPack support = prepareModpack(serverModpackName, "server-debug");
        Path supportPack = modpacksDirectory.resolve(serverModpackName);
        List<Path> serverSupport = new ArrayList<>();
        try (var metadata = Files.list(supportPack.resolve("mods"))) {
            for (Path metadataFile : metadata.filter(path -> path.getFileName().toString().endsWith(".pw.toml")).sorted().collect(Collectors.toList())) {
                ResolvedArtifact artifact = resolveArtifact(metadataFile, "server mod", Set.of("both", "server"));
                if (artifact != null) serverSupport.add(artifact.artifact);
            }
        }
        require(serverSupport.stream().anyMatch(path -> path.getFileName().toString().startsWith("fabric-api-")),
            "The selected Fabric server support pack contains no server-side Fabric API artifact.");

        Files.createDirectories(serverModsDirectory);
        if (Files.isRegularFile(serverManagedModsFile)) {
            for (String filename : Files.readAllLines(serverManagedModsFile, StandardCharsets.UTF_8)) {
                require(SAFE_MANAGED_FILENAME.matcher(filename).matches(), "Unsafe managed server mod filename: " + filename);
                Files.deleteIfExists(serverModsDirectory.resolve(filename));
            }
        }
        List<Path> managed = new ArrayList<>();
        managed.add(bridge);
        managed.addAll(serverSupport);
        for (Path artifact : managed) {
            Files.copy(artifact, serverModsDirectory.resolve(artifact.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        }
        Files.write(
            serverManagedModsFile,
            managed.stream().map(path -> path.getFileName().toString()).toList(),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
        );
        System.out.printf("Prepared Fabric server with debug bridge and %d managed support mod(s).%n", serverSupport.size());
        return fabricServerJar;
    }

    private void verifyFabricServerLauncher(Path launcher) throws IOException {
        try (JarFile jar = new JarFile(launcher.toFile())) {
            require(jar.getJarEntry("net/fabricmc/installer/ServerLauncher.class") != null,
                "Downloaded server launcher is not the expected Fabric server launcher: " + launcher);
        }
    }

    private void acceptEula() throws IOException {
        Path eula = serverDirectory.resolve("eula.txt");
        boolean accepted = Files.isRegularFile(eula) && Files.readAllLines(eula).stream().anyMatch(line -> line.trim().matches("eula\\s*=\\s*true"));
        if (accepted) return;
        if (!environment.getOrDefault("MINECRAFT_EULA_ACCEPTED", "false").equals("true")) {
            Console console = System.console();
            require(console != null, "Minecraft's EULA has not been accepted. Read https://aka.ms/MinecraftEULA and set MINECRAFT_EULA_ACCEPTED=true for non-interactive start.");
            String reply = console.readLine("Minecraft requires accepting https://aka.ms/MinecraftEULA%nDo you accept the Minecraft EULA? [y/N] ");
            require(reply != null && Set.of("y", "yes").contains(reply.trim().toLowerCase(Locale.ROOT)), "Minecraft's EULA was not accepted.");
        }
        Files.writeString(eula, "eula=true" + System.lineSeparator(), StandardCharsets.UTF_8);
        System.out.println("Minecraft EULA acceptance saved to " + eula + ".");
    }

    private void stopServer() throws Exception {
        stopManagedProcess(serverPidFile, this::isServerCommand, "Minecraft server");
    }

    private void startParent(String target) throws Exception {
        require(managedPid(supervisorPidFile, this::isPlayParentCommand).isEmpty(), "A Java play parent is already running.");
        beginParentSession("start", target);

        try {
            boolean serverWasReady = serverPortIsOpen();
            if (target.equals("server") || target.equals("both")) {
                startServer();
                supervisorOwnsServer = !serverWasReady && supervisedServer != null;
            }
            if (target.equals("client") || target.equals("both")) {
                if (!localWorld) require(serverGameIsReady(null), "No game-ready server is reachable at " + serverAddress + ". Start it first with: ./play.sh start server");
                require(externalClientPids().isEmpty(), "Minosoft is already running outside this launcher.");
                System.out.println("Preparing Minosoft...");
                installDistribution();
                PreparedPack pack = modpackName.isBlank() ? null : prepareModpack(modpackName, trajectory);
                PreparedCanary canary = canaryEnabled ? prepareCanary() : null;
                supervisedClient = launchSupervisedClient(pack, canary);
                System.out.printf("Java play parent is active (PID %d, client PID %d). Press Ctrl-C to stop.%n", ProcessHandle.current().pid(), supervisedClient.pid());
                supervisedClient.waitFor();
            } else if (supervisedServer != null) {
                System.out.printf("Java play parent is active (PID %d, server PID %d). Press Ctrl-C to stop.%n", ProcessHandle.current().pid(), supervisedServer.pid());
                supervisedServer.waitFor();
            }
        } finally {
            cleanupSupervisor();
        }
    }

    private List<String> clientCommand(PreparedPack pack, PreparedCanary canary) {
        Path launcher = project.resolve(isWindows() ? "build/install/minosoft/bin/minosoft.bat" : "build/install/minosoft/bin/minosoft");
        List<String> command = new ArrayList<>();
        command.add(launcher.toString());
        command.add("--no-eros");
        command.add(localWorld ? "--local" : "--connect");
        if (localWorld) {
            command.add("--world-generator=" + selectedWorldGenerator());
            command.add("--world-seed=" + worldSeed);
        } else {
            command.add("--address=" + serverAddress);
        }
        command.add("--protocol-version=" + minecraftVersion);
        String account = environment.get("MINOSOFT_ACCOUNT");
        if (account != null && !account.isBlank()) command.add("--account=" + account);
        command.add("--mod-trajectory=" + trajectory);
        command.add("--hot-reload-generation=" + clientGeneration);
        if (debugGpuMemoryLeaks) command.add("--debug-gpu-memory-leaks");
        if (pack != null) {
            command.add("--fabric-pack=" + pack.view);
            command.add("--home=" + pack.instance.resolve("home"));
            command.add("--profiles=" + pack.instance.resolve("profiles"));
            command.add("--assets=" + pack.assets);
        }
        if (canary != null) command.add("--mod-source=pre=archive:" + canary.artifact.toUri().getRawPath());
        return command;
    }

    private Process launchSupervisedClient(PreparedPack pack, PreparedCanary canary) throws Exception {
        if (pack != null) materializeAssetProfile(pack);
        if (localWorld) {
            System.out.printf("Starting Minosoft with regenerated %s local world seed %d (log: %s)...%n", selectedWorldGenerator(), worldSeed, clientLog);
        } else {
            System.out.printf("Starting Minosoft and connecting to %s (log: %s)...%n", serverAddress, clientLog);
        }
        Map<String, String> childEnvironment = new HashMap<>();
        if (
            pack != null &&
            pack.shaderPack != null &&
            environment.getOrDefault("MINOSOFT_SHADER_PACK", "").isBlank()
        ) {
            childEnvironment.put("MINOSOFT_SHADER_PACK", pack.shaderPack.toString());
        }
        if (
            pack != null &&
            pack.shaderOptions != null &&
            !pack.shaderOptions.isBlank() &&
            environment.getOrDefault("MINOSOFT_SHADER_OPTIONS", "").isBlank()
        ) {
            childEnvironment.put("MINOSOFT_SHADER_OPTIONS", pack.shaderOptions);
        }
        Process process = loggedChild(clientCommand(pack, canary), project, clientLog, childEnvironment);
        Files.writeString(clientPidFile, process.pid() + System.lineSeparator(), StandardCharsets.UTF_8);
        Thread.sleep(2_000);
        if (!process.isAlive()) {
            Files.deleteIfExists(clientPidFile);
            printTail(clientLog, 40);
            throw failure("Minosoft exited during startup.");
        }
        emitEvent("client_started", "clientPid", Long.toString(process.pid()));
        PredicateObservation debugReady = waitForPredicate("client.debug-ready", Duration.ofSeconds(120), process.pid());
        if (!debugReady.matched) {
            process.destroy();
            throw failure("Minosoft did not publish its debug endpoint within 120 seconds.");
        }
        emitEvent("client_debug_ready", "clientPid", Long.toString(process.pid()));
        return process;
    }

    private void materializeAssetProfile(PreparedPack pack) throws Exception {
        Path profile = pack.instance.resolve("profiles/minosoft/resources/Default.json");
        ObjectNode root;
        if (Files.isRegularFile(profile)) {
            JsonNode parsed = DebugJson.MAPPER.readTree(profile.toFile());
            require(parsed instanceof ObjectNode, "Resources profile is not a JSON object: " + profile);
            root = (ObjectNode) parsed;
        } else {
            root = DebugJson.MAPPER.createObjectNode();
            root.put("version", 2);
        }

        JsonNode existingAssets = root.get("assets");
        ObjectNode assets;
        if (existingAssets instanceof ObjectNode) {
            assets = (ObjectNode) existingAssets;
        } else {
            assets = root.putObject("assets");
        }
        ArrayNode preserved = DebugJson.MAPPER.createArrayNode();
        JsonNode existingPacks = assets.get("resource_packs");
        if (existingPacks != null && existingPacks.isArray()) {
            for (JsonNode existing : existingPacks) {
                String path = existing.path("path").asText("");
                if (path.isBlank() || !isLauncherManagedPack(path)) {
                    preserved.add(existing.deepCopy());
                }
            }
        }

        ArrayNode configured = assets.putArray("resource_packs");
        for (Path resourcePack : pack.resourcePacks) {
            ObjectNode entry = configured.addObject();
            entry.put("type", "ZIP");
            entry.put("path", resourcePack.toAbsolutePath().normalize().toString());
        }
        for (PreparedContentFixture fixture : pack.contentFixtures) {
            ObjectNode entry = configured.addObject();
            entry.put("type", "DIRECTORY");
            entry.put("path", fixture.resources.toAbsolutePath().normalize().toString());
        }
        configured.addAll(preserved);

        ArrayNode preservedData = DebugJson.MAPPER.createArrayNode();
        JsonNode existingDataPacks = assets.get("data_packs");
        if (existingDataPacks != null && existingDataPacks.isArray()) {
            for (JsonNode existing : existingDataPacks) {
                String path = existing.path("path").asText("");
                if (path.isBlank() || !isLauncherManagedPack(path)) {
                    preservedData.add(existing.deepCopy());
                }
            }
        }
        ArrayNode configuredData = assets.putArray("data_packs");
        for (PreparedContentFixture fixture : pack.contentFixtures) {
            ObjectNode entry = configuredData.addObject();
            entry.put("type", "DIRECTORY");
            entry.put("path", fixture.dataPacks.toAbsolutePath().normalize().toString());
        }
        configuredData.addAll(preservedData);

        Files.createDirectories(profile.getParent());
        Path candidate = profile.resolveSibling("." + profile.getFileName() + ".resourcepacks." + ProcessHandle.current().pid());
        DebugJson.MAPPER.writerWithDefaultPrettyPrinter().writeValue(candidate.toFile(), root);
        try {
            Files.move(candidate, profile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException error) {
            Files.move(candidate, profile, StandardCopyOption.REPLACE_EXISTING);
        }
        System.out.println(
            "Configured " + (pack.resourcePacks.size() + pack.contentFixtures.size())
                + " managed resource pack(s) and " + pack.contentFixtures.size()
                + " managed data pack(s) in " + profile + "."
        );
    }

    private boolean isLauncherManagedPack(String value) {
        try {
            return Path.of(value).toAbsolutePath().normalize().startsWith(modpackStore.toAbsolutePath().normalize());
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private void stopClient() throws Exception {
        stopManagedProcess(clientPidFile, command -> command.contains(MINOSOFT_MAIN), "Minosoft client");
    }

    private void supervise(String target) throws Exception {
        require(managedPid(supervisorPidFile, this::isPlayParentCommand).isEmpty(), "A play hot-reload supervisor is already running.");
        require(externalClientPids().isEmpty(), "Minosoft is already running outside this launcher; stop it before starting the hot-reload supervisor.");

        beginParentSession("dev", target);

        boolean serverWasReady = serverPortIsOpen();
        if (target.equals("both")) {
            startServer();
            supervisorOwnsServer = !serverWasReady && supervisedServer != null;
            if (!localWorld) require(serverGameIsReady(supervisedServer == null ? null : supervisedServer.pid()),
                "Minecraft server did not reach game-ready state.");
        } else if (!localWorld) {
            require(serverGameIsReady(null), "No game-ready server is reachable at " + serverAddress + ". Use './play.sh' to supervise both, or start a server first.");
        }

        System.out.println("Building initial client generation...");
        installDistribution();
        PreparedPack pack = modpackName.isBlank() ? null : prepareModpack(modpackName, trajectory);
        PreparedCanary canary = canaryEnabled ? prepareCanary() : null;
        supervisedClient = launchSupervisedClient(pack, canary);
        System.out.printf("Hot-reload supervisor is active (PID %d, client PID %d).%n", ProcessHandle.current().pid(), supervisedClient.pid());
        System.out.println("Watching client and shared debug sources, build configuration, pack manifests, configured external mod paths" + (canaryEnabled ? ", and the canary mod" : "") + ". Press Ctrl-C to stop.");

        try (WatchService watcher = project.getFileSystem().newWatchService()) {
            Map<WatchKey, Path> watchedDirectories = new HashMap<>();
            registerWatchTree(project.resolve("src/main"), watcher, watchedDirectories);
            registerWatchTree(project.resolve("debug-core/src/main"), watcher, watchedDirectories);
            registerWatchDirectory(project, watcher, watchedDirectories);
            if (!modpackName.isBlank()) registerWatchTree(modpacksDirectory.resolve(modpackName), watcher, watchedDirectories);
            if (canaryEnabled) registerWatchTree(canarySourceDirectory, watcher, watchedDirectories);
            for (Path external : externalWatchPaths()) registerWatchTree(external, watcher, watchedDirectories);

            while (true) {
                if (supervisorCleanup.get()) return;
                if (supervisedClient == null || !supervisedClient.isAlive()) {
                    if (supervisorCleanup.get()) return;
                    printTail(clientLog, 40);
                    throw failure("The supervised Minosoft client exited. Fix the reported issue and relaunch ./play.sh.");
                }
                WatchKey first = watcher.poll(1, TimeUnit.SECONDS);
                if (first == null) continue;
                Set<Path> changes = new HashSet<>();
                collectWatchEvents(first, watcher, watchedDirectories, changes);
                Thread.sleep(350);
                WatchKey additional;
                while ((additional = watcher.poll()) != null) collectWatchEvents(additional, watcher, watchedDirectories, changes);
                if (changes.stream().noneMatch(this::isReloadChange)) continue;

                String reloadKind = isCanaryOnlyChange(changes) ? "canary" : "base";
                System.out.printf("%s change detected; building candidate client generation...%n", reloadKind.equals("canary") ? "Canary mod" : "Base-game");
                emitEvent("candidate_detected", "changes", Integer.toString(changes.size()), "reloadKind", reloadKind);
                try {
                    if (reloadKind.equals("base")) installDistribution();
                    PreparedPack candidate = modpackName.isBlank() ? null : prepareModpack(modpackName, trajectory);
                    PreparedCanary candidateCanary = canaryEnabled ? prepareCanary() : null;
                    emitEvent("candidate_ready", "reloadKind", reloadKind, "canaryHash", candidateCanary == null ? "" : candidateCanary.hash);
                    if (!stopSupervisedClient()) {
                        System.err.println("Candidate is ready, but the active client did not stop cleanly; keeping the current process boundary.");
                        emitEvent("candidate_swap_blocked", "reason", "active client did not stop cleanly");
                        continue;
                    }
                    clientGeneration++;
                    supervisedClient = launchSupervisedClient(candidate, candidateCanary);
                    System.out.printf("Activated new client generation (PID %d).%n", supervisedClient.pid());
                    emitEvent("candidate_activated", "clientPid", Long.toString(supervisedClient.pid()), "reloadKind", reloadKind, "canaryHash", candidateCanary == null ? "" : candidateCanary.hash);
                } catch (Exception error) {
                    System.err.println("Candidate reload failed; the active generation was left running when possible: " + error.getMessage());
                    emitEvent("candidate_failed", "reason", String.valueOf(error.getMessage()), "reloadKind", reloadKind);
                }
            }
        } finally {
            cleanupSupervisor();
        }
    }

    private List<Path> externalWatchPaths() {
        String configured = environment.get("MINOSOFT_HOT_RELOAD_PATHS");
        if (configured == null || configured.isBlank()) return List.of();
        List<Path> paths = new ArrayList<>();
        for (String value : configured.split(Pattern.quote(System.getProperty("path.separator")))) {
            if (!value.isBlank()) paths.add(resolveProjectPath(value));
        }
        return paths;
    }

    private void registerWatchTree(Path root, WatchService watcher, Map<WatchKey, Path> watchedDirectories) throws IOException {
        if (!Files.isDirectory(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path directory : paths.filter(Files::isDirectory).collect(Collectors.toList())) {
                registerWatchDirectory(directory, watcher, watchedDirectories);
            }
        }
    }

    private void registerWatchDirectory(Path directory, WatchService watcher, Map<WatchKey, Path> watchedDirectories) throws IOException {
        if (!Files.isDirectory(directory) || watchedDirectories.containsValue(directory)) return;
        WatchKey key = directory.register(watcher, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);
        watchedDirectories.put(key, directory);
    }

    private void collectWatchEvents(WatchKey key, WatchService watcher, Map<WatchKey, Path> watchedDirectories, Set<Path> changes) throws IOException {
        Path directory = watchedDirectories.get(key);
        if (directory == null) {
            key.reset();
            return;
        }
        for (WatchEvent<?> event : key.pollEvents()) {
            if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                changes.add(project.resolve("src/main"));
                continue;
            }
            Path changed = directory.resolve((Path) event.context()).toAbsolutePath().normalize();
            changes.add(changed);
            if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(changed)) {
                registerWatchTree(changed, watcher, watchedDirectories);
            }
        }
        if (!key.reset()) watchedDirectories.remove(key);
    }

    private boolean isReloadChange(Path changed) {
        Path source = project.resolve("src/main").toAbsolutePath().normalize();
        if (changed.startsWith(source)) return true;
        Path debugCoreSource = project.resolve("debug-core/src/main").toAbsolutePath().normalize();
        if (changed.startsWith(debugCoreSource)) return true;
        if (canaryEnabled && changed.startsWith(canarySourceDirectory)) return true;
        if (!modpackName.isBlank() && changed.startsWith(modpacksDirectory.resolve(modpackName).toAbsolutePath().normalize())) return true;
        for (Path external : externalWatchPaths()) {
            if (changed.startsWith(external)) return true;
        }
        if (changed.getParent() != null && changed.getParent().equals(project)) {
            return Set.of("build.gradle.kts", "settings.gradle.kts", "settings.gradle", "gradle.properties").contains(changed.getFileName().toString());
        }
        return false;
    }

    private boolean isCanaryOnlyChange(Set<Path> changes) {
        if (!canaryEnabled || changes.isEmpty()) return false;
        return changes.stream().filter(this::isReloadChange).allMatch(path -> path.startsWith(canarySourceDirectory));
    }

    private boolean stopSupervisedClient() throws Exception {
        Process process = supervisedClient;
        if (process == null || !process.isAlive()) {
            Files.deleteIfExists(clientPidFile);
            return true;
        }
        process.destroy();
        boolean stopped = process.waitFor(20, TimeUnit.SECONDS);
        if (stopped) Files.deleteIfExists(clientPidFile);
        return stopped;
    }

    private boolean stopSupervisor() throws Exception {
        Optional<Long> pid = managedPid(supervisorPidFile, this::isPlayParentCommand);
        if (pid.isEmpty()) return false;
        ProcessHandle process = ProcessHandle.of(pid.get()).orElseThrow();
        System.out.printf("Stopping hot-reload supervisor (PID %d)...%n", pid.get());
        process.destroy();
        for (int attempt = 0; attempt < 30 && process.isAlive(); attempt++) Thread.sleep(1_000);
        require(!process.isAlive(), "Hot-reload supervisor did not stop within 30 seconds; it was not force-killed.");
        Files.deleteIfExists(supervisorPidFile);
        System.out.println("Hot-reload supervisor stopped.");
        return true;
    }

    private void beginParentSession(String mode, String target) throws IOException {
        Files.createDirectories(runDirectory);
        sessionId = Instant.now() + "-" + ProcessHandle.current().pid();
        Files.writeString(supervisorPidFile, ProcessHandle.current().pid() + System.lineSeparator(), StandardCharsets.UTF_8);
        Runtime.getRuntime().addShutdownHook(new Thread(this::cleanupSupervisor, "minosoft-play-shutdown"));
        emitEvent("parent_started", "mode", mode, "target", target, "parentPid", Long.toString(ProcessHandle.current().pid()));
    }

    private synchronized void emitEvent(String event, String... fields) {
        if (sessionId == null) return;
        StringBuilder json = new StringBuilder();
        json.append("{\"timestamp\":\"").append(jsonEscape(Instant.now().toString())).append("\"");
        json.append(",\"session\":\"").append(jsonEscape(sessionId)).append("\"");
        json.append(",\"event\":\"").append(jsonEscape(event)).append("\"");
        for (int index = 0; index + 1 < fields.length; index += 2) {
            json.append(",\"").append(jsonEscape(fields[index])).append("\":\"").append(jsonEscape(fields[index + 1])).append("\"");
        }
        json.append('}').append(System.lineSeparator());
        try {
            Files.createDirectories(runDirectory);
            Files.writeString(eventLog, json, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException error) {
            System.err.println("Warning: could not append play lifecycle evidence: " + error.getMessage());
        }
    }

    private void cleanupSupervisor() {
        if (!supervisorCleanup.compareAndSet(false, true)) return;
        emitEvent("parent_stopping");
        try {
            stopSupervisedClient();
        } catch (Exception error) {
            System.err.println("Could not stop supervised client cleanly: " + error.getMessage());
        }
        if (supervisorOwnsServer && supervisedServer != null && supervisedServer.isAlive()) {
            try {
                stopServer();
            } catch (Exception error) {
                System.err.println("Could not stop supervised server cleanly: " + error.getMessage());
            }
        }
        try {
            Files.deleteIfExists(supervisorPidFile);
        } catch (IOException ignored) {
        }
        emitEvent("parent_stopped");
    }

    private String jsonEscape(String value) {
        if (value == null) return "null";
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private void stopManagedProcess(Path pidFile, Predicate<String> commandMatcher, String label) throws Exception {
        Optional<Long> pid = managedPid(pidFile, commandMatcher);
        if (pid.isEmpty()) {
            System.out.println(label + " is not running under this launcher.");
            return;
        }
        ProcessHandle process = ProcessHandle.of(pid.get()).orElseThrow();
        System.out.printf("Stopping %s (PID %d)...%n", label, pid.get());
        process.destroy();
        for (int attempt = 0; attempt < 30 && process.isAlive(); attempt++) Thread.sleep(1_000);
        require(!process.isAlive(), label + " did not stop within 30 seconds; it was not force-killed.");
        Files.deleteIfExists(pidFile);
        System.out.println(label + " stopped.");
    }

    private void serverStatus() throws IOException {
        Optional<Long> managed = managedPid(serverPidFile, this::isServerCommand);
        if (managed.isPresent()) {
            System.out.printf("Server: running (PID %d, %s)%n", managed.get(), serverAddress);
        } else if (serverPortIsOpen()) {
            System.out.println("Server: reachable but not managed by this launcher (" + serverAddress + ")");
        } else {
            System.out.println("Server: stopped");
        }
    }

    private void clientStatus() throws IOException {
        Optional<Long> supervisor = managedPid(supervisorPidFile, this::isPlayParentCommand);
        Optional<Long> managed = managedPid(clientPidFile, command -> command.contains(MINOSOFT_MAIN));
        if (managed.isPresent()) {
            System.out.printf("Client: running (PID %d)%n", managed.get());
            supervisor.ifPresent(pid -> System.out.printf("Java play parent: PID %d%n", pid));
            return;
        }
        List<Long> external = externalClientPids();
        if (external.isEmpty()) System.out.println("Client: stopped");
        else System.out.println("Client: running outside this launcher (PID(s): " + joinPids(external) + ")");
        supervisor.ifPresent(pid -> System.out.printf("Java play parent: PID %d%n", pid));
    }

    private void statusJson(String target) throws IOException {
        System.out.println(DebugJson.MAPPER.writeValueAsString(buildStatusJson(target)));
    }

    private ObjectNode buildStatusJson(String target) throws IOException {
        Optional<Long> parent = managedPid(supervisorPidFile, this::isPlayParentCommand);
        Optional<Long> server = managedPid(serverPidFile, this::isServerCommand);
        Optional<Long> client = managedPid(clientPidFile, command -> command.contains(MINOSOFT_MAIN));
        List<Long> externalClients = externalClientPids().stream().filter(pid -> client.isEmpty() || pid.longValue() != client.get().longValue()).collect(Collectors.toList());
        boolean serverPortOpen = serverPortIsOpen();
        Optional<JsonNode> serverDebug = debugStatus(DebugEndpointRole.SERVER, server.orElse(null));
        Optional<JsonNode> clientDebug = debugStatus(DebugEndpointRole.CLIENT, client.orElse(null));
        boolean serverGameReady = serverDebug.map(value -> value.path("ready").asBoolean(false)).orElseGet(this::minecraftStatusIsReady);
        ObjectNode result = DebugJson.MAPPER.createObjectNode();
        result.put("target", target);
        if (parent.isPresent()) result.put("parentPid", parent.get()); else result.putNull("parentPid");
        if (server.isPresent()) result.put("serverPid", server.get()); else result.putNull("serverPid");
        if (client.isPresent()) result.put("clientPid", client.get()); else result.putNull("clientPid");
        result.put("serverPortOpen", serverPortOpen);
        result.put("serverDebugReady", serverDebug.isPresent());
        result.put("serverGameReady", serverGameReady);
        result.put("serverReady", serverGameReady);
        result.put("clientDebugReady", clientDebug.isPresent());
        result.put("clientJoined", clientDebug.map(value -> value.path("ready").asBoolean(false)).orElse(false));
        result.put("clientRenderReady", clientDebug.map(value -> value.path("renderReady").asBoolean(false)).orElse(false));
        ArrayNode external = result.putArray("externalClientPids");
        externalClients.forEach(external::add);
        return result;
    }

    private Optional<Long> managedPid(Path pidFile, Predicate<String> commandMatcher) throws IOException {
        if (!Files.isRegularFile(pidFile)) return Optional.empty();
        String value = Files.readString(pidFile).trim();
        long pid;
        try {
            pid = Long.parseLong(value);
        } catch (NumberFormatException error) {
            Files.deleteIfExists(pidFile);
            return Optional.empty();
        }
        Optional<ProcessHandle> process = ProcessHandle.of(pid);
        String command = process.flatMap(handle -> handle.info().commandLine()).orElse("");
        if (process.isPresent() && process.get().isAlive() && commandMatcher.test(command)) return Optional.of(pid);
        Files.deleteIfExists(pidFile);
        return Optional.empty();
    }

    private boolean isPlayParentCommand(String command) {
        return command.contains(project.resolve("util/play/Play.java").toString())
            || command.contains("play-util") || command.contains(" Play ") || command.endsWith(" Play");
    }

    private boolean isServerCommand(String command) {
        return command.contains(serverJar.toString()) || command.contains(fabricServerJar.toString());
    }

    private List<Long> externalClientPids() {
        return ProcessHandle.allProcesses()
            .filter(ProcessHandle::isAlive)
            .filter(process -> process.info().commandLine().orElse("").contains(MINOSOFT_MAIN))
            .map(ProcessHandle::pid)
            .sorted()
            .collect(Collectors.toList());
    }

    private boolean serverPortIsOpen() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(serverHost, serverPort), 300);
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    private boolean serverGameIsReady(Long expectedPid) {
        Optional<JsonNode> debug = debugStatus(DebugEndpointRole.SERVER, expectedPid);
        if (expectedPid != null && serverFlavor.equals("fabric")) {
            return debug.map(value -> value.path("ready").asBoolean(false)).orElse(false);
        }
        return debug.map(value -> value.path("ready").asBoolean(false)).orElseGet(this::minecraftStatusIsReady);
    }

    private Optional<JsonNode> debugStatus(DebugEndpointRole role, Long expectedPid) {
        try {
            DebugDiscovery discovery = new DebugDiscovery(DebugPaths.system());
            List<DebugEndpointDescriptor> endpoints = discovery.list(true).stream()
                .filter(endpoint -> endpoint.getRole() == role)
                .filter(endpoint -> expectedPid != null || endpoint.getTrajectory().equals(trajectory))
                .filter(endpoint -> expectedPid == null || endpoint.getPid() == expectedPid)
                .sorted(Comparator.comparingInt(DebugEndpointDescriptor::getGeneration).reversed()
                    .thenComparing(DebugEndpointDescriptor::getProcessStart, Comparator.reverseOrder()))
                .collect(Collectors.toList());
            for (DebugEndpointDescriptor endpoint : endpoints) {
                try (DebugClient client = DebugClient.connect(DebugPaths.system(), endpoint)) {
                    return Optional.of(client.request("core.status", DebugJson.MAPPER.createObjectNode(), 1_000));
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        return Optional.empty();
    }

    private boolean minecraftStatusIsReady() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(serverHost, serverPort), 500);
            socket.setSoTimeout(1_000);
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            byte[] host = serverHost.getBytes(StandardCharsets.UTF_8);
            java.io.ByteArrayOutputStream handshakeBytes = new java.io.ByteArrayOutputStream();
            DataOutputStream handshake = new DataOutputStream(handshakeBytes);
            writeVarInt(handshake, -1);
            writeVarInt(handshake, host.length);
            handshake.write(host);
            handshake.writeShort(serverPort);
            writeVarInt(handshake, 1);
            writePacket(output, 0, handshakeBytes.toByteArray());
            writePacket(output, 0, new byte[0]);

            DataInputStream input = new DataInputStream(socket.getInputStream());
            int packetLength = readVarInt(input);
            if (packetLength < 2 || packetLength > 1_048_576) return false;
            if (readVarInt(input) != 0) return false;
            int jsonLength = readVarInt(input);
            if (jsonLength < 2 || jsonLength > packetLength || jsonLength > 1_048_576) return false;
            byte[] response = input.readNBytes(jsonLength);
            return response.length == jsonLength && DebugJson.MAPPER.readTree(response).isObject();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void writePacket(DataOutputStream output, int packetId, byte[] body) throws IOException {
        java.io.ByteArrayOutputStream packetBytes = new java.io.ByteArrayOutputStream();
        DataOutputStream packet = new DataOutputStream(packetBytes);
        writeVarInt(packet, packetId);
        packet.write(body);
        writeVarInt(output, packetBytes.size());
        output.write(packetBytes.toByteArray());
        output.flush();
    }

    private static void writeVarInt(DataOutputStream output, int value) throws IOException {
        do {
            int next = value & 0x7F;
            value >>>= 7;
            if (value != 0) next |= 0x80;
            output.writeByte(next);
        } while (value != 0);
    }

    private static int readVarInt(DataInputStream input) throws IOException {
        int value = 0;
        int position = 0;
        while (position < 35) {
            byte current = input.readByte();
            value |= (current & 0x7F) << position;
            if ((current & 0x80) == 0) return value;
            position += 7;
        }
        throw new IOException("VarInt is too long");
    }

    private String serverJarVersion() throws IOException {
        try (JarFile jar = new JarFile(serverJar.toFile())) {
            var entry = jar.getJarEntry("version.json");
            if (entry == null) return null;
            try (InputStream input = jar.getInputStream(entry)) {
                byte[] bytes = input.readNBytes(1024 * 1024 + 1);
                require(bytes.length <= 1024 * 1024, "Server version.json exceeds the 1 MiB limit.");
                Matcher matcher = MINECRAFT_VERSION_ID.matcher(new String(bytes, StandardCharsets.UTF_8));
                return matcher.find() ? matcher.group(1) : null;
            }
        }
    }

    private void listModpacks() throws IOException {
        if (!Files.isDirectory(modpacksDirectory)) {
            System.out.println("No modpacks found under " + modpacksDirectory + ".");
            return;
        }
        List<Path> packs;
        try (var entries = Files.list(modpacksDirectory)) {
            packs = entries.filter(path -> Files.isRegularFile(path.resolve("pack.toml"))).sorted().collect(Collectors.toList());
        }
        if (packs.isEmpty()) System.out.println("No modpacks found under " + modpacksDirectory + ".");
        else packs.forEach(path -> System.out.println(path.getFileName()));
    }

    private PreparedPack prepareModpack(String packName, String trajectoryName) throws Exception {
        validateName("modpack", packName);
        validateName("trajectory", trajectoryName);
        Path packDirectory = modpacksDirectory.resolve(packName);
        Path packFile = packDirectory.resolve("pack.toml");
        require(Files.isRegularFile(packFile), "Unknown modpack '" + packName + "' (expected " + packFile + ").");
        require(Files.isRegularFile(packDirectory.resolve("fabric.mod.json")), "Pack '" + packName + "' has no aggregate fabric.mod.json.");

        String indexName = tomlValue(packFile, "file");
        String indexFormat = tomlValue(packFile, "hash-format");
        String expectedIndexHash = normalizedHash(indexFormat, tomlValue(packFile, "hash"));
        require(indexName != null && !indexName.contains("/") && !indexName.contains("\\") && !indexName.startsWith("."), "Pack '" + packName + "' has an unsafe index path.");
        Path indexFile = packDirectory.resolve(indexName);
        require(Files.isRegularFile(indexFile), "Pack '" + packName + "' index not found: " + indexFile);
        require(hash(indexFormat, indexFile).equals(expectedIndexHash), "Pack '" + packName + "' index hash does not match pack.toml. Run packwiz refresh.");

        List<IndexEntry> indexEntries = readIndex(indexFile);
        require(!indexEntries.isEmpty(), "Pack '" + packName + "' index contains no files.");
        Set<String> indexedPaths = new HashSet<>();
        for (IndexEntry entry : indexEntries) {
            Path relative = safeRelativePath(entry.file, "Pack '" + packName + "' has an unsafe index path: " + entry.file);
            Path indexedFile = packDirectory.resolve(relative);
            require(Files.isRegularFile(indexedFile), "Pack '" + packName + "' index entry is missing: " + indexedFile);
            String expected = normalizedHash(indexFormat, entry.hash);
            require(hash(indexFormat, indexedFile).equals(expected), "Pack '" + packName + "' index hash does not match " + entry.file + ". Run packwiz refresh.");
            indexedPaths.add(relative.toString().replace('\\', '/'));
        }
        require(indexedPaths.contains("fabric.mod.json"), "Pack '" + packName + "' index does not include fabric.mod.json.");
        require(indexedPaths.contains("ladder.tsv"), "Pack '" + packName + "' index does not include ladder.tsv.");

        String packMinecraft = tomlValue(packFile, "minecraft");
        String fabricVersion = tomlValue(packFile, "fabric");
        require(minecraftVersion.equals(packMinecraft), "Pack '" + packName + "' targets Minecraft " + packMinecraft + ", launcher targets " + minecraftVersion + ".");
        String fingerprint = packFingerprint(packDirectory);
        Path packView = modpackStore.resolve("packs").resolve(packName).resolve(fingerprint);
        Path instance = modpackStore.resolve("trajectories").resolve(trajectoryName).resolve(packName);
        Path assets = modpackStore.resolve("shared/assets");
        Files.createDirectories(modpackStore.resolve("artifacts"));
        Files.createDirectories(packView.resolve("mods"));
        Files.createDirectories(packView.resolve("resourcepacks"));
        Files.createDirectories(packView.resolve("shaderpacks"));
        Files.createDirectories(packView.resolve("metadata/mods"));
        Files.createDirectories(packView.resolve("metadata/resourcepacks"));
        Files.createDirectories(packView.resolve("metadata/shaderpacks"));
        Files.createDirectories(instance.resolve("home"));
        Files.createDirectories(instance.resolve("profiles"));
        Files.createDirectories(assets);
        copy(packFile, packView.resolve("metadata").resolve(packFile.getFileName()));
        copy(indexFile, packView.resolve("metadata").resolve(indexFile.getFileName()));
        copy(packDirectory.resolve("fabric.mod.json"), packView.resolve("metadata/fabric.mod.json"));
        copy(packDirectory.resolve("ladder.tsv"), packView.resolve("metadata/ladder.tsv"));

        List<Path> modFiles;
        Path modsDirectory = packDirectory.resolve("mods");
        if (Files.isDirectory(modsDirectory)) {
            try (var files = Files.list(modsDirectory)) {
                modFiles = files.filter(path -> path.getFileName().toString().endsWith(".pw.toml")).sorted().collect(Collectors.toList());
            }
        } else {
            modFiles = List.of();
        }
        require(!modFiles.isEmpty(), "Pack '" + packName + "' has no mods/*.pw.toml entries.");

        for (Path metadataFile : modFiles) {
            String relativeMetadata = packDirectory.relativize(metadataFile).toString().replace('\\', '/');
            require(indexedPaths.contains(relativeMetadata), "Pack '" + packName + "' index does not include " + relativeMetadata + ".");
            ResolvedArtifact resolved = resolveArtifact(metadataFile, "mod", Set.of("client", "both"));
            if (resolved == null) continue;

            Path extractedMetadata = packView.resolve("metadata/mods").resolve(resolved.filename + ".fabric.mod.json");
            try (JarFile jar = new JarFile(resolved.artifact.toFile())) {
                var entry = jar.getJarEntry("fabric.mod.json");
                require(entry != null, resolved.name + " does not contain fabric.mod.json.");
                try (InputStream input = jar.getInputStream(entry)) {
                    byte[] bytes = input.readNBytes(1024 * 1024 + 1);
                    require(bytes.length <= 1024 * 1024, resolved.name + " fabric.mod.json exceeds the 1 MiB limit.");
                    Files.write(extractedMetadata, bytes);
                }
            }
            copy(metadataFile, packView.resolve("metadata").resolve(metadataFile.getFileName()));
            stageArtifact(resolved, packView.resolve("mods"));
        }

        List<Path> resourcePacks = new ArrayList<>();
        Path resourcePacksDirectory = packDirectory.resolve("resourcepacks");
        if (Files.isDirectory(resourcePacksDirectory)) {
            List<Path> metadataFiles;
            try (var files = Files.list(resourcePacksDirectory)) {
                metadataFiles = files.filter(path -> path.getFileName().toString().endsWith(".pw.toml")).sorted().collect(Collectors.toList());
            }
            for (Path metadataFile : metadataFiles) {
                String relativeMetadata = packDirectory.relativize(metadataFile).toString().replace('\\', '/');
                require(indexedPaths.contains(relativeMetadata), "Pack '" + packName + "' index does not include " + relativeMetadata + ".");
                ResolvedArtifact resolved = resolveArtifact(metadataFile, "resource pack", Set.of("client", "both"));
                if (resolved == null) continue;
                require(resolved.filename.toLowerCase(Locale.ROOT).endsWith(".zip"), "Resource pack artifact must be a ZIP: " + resolved.filename);
                try (ZipFile zip = new ZipFile(resolved.artifact.toFile())) {
                    require(zip.getEntry("pack.mcmeta") != null, resolved.name + " does not contain pack.mcmeta.");
                    require(zip.stream().anyMatch(entry -> entry.getName().startsWith("assets/")), resolved.name + " does not contain an assets/ tree.");
                }
                copy(metadataFile, packView.resolve("metadata/resourcepacks").resolve(metadataFile.getFileName()));
                resourcePacks.add(stageArtifact(resolved, packView.resolve("resourcepacks")));
            }
        }
        List<Path> shaderPacks = new ArrayList<>();
        String shaderOptions = null;
        Path shaderPacksDirectory = packDirectory.resolve("shaderpacks");
        if (Files.isDirectory(shaderPacksDirectory)) {
            List<Path> metadataFiles;
            try (var files = Files.list(shaderPacksDirectory)) {
                metadataFiles = files.filter(path -> path.getFileName().toString().endsWith(".pw.toml")).sorted().collect(Collectors.toList());
            }
            require(metadataFiles.size() <= 1, "Pack '" + packName + "' declares more than one default shader pack.");
            for (Path metadataFile : metadataFiles) {
                String relativeMetadata = packDirectory.relativize(metadataFile).toString().replace('\\', '/');
                require(indexedPaths.contains(relativeMetadata), "Pack '" + packName + "' index does not include " + relativeMetadata + ".");
                ResolvedArtifact resolved = resolveArtifact(metadataFile, "shader pack", Set.of("client", "both"));
                if (resolved == null) continue;
                require(resolved.filename.toLowerCase(Locale.ROOT).endsWith(".zip"), "Shader pack artifact must be a ZIP: " + resolved.filename);
                try (ZipFile zip = new ZipFile(resolved.artifact.toFile())) {
                    require(
                        zip.stream().anyMatch(entry -> !entry.isDirectory() && entry.getName().replace('\\', '/').contains("shaders/")),
                        resolved.name + " does not contain a shaders/ tree."
                    );
                }
                copy(metadataFile, packView.resolve("metadata/shaderpacks").resolve(metadataFile.getFileName()));
                shaderPacks.add(stageArtifact(resolved, packView.resolve("shaderpacks")));
                shaderOptions = tomlValue(metadataFile, "shader-options");
                if (shaderOptions != null) {
                    require(
                        shaderOptions.matches("[A-Za-z_][A-Za-z0-9_]*=[^;=\\r\\n]+(?:;[A-Za-z_][A-Za-z0-9_]*=[^;=\\r\\n]+)*"),
                        "Invalid shader-options in " + metadataFile
                    );
                }
            }
        }
        List<PreparedContentFixture> contentFixtures = prepareContentFixtures(
            packDirectory,
            indexedPaths,
            instance,
            packMinecraft,
            packView.resolve("metadata")
        );

        System.out.printf("Prepared Fabric pack %s (Minecraft %s, Fabric %s).%n", packName, packMinecraft, fabricVersion);
        System.out.println("  immutable view: " + packView);
        System.out.println("  trajectory:     " + instance);
        System.out.println("  resource packs: " + (resourcePacks.size() + contentFixtures.size()));
        System.out.println("  shader pack:    " + (shaderPacks.isEmpty() ? "none" : shaderPacks.get(0)));
        System.out.println("  data packs:     " + contentFixtures.size());
        System.out.println("  content fixtures: " + contentFixtures.size());
        return new PreparedPack(
            packView,
            instance,
            assets,
            resourcePacks,
            shaderPacks.isEmpty() ? null : shaderPacks.get(0),
            shaderOptions,
            contentFixtures
        );
    }

    private List<PreparedContentFixture> prepareContentFixtures(
        Path packDirectory,
        Set<String> indexedPaths,
        Path instance,
        String packMinecraft,
        Path metadataDirectory
    ) throws Exception {
        Path definitions = packDirectory.resolve("fixtures.tsv");
        if (!Files.exists(definitions)) return List.of();
        require(Files.isRegularFile(definitions), "Content fixture definition is not a regular file: " + definitions);
        require(indexedPaths.contains("fixtures.tsv"), "Pack index does not include fixtures.tsv.");
        require(Files.size(definitions) <= 64 * 1024, "Content fixture definition exceeds 64 KiB: " + definitions);
        copy(definitions, metadataDirectory.resolve("fixtures.tsv"));

        List<String> lines = Files.readAllLines(definitions, StandardCharsets.UTF_8);
        require(!lines.isEmpty(), "Content fixture definition is empty: " + definitions);
        require(lines.get(0).equals("id\tsource\tmanifest_sha256\tresource_files\tdata_files"),
            "Content fixture definition has an unsupported header: " + definitions);
        require(lines.size() <= 65, "Content fixture definition exceeds 64 entries: " + definitions);

        Set<String> ids = new HashSet<>();
        List<PreparedContentFixture> prepared = new ArrayList<>();
        for (int lineNumber = 1; lineNumber < lines.size(); lineNumber++) {
            String line = lines.get(lineNumber);
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] fields = line.split("\\t", -1);
            require(fields.length == 5, "Invalid content fixture line " + (lineNumber + 1) + " in " + definitions);
            String id = fields[0];
            validateName("content fixture", id);
            require(ids.add(id), "Duplicate content fixture id '" + id + "'.");
            Path relative = safeRelativePath(fields[1], "Content fixture '" + id + "' has an unsafe source path.");
            Path source = project.resolve(relative).normalize();
            require(source.startsWith(project), "Content fixture '" + id + "' escapes the project.");
            Path descriptor = source.resolve("fixture.json");
            Path resources = source.resolve("resources");
            Path dataPacks = source.resolve("datapacks");
            require(Files.isRegularFile(descriptor), "Content fixture '" + id + "' has no fixture.json.");
            require(Files.isRegularFile(resources.resolve("pack.mcmeta")), "Content fixture '" + id + "' has no resource pack metadata.");
            require(Files.isRegularFile(dataPacks.resolve("pack.mcmeta")), "Content fixture '" + id + "' has no data pack metadata.");

            JsonNode fixture = DebugJson.MAPPER.readTree(descriptor.toFile());
            require(fixture.isObject(), "Content fixture '" + id + "' descriptor is not an object.");
            require(packMinecraft.equals(fixture.path("minecraft").asText()),
                "Content fixture '" + id + "' targets Minecraft " + fixture.path("minecraft").asText() + ", pack targets " + packMinecraft + ".");
            String expectedManifest = normalizedHash("sha256", fields[2]);
            require(expectedManifest.equals(fixture.path("output").path("manifest_sha256").asText()),
                "Content fixture '" + id + "' definition and descriptor manifest disagree.");
            int expectedResources = parsePositiveInt(fields[3], "resource_files for content fixture '" + id + "'");
            int expectedData = parsePositiveInt(fields[4], "data_files for content fixture '" + id + "'");
            require(expectedResources == fixture.path("output").path("resource_files").asInt(-1),
                "Content fixture '" + id + "' resource count disagrees with fixture.json.");
            require(expectedData == fixture.path("output").path("data_files").asInt(-1),
                "Content fixture '" + id + "' data count disagrees with fixture.json.");

            FixtureManifest actual = contentFixtureManifest(source);
            require(expectedManifest.equals(actual.hash),
                "Content fixture '" + id + "' manifest mismatch: expected " + expectedManifest + ", got " + actual.hash + ".");
            require(expectedResources == actual.resourceFiles,
                "Content fixture '" + id + "' resource count mismatch: expected " + expectedResources + ", got " + actual.resourceFiles + ".");
            require(expectedData == actual.dataFiles,
                "Content fixture '" + id + "' data count mismatch: expected " + expectedData + ", got " + actual.dataFiles + ".");

            Path staged = instance.resolve("content-fixtures").resolve(id).resolve(expectedManifest);
            stageContentFixture(source, staged, expectedManifest);
            prepared.add(new PreparedContentFixture(
                id,
                expectedManifest,
                staged.resolve("resources"),
                staged.resolve("datapacks")
            ));
        }
        return List.copyOf(prepared);
    }

    private FixtureManifest contentFixtureManifest(Path root) throws Exception {
        List<Path> files;
        try (var paths = Files.walk(root)) {
            files = paths
                .filter(Files::isRegularFile)
                .filter(path -> {
                    String relative = root.relativize(path).toString().replace('\\', '/');
                    return relative.startsWith("resources/") || relative.startsWith("datapacks/");
                })
                .sorted(Comparator.comparing(path -> root.relativize(path).toString().replace('\\', '/')))
                .collect(Collectors.toList());
        }
        MessageDigest manifest = MessageDigest.getInstance("SHA-256");
        int resourceFiles = 0;
        int dataFiles = 0;
        for (Path file : files) {
            String relative = root.relativize(file).toString().replace('\\', '/');
            if (relative.startsWith("resources/")) resourceFiles++;
            if (relative.startsWith("datapacks/")) dataFiles++;
            String digest = hash("sha256", file);
            manifest.update((digest + "  " + relative + "\n").getBytes(StandardCharsets.UTF_8));
        }
        return new FixtureManifest(HexFormat.of().formatHex(manifest.digest()), resourceFiles, dataFiles);
    }

    private void stageContentFixture(Path source, Path target, String expectedManifest) throws Exception {
        if (Files.isDirectory(target)) {
            FixtureManifest staged = contentFixtureManifest(target);
            require(expectedManifest.equals(staged.hash), "Staged content fixture failed verification: " + target);
            return;
        }
        Files.createDirectories(target.getParent());
        Path candidate = target.resolveSibling("." + target.getFileName() + ".candidate." + ProcessHandle.current().pid());
        deleteTree(candidate);
        try {
            copyTree(source, candidate);
            FixtureManifest staged = contentFixtureManifest(candidate);
            require(expectedManifest.equals(staged.hash), "Candidate content fixture failed verification: " + candidate);
            try {
                Files.move(candidate, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException error) {
                Files.move(candidate, target);
            } catch (FileAlreadyExistsException ignored) {
                deleteTree(candidate);
                FixtureManifest existing = contentFixtureManifest(target);
                require(expectedManifest.equals(existing.hash), "Concurrent staged content fixture failed verification: " + target);
            }
        } catch (Throwable error) {
            try {
                deleteTree(candidate);
            } catch (Throwable cleanup) {
                error.addSuppressed(cleanup);
            }
            throw error;
        }
    }

    private void copyTree(Path source, Path target) throws IOException {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.collect(Collectors.toList())) {
                require(!Files.isSymbolicLink(path), "Content fixtures must not contain symbolic links: " + path);
                Path destination = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    require(Files.isRegularFile(path), "Unsupported content fixture entry: " + path);
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) {
                Files.deleteIfExists(path);
            }
        }
    }

    private int parsePositiveInt(String value, String label) {
        try {
            int parsed = Integer.parseInt(value);
            require(parsed > 0, label + " must be positive.");
            return parsed;
        } catch (NumberFormatException error) {
            throw failure(label + " must be an integer: " + value);
        }
    }

    private ResolvedArtifact resolveArtifact(Path metadataFile, String kind, Set<String> acceptedSides) throws Exception {
        String name = tomlValue(metadataFile, "name");
        String filename = tomlValue(metadataFile, "filename");
        String side = tomlValue(metadataFile, "side");
        String hashFormat = tomlValue(metadataFile, "hash-format");
        String expectedHash = normalizedHash(hashFormat, tomlValue(metadataFile, "hash"));
        String url = tomlValue(metadataFile, "url");
        require(name != null && filename != null && side != null && hashFormat != null && url != null, "Incomplete Packwiz metadata: " + metadataFile);
        require(!filename.startsWith(".") && !filename.contains("/") && !filename.contains("\\"), "Unsafe " + kind + " filename in " + metadataFile + ": " + filename);
        require(Set.of("client", "both", "server").contains(side), "Invalid side '" + side + "' in " + metadataFile);
        if (!acceptedSides.contains(side)) return null;

        Path artifactDirectory = modpackStore.resolve("artifacts").resolve(hashFormat).resolve(expectedHash);
        Path artifact = artifactDirectory.resolve(filename);
        Files.createDirectories(artifactDirectory);
        if (Files.isRegularFile(artifact)) {
            require(hash(hashFormat, artifact).equals(expectedHash), "Cached artifact failed verification: " + artifact);
        } else {
            Path partial = artifactDirectory.resolve("." + filename + ".part." + ProcessHandle.current().pid());
            boolean imported = importPortableCacheArtifact(name, filename, hashFormat, expectedHash, partial, artifact);
            if (!imported) {
                URI uri = URI.create(url);
                require(!"minosoft-cache".equalsIgnoreCase(uri.getScheme()),
                    name + " is cache-only. Set MINOSOFT_MODPACK_CACHE and add " + filename + " with './play.sh modpack cache add FILE'.");
                System.out.println("Downloading " + name + " " + filename + "...");
                download(uri, partial);
                require(hash(hashFormat, partial).equals(expectedHash), "Downloaded artifact hash mismatch for " + name + "; retained " + partial + " for inspection.");
                makeReadOnly(partial);
                publishArtifact(partial, artifact, expectedHash, hashFormat);
            }
        }
        return new ResolvedArtifact(name, filename, hashFormat, expectedHash, artifact);
    }

    private boolean importPortableCacheArtifact(
        String name,
        String filename,
        String hashFormat,
        String expectedHash,
        Path partial,
        Path artifact
    ) throws Exception {
        if (modpackCache == null) return false;
        Path cached = portableCacheArtifact(hashFormat, expectedHash, filename);
        if (!Files.isRegularFile(cached)) return false;
        require(Files.size(cached) <= MAX_MODPACK_ARTIFACT_BYTES,
            "Portable cached artifact exceeds the 1 GiB limit: " + cached);
        Files.copy(cached, partial, StandardCopyOption.REPLACE_EXISTING);
        require(hash(hashFormat, partial).equals(expectedHash),
            "Portable cached artifact hash mismatch for " + name + ": " + cached);
        makeReadOnly(partial);
        publishArtifact(partial, artifact, expectedHash, hashFormat);
        System.out.println("Imported " + name + " " + filename + " from portable cache.");
        return true;
    }

    private Path portableCacheArtifact(String hashFormat, String fingerprint, String filename) {
        require(modpackCache != null, "Set MINOSOFT_MODPACK_CACHE to the portable cache directory.");
        return modpackCache.resolve(hashFormat).resolve(fingerprint).resolve(filename);
    }

    private void publishPortableCacheArtifact(Path source, Path artifact, String hashFormat, String expectedHash) throws Exception {
        Files.createDirectories(artifact.getParent());
        if (Files.isRegularFile(artifact)) {
            require(hash(hashFormat, artifact).equals(expectedHash), "Portable cached artifact failed verification: " + artifact);
            return;
        }
        Path partial = artifact.getParent().resolve("." + artifact.getFileName() + ".part." + ProcessHandle.current().pid());
        Files.copy(source, partial, StandardCopyOption.REPLACE_EXISTING);
        require(hash(hashFormat, partial).equals(expectedHash), "Portable cache source changed while publishing: " + source);
        makeReadOnly(partial);
        publishArtifact(partial, artifact, expectedHash, hashFormat);
    }

    private Path stageArtifact(ResolvedArtifact resolved, Path destination) throws Exception {
        Path staged = destination.resolve(resolved.filename);
        if (!Files.isRegularFile(staged)) {
            try {
                Files.createLink(staged, resolved.artifact);
            } catch (IOException | UnsupportedOperationException error) {
                Files.copy(resolved.artifact, staged);
                makeReadOnly(staged);
            }
        }
        require(hash(resolved.hashFormat, staged).equals(resolved.expectedHash), "Staged artifact failed verification: " + staged);
        return staged;
    }

    private List<IndexEntry> readIndex(Path indexFile) throws IOException {
        require(Files.size(indexFile) <= MAX_SCENARIO_BYTES, "Pack index exceeds the 4 MiB limit: " + indexFile);
        List<IndexEntry> entries = new ArrayList<>();
        String file = null;
        String hash = null;
        boolean inFile = false;
        try (var reader = Files.newBufferedReader(indexFile, StandardCharsets.UTF_8)) {
            for (String line; (line = reader.readLine()) != null;) {
                if (line.trim().equals("[[files]]")) {
                    if (inFile) entries.add(new IndexEntry(file, hash));
                    file = null;
                    hash = null;
                    inFile = true;
                } else if (inFile && line.trim().startsWith("file")) {
                    file = quotedValue(line);
                } else if (inFile && line.trim().startsWith("hash")) {
                    hash = quotedValue(line);
                }
            }
        }
        if (inFile) entries.add(new IndexEntry(file, hash));
        for (IndexEntry entry : entries) require(entry.file != null && entry.hash != null, "Pack index has an incomplete file entry: " + indexFile);
        return entries;
    }

    private String packFingerprint(Path packDirectory) throws Exception {
        List<Path> files = new ArrayList<>(List.of(
            packDirectory.resolve("pack.toml"),
            packDirectory.resolve("index.toml"),
            packDirectory.resolve("fabric.mod.json"),
            packDirectory.resolve("ladder.tsv")
        ));
        Path mods = packDirectory.resolve("mods");
        if (Files.isDirectory(mods)) {
            try (var entries = Files.list(mods)) {
                entries.filter(path -> path.getFileName().toString().endsWith(".pw.toml")).sorted().forEach(files::add);
            }
        }
        Path resourcePacks = packDirectory.resolve("resourcepacks");
        if (Files.isDirectory(resourcePacks)) {
            try (var entries = Files.list(resourcePacks)) {
                entries.filter(path -> path.getFileName().toString().endsWith(".pw.toml")).sorted().forEach(files::add);
            }
        }
        Path shaderPacks = packDirectory.resolve("shaderpacks");
        if (Files.isDirectory(shaderPacks)) {
            try (var entries = Files.list(shaderPacks)) {
                entries.filter(path -> path.getFileName().toString().endsWith(".pw.toml")).sorted().forEach(files::add);
            }
        }
        MessageDigest digest = digest("sha256");
        for (Path file : files) {
            if (!Files.isRegularFile(file)) continue;
            String relative = packDirectory.relativize(file).toString().replace('\\', '/');
            String line = hash("sha256", file) + "  " + relative + "\n";
            digest.update(line.getBytes(StandardCharsets.UTF_8));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private PreparedCanary prepareCanary() throws Exception {
        require(Files.isDirectory(canarySourceDirectory), "Canary source not found at " + canarySourceDirectory + ".");
        runInherited(List.of(project.resolve(isWindows() ? "gradlew.bat" : "gradlew").toString(), "--quiet", "canaryModJar"), project);
        require(Files.isRegularFile(canaryBuildJar), "Canary build did not produce " + canaryBuildJar + ".");

        String fingerprint = hash("sha256", canaryBuildJar);
        Path artifact = modpackStore.resolve("artifacts/sha256").resolve(fingerprint).resolve(canaryBuildJar.getFileName());
        Files.createDirectories(artifact.getParent());
        if (!Files.isRegularFile(artifact)) {
            Path partial = artifact.getParent().resolve("." + artifact.getFileName() + ".part." + ProcessHandle.current().pid());
            Files.copy(canaryBuildJar, partial, StandardCopyOption.REPLACE_EXISTING);
            require(hash("sha256", partial).equals(fingerprint), "Canary staging hash changed while publishing.");
            makeReadOnly(partial);
            publishArtifact(partial, artifact, fingerprint, "sha256");
        }
        require(hash("sha256", artifact).equals(fingerprint), "Published canary artifact failed verification: " + artifact);
        System.out.printf("Prepared hot-reload canary %s (%s).%n", fingerprint.substring(0, 12), artifact);
        return new PreparedCanary(artifact, fingerprint);
    }

    private void download(URI uri, Path target) throws Exception {
        require("https".equalsIgnoreCase(uri.getScheme()), "Mod download URI must use HTTPS: " + uri);
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(20)).build();
        Exception last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                Files.deleteIfExists(target);
                HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(2)).GET().build();
                HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(target));
                if (response.statusCode() >= 200 && response.statusCode() < 300) return;
                Files.deleteIfExists(target);
                last = new IOException("HTTP " + response.statusCode());
            } catch (Exception error) {
                last = error;
            }
            if (attempt < 3) Thread.sleep(1_000);
        }
        throw failure("Could not download " + uri + ": " + (last == null ? "unknown error" : last.getMessage()));
    }

    private void publishArtifact(Path partial, Path artifact, String expectedHash, String hashFormat) throws Exception {
        try {
            try {
                Files.move(partial, artifact, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException error) {
                Files.move(partial, artifact);
            }
        } catch (FileAlreadyExistsException error) {
            require(hash(hashFormat, artifact).equals(expectedHash), "Concurrent cached artifact failed verification: " + artifact);
            Files.deleteIfExists(partial);
        }
    }

    private void makeReadOnly(Path file) throws IOException {
        try {
            Set<PosixFilePermission> permissions = new HashSet<>(Files.getPosixFilePermissions(file));
            permissions.remove(PosixFilePermission.OWNER_WRITE);
            permissions.remove(PosixFilePermission.GROUP_WRITE);
            permissions.remove(PosixFilePermission.OTHERS_WRITE);
            Files.setPosixFilePermissions(file, permissions);
        } catch (UnsupportedOperationException error) {
            require(file.toFile().setWritable(false, false), "Could not make artifact read-only: " + file);
        }
    }

    private String tomlValue(Path file, String key) throws IOException {
        Pattern pattern = Pattern.compile("^\\s*" + Pattern.quote(key) + "\\s*=\\s*\\\"([^\\\"]*)\\\"\\s*$");
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            Matcher matcher = pattern.matcher(line);
            if (matcher.matches()) return matcher.group(1);
        }
        return null;
    }

    private String quotedValue(String line) {
        int first = line.indexOf('"');
        int last = line.lastIndexOf('"');
        return first >= 0 && last > first ? line.substring(first + 1, last) : null;
    }

    private String normalizedHash(String format, String value) {
        require(format != null && value != null, "Missing hash metadata.");
        String normalized = value.toLowerCase(Locale.ROOT);
        int length = switch (format) {
            case "sha256" -> 64;
            case "sha512" -> 128;
            default -> throw failure("Unsupported pack hash format: " + format);
        };
        require(normalized.length() == length && normalized.matches("[0-9a-f]+"), "Invalid " + format + " hash: " + value);
        return normalized;
    }

    private String hash(String format, Path file) throws Exception {
        MessageDigest digest = digest(format);
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private MessageDigest digest(String format) throws Exception {
        return MessageDigest.getInstance(switch (format) {
            case "sha256" -> "SHA-256";
            case "sha512" -> "SHA-512";
            default -> throw failure("Unsupported pack hash format: " + format);
        });
    }

    private void installDistribution() throws Exception {
        runInherited(List.of(project.resolve(isWindows() ? "gradlew.bat" : "gradlew").toString(), "--quiet", "installDist"), project);
    }

    private void runInherited(List<String> command, Path directory) throws Exception {
        ProcessBuilder builder = processBuilder(command, directory);
        builder.inheritIO();
        int exit = builder.start().waitFor();
        require(exit == 0, "Command exited with status " + exit + ": " + String.join(" ", command));
    }

    private Process loggedChild(List<String> command, Path directory, Path log) throws IOException {
        return loggedChild(command, directory, log, Map.of());
    }

    private Process loggedChild(
        List<String> command,
        Path directory,
        Path log,
        Map<String, String> environmentOverrides
    ) throws IOException {
        Files.createDirectories(log.getParent());
        ProcessBuilder builder = processBuilder(command, directory);
        builder.environment().putAll(environmentOverrides);
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
        builder.redirectInput(ProcessBuilder.Redirect.from(Path.of(isWindows() ? "NUL" : "/dev/null").toFile()));
        return builder.start();
    }

    private ProcessBuilder processBuilder(List<String> command, Path directory) {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(directory.toFile());
        builder.environment().put("JAVA_HOME", javaHome.toString());
        builder.environment().put("PATH", javaHome.resolve("bin") + System.getProperty("path.separator") + builder.environment().getOrDefault("PATH", ""));
        builder.environment().putIfAbsent("MINOSOFT_DEBUG", "true");
        builder.environment().put("MINOSOFT_TRAJECTORY", trajectory);
        builder.environment().put("MINOSOFT_DEBUG_GENERATION", Integer.toString(clientGeneration));
        return builder;
    }

    private void printTail(Path file, int lines) {
        if (lines <= 0 || !Files.isRegularFile(file)) return;
        try {
            ArrayDeque<String> tail = new ArrayDeque<>(Math.max(1, lines));
            try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                for (String line; (line = reader.readLine()) != null;) {
                    if (tail.size() == lines) tail.removeFirst();
                    tail.addLast(line);
                }
            }
            tail.forEach(System.err::println);
        } catch (IOException ignored) {
        }
    }

    private Path defaultModpackStore() {
        String home = environment.get("HOME");
        require(home != null && !home.isBlank(), "HOME is required to choose the modpack store; set MINOSOFT_MODPACK_STORE.");
        if (isMac()) return Path.of(home, "Library", "Caches", "Minosoft", "modpacks");
        if (isWindows()) return Path.of(environment.getOrDefault("LOCALAPPDATA", Path.of(home, ".cache").toString()), "Minosoft", "modpacks");
        return Path.of(environment.getOrDefault("XDG_CACHE_HOME", Path.of(home, ".cache").toString()), "minosoft", "modpacks");
    }

    private Path resolveProjectPath(String value) {
        Path path = Path.of(value);
        return (path.isAbsolute() ? path : project.resolve(path)).toAbsolutePath().normalize();
    }

    private Path safeRelativePath(String value, String message) {
        require(value != null && !value.contains("\\"), message);
        Path path = Path.of(value).normalize();
        require(!path.isAbsolute() && !path.startsWith("..") && !path.toString().equals("."), message);
        return path;
    }

    private HostAndPort parseAddress(String address) {
        int separator = address.lastIndexOf(':');
        if (separator < 0) return new HostAndPort(address, 25565);
        String host = address.substring(0, separator);
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        try {
            int port = Integer.parseInt(address.substring(separator + 1));
            require(port > 0 && port <= 65_535, "Invalid port in MINECRAFT_SERVER_ADDRESS: " + address);
            return new HostAndPort(host, port);
        } catch (NumberFormatException error) {
            throw failure("Invalid port in MINECRAFT_SERVER_ADDRESS: " + address);
        }
    }

    private void copy(Path source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private String env(String key, String fallback) {
        return environment.getOrDefault(key, fallback);
    }

    private void validateName(String kind, String value) {
        require(SAFE_NAME.matcher(value).matches(), "Invalid " + kind + " '" + value + "'; use letters, digits, dot, underscore, or hyphen.");
    }

    private String joinPids(List<Long> pids) {
        return pids.stream().map(String::valueOf).collect(Collectors.joining(" "));
    }

    private boolean isMac() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac");
    }

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw failure(message);
    }

    private static PlayFailure failure(String message) {
        return new PlayFailure(message);
    }

    private static long parseLong(String value, String label) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException error) {
            throw failure(label + " must be a signed 64-bit integer: " + value);
        }
    }

    private void usage() {
        System.out.print("""
            Control the local Minecraft server and Minosoft client independently.

            Usage:
              ./play.sh ACTION [TARGET] [--modpack NAME] [--trajectory NAME]
              ./play.sh modpack list
              ./play.sh modpack cache add FILE [--hash-format sha256|sha512]
              ./play.sh modpack prepare NAME [--trajectory NAME]
              ./play.sh modpack inspect NAME [--trajectory NAME]
              ./play.sh debug COMMAND [--role client|server] [--trajectory NAME] [--endpoint ID]
              ./play.sh wait PREDICATE [--timeout 120s] [--trajectory NAME] [--json]
              ./play.sh scenario run FILE [--artifacts PATH] [--jfr MODE] [--json]
              ./play.sh worldgen inspect [WORLD] [--max-chunks N] [--json]
              ./play.sh worldgen compare BASELINE CANDIDATE [--max-chunks N] [--json]
              ./play.sh screenshot compare BASELINE ACTUAL [THRESHOLDS] [--json]
              ./play.sh lease acquire --scope SCOPE [--trajectory NAME] [--ttl 20m]
              ./play.sh lease status --json
              ./play.sh lease release TOKEN
              ./play.sh diagnose capture [--trajectory NAME] [--output PATH] [--visual] [--json]

              ACTION  dev, start, stop, or status (default: dev)
              TARGET  server or client (default: both)

            Commands:
              dev             Parent-supervised client with clean hot reload; starts a server by default
              dev client      Supervise the client against an already-running server
              start           Parent-own the server and client without watching
              start server    Parent-own only the Minecraft server
              start client    Parent-own only Minosoft against an existing server
              stop            Stop the client, then the server
              stop server     Gracefully stop and save only the Minecraft server
              stop client     Stop only the Minosoft client
              status          Show both process states
              status --json   Emit the PID/readiness contract as one JSON object
              modpack list    List source-controlled Fabric packs
              modpack cache   Add a hash-addressed artifact to a portable cache
              modpack prepare Resolve and verify a pack without starting Minosoft
              modpack inspect Resolve a pack and run Minosoft's Fabric compatibility preflight
              debug endpoints List live, discoverable client/server debug endpoints
              debug status    Sample selected endpoint status
              debug state     Sample a named client state view
              debug visual    Capture/sample pixels or measure same-pose camera-motion noise
              debug input     Inject normalized key, text, mouse, or scroll input
              debug blocks    Sample a bounded block cuboid without loading chunks
              debug compare   Normalize and compare client/server block samples
              debug mods      Show process-local mod hooks, status, and timings
              wait             Wait for a semantic server/client lifecycle predicate
              scenario run     Execute JSON acceptance steps and emit report.json plus junit.xml
              worldgen inspect Measure datapacks, biomes, terrain shape, and a canonical terrain hash
              worldgen compare Require deterministic terrain equality for same-seed A/B worlds
              screenshot       Crop reference regions or compare PNGs with bounded thresholds
              lease            Acquire, inspect, or release bounded trajectory mutation ownership
              diagnose capture Capture one bounded status, endpoint, state, render, fixture, and log bundle

            Lifecycle predicates:
              server.port-open, server.debug-ready, server.game-ready
              client.debug-ready, client.joined, client.render-ready, both.ready

            Scenario steps:
              wait, request, screenshot, sleep; top-level matrix, repeat, duration, and jfr
              JFR modes: off, always, on-failure, slow

            Pack options:
              --modpack NAME    Select a pack under modpacks/ (for example: sodium)
              --trajectory NAME Isolate mutable state for a branch/experiment (default: default)
              --canary          Build, publish, load, and watch the native hot-reload canary mod
              --local-world     Use the source-native authoritative local world (client target only)
              --debug-gpu-memory-leaks
                                Retain OpenGL buffer allocation stacks for leak diagnosis
              --world-generator Select flat, debug, void, or tech_reborn (default: flat; tech-reborn pack: tech_reborn)
              --world-seed N    Seed for deterministic local world regeneration

            Configuration:
              MINECRAFT_SERVER_JAR, MINECRAFT_SERVER_DIR, MINECRAFT_SERVER_ADDRESS
              MINECRAFT_SERVER_MEMORY, MINECRAFT_VERSION, MINECRAFT_SERVER_FLAVOR
              MINECRAFT_EULA_ACCEPTED
              MINOSOFT_ACCOUNT, MINOSOFT_JAVA_HOME, MINOSOFT_MODPACK
              MINOSOFT_SERVER_MODPACK (defaults to fabric-stack)
              MINOSOFT_TRAJECTORY, MINOSOFT_MODPACKS_DIR, MINOSOFT_MODPACK_STORE
              MINOSOFT_MODPACK_CACHE (optional portable, read-only download source)
              MINOSOFT_CANARY=true (equivalent to --canary)
              MINOSOFT_LOCAL_WORLD=true, MINOSOFT_WORLD_GENERATOR, MINOSOFT_WORLD_SEED
              MINOSOFT_DEBUG_GPU_MEMORY_LEAKS=true
              MINOSOFT_LEASE_OWNER (optional bounded lease owner label)
              MINOSOFT_HOT_RELOAD_PATHS (platform-separated external source/staging roots)

            Logs:
              Server: server/server-console.log
              Client: .run/minosoft-client.log
              Lifecycle evidence: .run/play-events.jsonl
            """);
    }

    private static final class PlayFailure extends RuntimeException {
        private PlayFailure(String message) {
            super(message);
        }
    }

    private static final class PredicateObservation {
        private final boolean matched;
        private final long elapsedMillis;
        private final JsonNode value;

        private PredicateObservation(boolean matched, long elapsedMillis, JsonNode value) {
            this.matched = matched;
            this.elapsedMillis = elapsedMillis;
            this.value = value;
        }
    }

    private static final class ScreenshotComparison {
        private final int width;
        private final int height;
        private final long changedPixels;
        private final double changedRatio;
        private final double meanAbsoluteError;
        private final int maxChannelError;

        private ScreenshotComparison(int width, int height, long changedPixels, double changedRatio,
                                     double meanAbsoluteError, int maxChannelError) {
            this.width = width;
            this.height = height;
            this.changedPixels = changedPixels;
            this.changedRatio = changedRatio;
            this.meanAbsoluteError = meanAbsoluteError;
            this.maxChannelError = maxChannelError;
        }
    }

    private static final class JfrCapture {
        private final long pid;
        private final String role;
        private final String name;
        private final Path jcmd;

        private JfrCapture(long pid, String role, String name, Path jcmd) {
            this.pid = pid;
            this.role = role;
            this.name = name;
            this.jcmd = jcmd;
        }
    }

    private static final class CommandResult {
        private final int exitCode;
        private final String output;

        private CommandResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }

    private static final class WorldgenInspection {
        private final Path world;
        private final long seed;
        private final String terrainHash;
        private final Set<String> chunkCoordinates;
        private final ObjectNode json;

        private WorldgenInspection(Path world, long seed, String terrainHash, Set<String> chunkCoordinates, ObjectNode json) {
            this.world = world;
            this.seed = seed;
            this.terrainHash = terrainHash;
            this.chunkCoordinates = Set.copyOf(chunkCoordinates);
            this.json = json;
        }
    }

    private static final class DebugSelection {
        private DebugEndpointRole role;
        private String trajectory;
        private boolean trajectorySpecified;
        private String endpointId;
        private boolean json;

        private DebugSelection copy(DebugEndpointRole selectedRole) {
            DebugSelection copy = new DebugSelection();
            copy.role = selectedRole;
            copy.trajectory = trajectory;
            copy.trajectorySpecified = trajectorySpecified;
            copy.endpointId = endpointId;
            copy.json = json;
            return copy;
        }
    }

    private static final class HostAndPort {
        private final String host;
        private final int port;

        private HostAndPort(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }

    private static final class IndexEntry {
        private final String file;
        private final String hash;

        private IndexEntry(String file, String hash) {
            this.file = file;
            this.hash = hash;
        }
    }

    private static final class ResolvedArtifact {
        private final String name;
        private final String filename;
        private final String hashFormat;
        private final String expectedHash;
        private final Path artifact;

        private ResolvedArtifact(String name, String filename, String hashFormat, String expectedHash, Path artifact) {
            this.name = name;
            this.filename = filename;
            this.hashFormat = hashFormat;
            this.expectedHash = expectedHash;
            this.artifact = artifact;
        }
    }

    private static final class PreparedPack {
        private final Path view;
        private final Path instance;
        private final Path assets;
        private final List<Path> resourcePacks;
        private final Path shaderPack;
        private final String shaderOptions;
        private final List<PreparedContentFixture> contentFixtures;

        private PreparedPack(
            Path view,
            Path instance,
            Path assets,
            List<Path> resourcePacks,
            Path shaderPack,
            String shaderOptions,
            List<PreparedContentFixture> contentFixtures
        ) {
            this.view = view;
            this.instance = instance;
            this.assets = assets;
            this.resourcePacks = List.copyOf(resourcePacks);
            this.shaderPack = shaderPack;
            this.shaderOptions = shaderOptions;
            this.contentFixtures = List.copyOf(contentFixtures);
        }
    }

    private static final class PreparedContentFixture {
        private final String id;
        private final String manifest;
        private final Path resources;
        private final Path dataPacks;

        private PreparedContentFixture(String id, String manifest, Path resources, Path dataPacks) {
            this.id = id;
            this.manifest = manifest;
            this.resources = resources;
            this.dataPacks = dataPacks;
        }
    }

    private static final class FixtureManifest {
        private final String hash;
        private final int resourceFiles;
        private final int dataFiles;

        private FixtureManifest(String hash, int resourceFiles, int dataFiles) {
            this.hash = hash;
            this.resourceFiles = resourceFiles;
            this.dataFiles = dataFiles;
        }
    }

    private record ScreenshotRegion(int x, int y, int width, int height) {}

    private record ScreenshotCrop(int sourceWidth, int sourceHeight) {}

    private record MotionPose(
        String dimension,
        double x,
        double y,
        double z,
        double yaw,
        double pitch
    ) {
        private MotionPose withYaw(double nextYaw) {
            return new MotionPose(dimension, x, y, z, nextYaw, pitch);
        }
    }

    private record MotionFrame(long frame, BufferedImage image) {}

    private record MotionNoiseOptions(
        double yawDelta,
        int samples,
        int settleFrames,
        int awayFrames,
        int pixelThreshold,
        int flatGradientThreshold,
        int[] recoveryFrames,
        MotionNoiseAnalyzer.Region region,
        Path output
    ) {}

    private static final class PreparedCanary {
        private final Path artifact;
        private final String hash;

        private PreparedCanary(Path artifact, String hash) {
            this.artifact = artifact;
            this.hash = hash;
        }
    }
}
