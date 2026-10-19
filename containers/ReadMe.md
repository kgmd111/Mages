# Containerized Mages Web

## Usage

[`compose.override.template.yaml`](./compose.override.template.yaml) should be
copied to `compose.override.yaml`, adjusted if needed, then:

```shell
docker compose up --detach
```

Then Mages Web should be available at <http://127.0.0.1:8080> (with default
template values).

NOTE: this setup is not ready to be deployed publicly. If that is needed, the
container should be served with a reverse proxy and TLS.
