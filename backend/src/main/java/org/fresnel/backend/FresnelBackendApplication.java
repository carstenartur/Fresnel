package org.fresnel.backend;

import org.fresnel.backend.desktop.DesktopDataDirectory;
import org.fresnel.backend.desktop.DesktopDiagnostics;
import org.fresnel.backend.desktop.FresnelDesktopLauncher;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class FresnelBackendApplication {
    public static void main(String[] args) {
        if (Boolean.getBoolean("fresnel.desktop.enabled")) {
            try {
                FresnelDesktopLauncher.launch(args);
            } catch (RuntimeException e) {
                String message = e.getMessage() == null
                        ? e.getClass().getSimpleName()
                        : e.getMessage();
                System.err.println("Fresnel desktop launch failed: " + message);
                DesktopDiagnostics.append(
                        DesktopDataDirectory.resolve(),
                        "Fresnel desktop launch failed",
                        e);
                throw e;
            }
            return;
        }
        SpringApplication.run(FresnelBackendApplication.class, args);
    }
}
