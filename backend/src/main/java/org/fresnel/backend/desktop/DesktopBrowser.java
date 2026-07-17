package org.fresnel.backend.desktop;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Locale;

/** Opens a trusted loopback URI in the user's default browser. */
public final class DesktopBrowser {

    @FunctionalInterface
    interface ProcessStarter {
        void start(List<String> command) throws IOException;
    }

    private final ProcessStarter processStarter;
    private final String osName;

    public DesktopBrowser() {
        this(command -> new ProcessBuilder(command).start(), System.getProperty("os.name", ""));
    }

    DesktopBrowser(ProcessStarter processStarter, String osName) {
        if (processStarter == null) throw new IllegalArgumentException("processStarter must not be null");
        this.processStarter = processStarter;
        this.osName = osName == null ? "" : osName;
    }

    /**
     * Opens the URI and returns true on a successful launch attempt. Failures are
     * reported to stderr together with the usable local URL.
     */
    public boolean open(URI uri) {
        requireLoopbackHttpUri(uri);
        if (!Boolean.parseBoolean(System.getProperty("fresnel.desktop.browser.enabled", "true"))) {
            System.out.println("Fresnel desktop URL: " + uri);
            return true;
        }

        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            try {
                Desktop.getDesktop().browse(uri);
                return true;
            } catch (Exception e) {
                System.err.println("Could not open the default browser through java.awt.Desktop: "
                        + concise(e));
            }
        }

        try {
            processStarter.start(platformCommand(uri));
            return true;
        } catch (IOException e) {
            System.err.println("Could not open the default browser: " + concise(e));
            System.err.println("Open this local URL manually: " + uri);
            return false;
        }
    }

    private List<String> platformCommand(URI uri) {
        String os = osName.toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return List.of("rundll32", "url.dll,FileProtocolHandler", uri.toASCIIString());
        }
        if (os.contains("mac")) {
            return List.of("open", uri.toASCIIString());
        }
        return List.of("xdg-open", uri.toASCIIString());
    }

    private static void requireLoopbackHttpUri(URI uri) {
        if (uri == null
                || !"http".equalsIgnoreCase(uri.getScheme())
                || !"127.0.0.1".equals(uri.getHost())
                || uri.getPort() < 1) {
            throw new IllegalArgumentException("Desktop browser URI must use loopback HTTP");
        }
    }

    private static String concise(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message.replace('\r', ' ').replace('\n', ' ').trim();
    }
}
