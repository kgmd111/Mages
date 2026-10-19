# Containerized Mages Web

## Usage

[`compose.override.template.yaml`](./compose.override.template.yaml) should be
copied to `compose.override.yaml`, adjusted if needed, then:

```shell
docker compose up --detach
```

Then Mages Web should be available at <http://127.0.0.1:8080> (with default
template values).

## Prebuilt image

Tagged releases publish `ghcr.io/mlm-games/mages-web` for `linux/amd64` and
`linux/arm64`:

```shell
docker run --rm --detach --name mages \
  --publish 127.0.0.1:8080:8080 \
  ghcr.io/mlm-games/mages-web:latest
```

`docker compose pull` fetches the published image for the compose setup instead
of building it locally.

NOTE: this setup is not ready to be deployed publicly. If that is needed, the
container should be served with a reverse proxy and TLS.
