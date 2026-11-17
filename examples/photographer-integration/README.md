# Pair Fresnel with Photographer

Open Fresnel on port 8081 and Photographer on port 8080. Fresnel contacts the configured address from its backend. Photographer needs its Fresnel integration enabled and a selectable camera profile. Its current bridge supports mock profiles. For physical camera images use the manual upload in the measurement editor.

| Installation | Address configured in Fresnel | Setup |
|---|---|---|
| Both processes on the same host | `http://127.0.0.1:8080` | Launch Fresnel with `SERVER_PORT=8081`. Keep both HTTP listeners on loopback. |
| Both in Compose | `http://focusstack:8080` | Use the private network and overlay below. |
| Fresnel in Docker, Photographer on the host | `http://host.docker.internal:8080` on a trusted private link | On Linux add `extra_hosts: ["host.docker.internal:host-gateway"]` to the Fresnel service. Bind Photographer on an address reachable from the Docker bridge, with private ingress enabled. |
| Separate hosts | The Photographer HTTPS origin | Use a TLS endpoint reachable from the Fresnel backend and configure that exact origin in both pairing screens. |

## Private Compose network

Create the shared network once.

```sh
docker network create fresnel-capture
```

From the Photographer checkout, start its ordinary Compose project with the [Photographer overlay](photographer.override.yaml). Replace the example path with the local Fresnel checkout path and use the base Compose filename for your Photographer installation.

```sh
docker compose -f docker-compose.yml -f /path/to/Fresnel/examples/photographer-integration/photographer.override.yaml up -d focusstack
```

Set distinct Fresnel operator and administrator passwords in the shell. From the Fresnel repository root, start Fresnel with its own data volume.

```sh
docker compose -f examples/photographer-integration/compose.yaml up -d --build
```

Open Photographer's **Fresnel** page and request a pairing code for `http://focusstack:8080`. In Fresnel at `http://127.0.0.1:8081`, open **Camera service → Manage Photographer pairing** and enter the code. The camera service lists the available cameras after successful pairing. The network carries the capture protocol and source image transfers. Each service owns its own storage.

[Photographer pairing, configuration and capability limits](https://github.com/carstenartur/Photographer/blob/main/docs/fresnel-capture.md) · [Fresnel capture and recovery](../../docs/integrations/photographer-provider.md)
