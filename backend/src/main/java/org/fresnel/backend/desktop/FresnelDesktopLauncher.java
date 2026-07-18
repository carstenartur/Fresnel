package org.fresnel.backend.desktop;

import org.fresnel.backend.FresnelBackendApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextClosedEvent;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;

/** Entry point used only by the native jpackage desktop application. */
public final class FresnelDesktopLauncher {

    private static final Duration PRIMARY_READY_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration PRIMARY_READY_POLL = Duration.ofMillis(150);
    private static final int SECRET_BYTES = 32;

    private FresnelDesktopLauncher() {}

    public static void launch(String[] arguments) {
        DesktopLaunchRequest request = DesktopLaunchRequest.parse(arguments);
        Path dataDirectory = DesktopDataDirectory.resolve();
        DesktopOpenClient client = new DesktopOpenClient();
        DesktopBrowser browser = new DesktopBrowser();

        try {
            Optional<PrimaryInstanceCoordinator> ownership =
                    PrimaryInstanceCoordinator.tryAcquire(dataDirectory);
            if (ownership.isPresent()) {
                startPrimary(request, ownership.orElseThrow(), client, browser, dataDirectory);
                return;
            }

            try {
                handOffToPrimary(
                        request,
                        awaitPrimary(dataDirectory, client),
                        client,
                        browser,
                        dataDirectory);
                return;
            } catch (PrimaryNotReadyException firstFailure) {
                // The former primary may have crashed while this invocation was
                // waiting. A second lock attempt safely recovers stale metadata.
                ownership = PrimaryInstanceCoordinator.tryAcquire(dataDirectory);
                if (ownership.isPresent()) {
                    startPrimary(request, ownership.orElseThrow(), client, browser, dataDirectory);
                    return;
                }
                throw firstFailure;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Desktop launch was interrupted", e);
        } catch (IOException | PrimaryNotReadyException e) {
            throw new IllegalStateException("Could not coordinate the Fresnel desktop instance: "
                    + concise(e), e);
        }
    }

    private static void startPrimary(
            DesktopLaunchRequest request,
            PrimaryInstanceCoordinator coordinator,
            DesktopOpenClient client,
            DesktopBrowser browser,
            Path dataDirectory) {
        ConfigurableApplicationContext context = null;
        try {
            String sessionSecret = randomSecret();
            DesktopDiagnostics.append(
                    dataDirectory,
                    request.jobFile().isPresent()
                            ? "Starting primary Fresnel desktop instance with a job"
                            : "Starting primary Fresnel desktop instance",
                    null);
            configureDesktopSystemProperties(sessionSecret, dataDirectory, request);

            SpringApplication application = new SpringApplication(FresnelBackendApplication.class);
            context = application.run(request.springArguments().toArray(String[]::new));
            ConfigurableApplicationContext startedContext = context;
            context.addApplicationListener(event -> {
                if (event instanceof ContextClosedEvent closedEvent
                        && closedEvent.getApplicationContext() == startedContext) {
                    coordinator.close();
                }
            });

            int port = actualPort(context);
            DesktopInstanceMetadata metadata = new DesktopInstanceMetadata(
                    DesktopInstanceMetadata.CURRENT_PROTOCOL_VERSION,
                    port,
                    ProcessHandle.current().pid(),
                    sessionSecret,
                    System.currentTimeMillis());

            DesktopOpenQueue queue = context.getBean(DesktopOpenQueue.class);
            String importId = request.jobFile()
                    .map(path -> enqueueStartupFile(queue, path))
                    .orElse(null);

            coordinator.publish(metadata);
            System.out.println("Fresnel desktop instance ready on http://127.0.0.1:" + port);
            URI uri = importId == null
                    ? client.baseBrowserUri(metadata)
                    : client.browserUri(metadata, importId);
            openBrowser(browser, uri, dataDirectory);
        } catch (RuntimeException | IOException e) {
            if (context != null) {
                try {
                    context.close();
                } catch (RuntimeException ignored) {
                    // Preserve the original startup failure.
                }
            }
            coordinator.close();
            throw new IllegalStateException("Could not start the Fresnel desktop application: "
                    + concise(e), e);
        }
    }

    private static void handOffToPrimary(
            DesktopLaunchRequest request,
            DesktopInstanceMetadata metadata,
            DesktopOpenClient client,
            DesktopBrowser browser,
            Path dataDirectory) throws IOException, InterruptedException {
        if (!request.springArguments().isEmpty()) {
            throw new IllegalArgumentException(
                    "Spring Boot arguments cannot be applied while Fresnel is already running");
        }

        final URI uri;
        if (request.jobFile().isPresent()) {
            byte[] jobBytes = Files.readAllBytes(request.jobFile().orElseThrow());
            String importId = client.submit(metadata, jobBytes);
            uri = client.browserUri(metadata, importId);
        } else {
            uri = client.baseBrowserUri(metadata);
        }
        openBrowser(browser, uri, dataDirectory);
    }

    private static DesktopInstanceMetadata awaitPrimary(
            Path dataDirectory,
            DesktopOpenClient client) throws InterruptedException, PrimaryNotReadyException {
        long deadline = System.nanoTime() + PRIMARY_READY_TIMEOUT.toNanos();
        String lastProblem = "instance metadata has not been published";

        while (System.nanoTime() < deadline) {
            try {
                Optional<DesktopInstanceMetadata> metadata =
                        PrimaryInstanceCoordinator.readPublished(dataDirectory);
                if (metadata.isPresent()) {
                    if (client.ping(metadata.orElseThrow())) {
                        return metadata.orElseThrow();
                    }
                    lastProblem = "authenticated loopback readiness check failed";
                }
            } catch (IOException | IllegalArgumentException e) {
                lastProblem = concise(e);
            }
            Thread.sleep(PRIMARY_READY_POLL.toMillis());
        }
        throw new PrimaryNotReadyException(
                "The running Fresnel desktop instance did not become ready: " + lastProblem);
    }

    private static String enqueueStartupFile(DesktopOpenQueue queue, Path path) {
        try {
            return queue.enqueue(Files.readAllBytes(path));
        } catch (IOException e) {
            System.err.println("Could not read the selected Fresnel job: " + concise(e));
            return queue.enqueueError(
                    "FILE_READ_ERROR",
                    "The selected Fresnel job could not be read after Fresnel started.");
        }
    }

    private static void openBrowser(DesktopBrowser browser, URI uri, Path dataDirectory) {
        if (!browser.open(uri)) {
            DesktopDiagnostics.append(
                    dataDirectory,
                    "Could not open the default browser. Open this local URL manually: " + uri,
                    null);
        }
    }

    private static int actualPort(ConfigurableApplicationContext context) {
        if (!(context instanceof WebServerApplicationContext webContext)) {
            throw new IllegalStateException("Fresnel did not start as a web server application");
        }
        WebServer webServer = webContext.getWebServer();
        if (webServer == null || webServer.getPort() < 1) {
            throw new IllegalStateException("Fresnel web server did not publish a usable port");
        }
        return webServer.getPort();
    }

    private static void configureDesktopSystemProperties(
            String sessionSecret,
            Path dataDirectory,
            DesktopLaunchRequest request) {
        System.setProperty("fresnel.desktop.enabled", "true");
        System.setProperty("fresnel.desktop.instance-secret", sessionSecret);
        System.setProperty("FRESNEL_DATA_DIR", dataDirectory.toString());

        // Desktop IPC and public one-time imports must never become network-visible,
        // even if an external standalone configuration contains another address.
        System.setProperty("server.address", "127.0.0.1");

        boolean externalLogConfigured = System.getProperty("logging.file.name") != null
                || System.getenv("LOGGING_FILE_NAME") != null
                || request.springArguments().stream()
                .anyMatch(argument -> argument.startsWith("--logging.file.name="));
        if (!externalLogConfigured) {
            System.setProperty(
                    "logging.file.name",
                    DesktopDiagnostics.applicationLog(dataDirectory).toString());
        }
    }

    private static String randomSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String concise(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) return exception.getClass().getSimpleName();
        return message.replace('\r', ' ').replace('\n', ' ').trim();
    }

    private static final class PrimaryNotReadyException extends Exception {
        private PrimaryNotReadyException(String message) {
            super(message);
        }
    }
}
