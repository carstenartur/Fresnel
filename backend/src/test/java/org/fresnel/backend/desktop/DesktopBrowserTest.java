package org.fresnel.backend.desktop;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopBrowserTest {

    private static final URI LOCAL_URI = URI.create(
            "http://127.0.0.1:8080/?fresnelOpen=" + "A".repeat(43));

    @Test
    void buildsArgumentListsWithoutShellInterpolation() {
        DesktopBrowser windows = new DesktopBrowser(command -> {}, "Windows 11");
        DesktopBrowser mac = new DesktopBrowser(command -> {}, "Mac OS X");
        DesktopBrowser linux = new DesktopBrowser(command -> {}, "Linux");

        assertEquals(List.of("rundll32", "url.dll,FileProtocolHandler", LOCAL_URI.toASCIIString()),
                windows.platformCommand(LOCAL_URI));
        assertEquals(List.of("open", LOCAL_URI.toASCIIString()),
                mac.platformCommand(LOCAL_URI));
        assertEquals(List.of("xdg-open", LOCAL_URI.toASCIIString()),
                linux.platformCommand(LOCAL_URI));
    }

    @Test
    void rejectsRemoteOrNonHttpUrisBeforeStartingAProcess() {
        AtomicReference<List<String>> command = new AtomicReference<>();
        DesktopBrowser browser = new DesktopBrowser(command::set, "Linux");

        assertThrows(IllegalArgumentException.class,
                () -> browser.open(URI.create("https://127.0.0.1:8080/")));
        assertThrows(IllegalArgumentException.class,
                () -> browser.open(URI.create("http://example.com:8080/")));
        assertEquals(null, command.get());
    }

    @Test
    void browserOpeningCanBeDisabledWithoutChangingTheLocalUrl() {
        String previous = System.getProperty("fresnel.desktop.browser.enabled");
        try {
            System.setProperty("fresnel.desktop.browser.enabled", "false");
            DesktopBrowser browser = new DesktopBrowser(
                    command -> { throw new AssertionError("process must not start"); }, "Linux");
            assertTrue(browser.open(LOCAL_URI));
        } finally {
            if (previous == null) System.clearProperty("fresnel.desktop.browser.enabled");
            else System.setProperty("fresnel.desktop.browser.enabled", previous);
        }
    }
}
