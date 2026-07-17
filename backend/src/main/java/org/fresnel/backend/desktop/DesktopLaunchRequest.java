package org.fresnel.backend.desktop;

import org.fresnel.backend.api.FresnelJobDocument;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Parsed command line for the packaged Fresnel desktop launcher.
 *
 * <p>Only a single local {@code .fresnel} file is accepted before the optional
 * {@code --} separator. Arguments after the separator are passed to Spring Boot
 * only when this invocation becomes the primary process.</p>
 */
public record DesktopLaunchRequest(Optional<Path> jobFile, List<String> springArguments) {

    public DesktopLaunchRequest {
        jobFile = jobFile == null ? Optional.empty() : jobFile;
        springArguments = springArguments == null ? List.of() : List.copyOf(springArguments);
    }

    public static DesktopLaunchRequest parse(String[] arguments) {
        String[] args = arguments == null ? new String[0] : arguments;
        Path jobFile = null;
        List<String> springArguments = new ArrayList<>();
        boolean springSection = false;

        for (int i = 0; i < args.length; i++) {
            String argument = args[i];
            if (springSection) {
                validateSpringArgument(argument);
                springArguments.add(argument);
                continue;
            }
            if ("--".equals(argument)) {
                springSection = true;
                continue;
            }
            if ("--open".equals(argument)) {
                if (++i >= args.length || "--".equals(args[i])) {
                    throw new IllegalArgumentException("--open requires one .fresnel file path");
                }
                if (jobFile != null) {
                    throw new IllegalArgumentException("Only one .fresnel file can be opened at a time");
                }
                jobFile = validateJobFile(args[i]);
                continue;
            }
            if (argument.startsWith("-")) {
                throw new IllegalArgumentException(
                        "Unknown desktop option: " + argument
                                + ". Put Spring Boot arguments after --.");
            }
            if (jobFile != null) {
                throw new IllegalArgumentException("Only one .fresnel file can be opened at a time");
            }
            jobFile = validateJobFile(argument);
        }

        return new DesktopLaunchRequest(Optional.ofNullable(jobFile), springArguments);
    }

    private static void validateSpringArgument(String argument) {
        if (argument == null) {
            throw new IllegalArgumentException("Spring Boot arguments must not be null");
        }
        String normalized = argument.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("--fresnel.desktop.")
                || normalized.startsWith("--server.address")) {
            throw new IllegalArgumentException(
                    "The packaged desktop launcher reserves this Spring property: " + argument);
        }
    }

    private static Path validateJobFile(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new IllegalArgumentException("The Fresnel job path must not be empty");
        }

        final Path path;
        try {
            path = Path.of(rawPath).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid Fresnel job path: " + rawPath, e);
        }

        String filename = path.getFileName() == null ? "" : path.getFileName().toString();
        if (!filename.toLowerCase(Locale.ROOT).endsWith(FresnelJobDocument.FILE_EXTENSION)) {
            throw new IllegalArgumentException(
                    "Desktop open accepts only " + FresnelJobDocument.FILE_EXTENSION + " files: " + filename);
        }
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Fresnel job is not a regular file: " + path);
        }
        if (!Files.isReadable(path)) {
            throw new IllegalArgumentException("Fresnel job is not readable: " + path);
        }
        try {
            long size = Files.size(path);
            if (size <= 0) {
                throw new IllegalArgumentException("Fresnel job must not be empty: " + path);
            }
            if (size > FresnelJobDocument.MAX_FILE_BYTES) {
                throw new IllegalArgumentException(
                        "Fresnel job exceeds the maximum size of "
                                + FresnelJobDocument.MAX_FILE_BYTES + " bytes");
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not inspect Fresnel job: " + path, e);
        }
        return path;
    }
}
