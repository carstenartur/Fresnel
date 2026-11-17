package org.fresnel.backend.measurement.photographer;

import org.fresnel.measurement.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import java.nio.file.*;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

/** A plain Maven verify exercises pairing and idempotent acquisition across a real provider-process restart. */
class PhotographerContainerIT {
    @TempDir Path data;

    private GenericContainer<?> fixture() throws Exception {
        String image;
        try (var lines = Files.lines(Path.of("..","Dockerfile"))) {
            image = lines.filter(l -> l.startsWith("FROM node:")).findFirst().orElseThrow().split(" ")[1];
        }
        return new GenericContainer<>(DockerImageName.parse(image)).withExposedPorts(8080)
                .withCopyFileToContainer(MountableFile.forClasspathResource("photographer/photometry-image.mjs"),"/fixture/photometry-image.mjs")
                .withCopyFileToContainer(MountableFile.forClasspathResource("photographer/container-fixture.mjs"),"/fixture/server.mjs")
                .withCopyFileToContainer(MountableFile.forHostPath(Path.of("../docs/integrations/photographer-capture-v1.fixtures.json").toAbsolutePath()),"/fixture/contract.json")
                .withCommand("sh","-c","while :; do node /fixture/server.mjs; done").waitingFor(Wait.forHttp("/ready"));
    }

    @Test void recoversAStillPairWithExactlyTwoShutterReleases() throws Exception {
        try (var container = fixture()) {
            container.start();
            String origin = "http://" + container.getHost() + ":" + container.getMappedPort(8080);
            var paired = new PhotographerPairing(origin,true,data.resolve("pairing"));
            paired.pair("test-only-code-fixture");
            var provider = new PhotographerCaptureProvider(paired::current,data.resolve("journal"));
            assertEquals(CaptureProvider.HealthState.CONNECTED,provider.health().state());
            var plan = PhotographerInteropTest.bos(provider.listDevices().getFirst().id());
            String id = provider.createSession(plan,"fixture-bos-1","create-once").id();
            var ack = new CaptureProvider.StepTrigger(null,null,Instant.now());
            var first = provider.triggerStep(id,"reference",ack,"reference-once");
            assertEquals("reference",first.asset().stepId());

            // Restart the camera service inside its container. Docker may reassign a random host port
            // on a full container restart, which would correctly invalidate the origin-bound pairing.
            String previousPid = container.execInContainer("cat","/tmp/photographer.pid").getStdout().trim();
            var stopped = container.execInContainer("kill","-TERM",previousPid);
            assertEquals(0,stopped.getExitCode());
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(20)).until(() ->
                    !previousPid.equals(container.execInContainer("cat","/tmp/photographer.pid").getStdout().trim()));
            var reopened = new PhotographerPairing(origin,true,data.resolve("pairing"));
            var resumed = new PhotographerCaptureProvider(reopened::current,data.resolve("journal"));
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(30)).ignoreExceptions()
                    .until(() -> resumed.health().state() == CaptureProvider.HealthState.CONNECTED);
            assertEquals(id,resumed.createSession(plan,"fixture-bos-1","create-once").id());
            assertEquals(first,resumed.triggerStep(id,"reference",ack,"reference-once"));
            var second = resumed.triggerStep(id,"disturbed",new CaptureProvider.StepTrigger(null,null,Instant.now()),"disturbed-once");
            for (var source : java.util.List.of(first.asset(),second.asset())) {
                try (var stream = resumed.openAsset(id,source.assetId(),CaptureProvider.FullAsset.INSTANCE)) {
                    var pixels = stream.readAllBytes();
                    assertEquals(source.byteLength(),pixels.length);
                    assertEquals(source.sha256(),java.util.HexFormat.of().formatHex(PhotographerJournal.sha256().digest(pixels)));
                }
            }
            assertEquals(CaptureProvider.SessionState.COMPLETED,resumed.getSession(id).state());
            var evidence = container.execInContainer("node","-p","require('/tmp/capture-state.json').shots");
            assertEquals(0,evidence.getExitCode());
            assertEquals("2",evidence.getStdout().trim());
        }
    }
    @Test void capturesAndReopensPhotometryUsingTheRealBrowserAndBackend() throws Exception {
        try (var remote = fixture()) {
            remote.start();
            var origin = "http://" + remote.getHost() + ":" + remote.getMappedPort(8080);
            try (var app = new org.springframework.boot.builder.SpringApplicationBuilder(
                    org.fresnel.backend.FresnelBackendApplication.class).run(
                    "--server.port=0", "--server.address=127.0.0.1", "--spring.jmx.enabled=false",
                    "--fresnel.capture.photographer.enabled=true", "--fresnel.capture.photographer.origin=" + origin,
                    "--fresnel.capture.photographer.trust-private-network=true",
                    "--fresnel.capture.photographer.directory=" + data.resolve("pair"),
                    "--fresnel.capture.photographer.journal-directory=" + data.resolve("journal"),
                    "--fresnel.measurements.directory=" + data.resolve("experiments"))) {
                app.getBean(PhotographerPairing.class).pair("test-only-code-fixture");
                var port = app.getEnvironment().getRequiredProperty("local.server.port");
                org.fresnel.backend.e2e.PlaywrightE2EIT.runBrowser(java.net.URI.create("http://127.0.0.1:" + port), true);
                var count = remote.execInContainer("node","-p","require('/tmp/capture-state.json').shots");
                assertEquals(0, count.getExitCode());
                assertEquals("6", count.getStdout().trim());
            }
        }
    }

}
