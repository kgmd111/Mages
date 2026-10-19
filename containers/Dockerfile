ARG gradle_version=9.8
ARG jdk_version=25
FROM docker.io/gradle:${gradle_version}-jdk${jdk_version} AS builder
ARG destination=/srv/app
ARG rust_version
COPY [".", "${destination}"]
WORKDIR ${destination}
RUN \
  apt-get update && \
  apt-get --assume-yes --no-install-recommends install \
    gcc \
    libc-dev \
    rustup && \
  rustup default ${rust_version:-1.98.0} && \
  rustup target add wasm32-unknown-unknown && \
  cargo install wasm-bindgen-cli && \
  cp ~/.cargo/bin/wasm-bindgen /usr/local/bin/ && \
  ./gradlew :webApp:wasmJsBrowserDistribution

FROM docker.io/alpine:3.24
ARG app_dir=/srv/app
ARG port=8080
ARG user=mages
RUN \
  apk add lighttpd && \
  adduser -D -H "${user}" "${user}" && \
  { \
    printf 'index-file.names = ( "index.html" )\n' && \
    printf 'mimetype.assign = (\n' && \
    printf '  ".html"   => "text/html",\n' && \
    printf '  ".htm"    => "text/html",\n' && \
    printf '  ".css"    => "text/css",\n' && \
    printf '  ".csv"    => "text/csv",\n' && \
    printf '  ".xml"    => "application/xml",\n' && \
    printf '  ".js"     => "application/javascript",\n' && \
    printf '  ".json"   => "application/json",\n' && \
    printf '  ".rss"    => "application/rss+xml",\n' && \
    printf '  ".atom"   => "application/atom+xml",\n' && \
    printf '  ".xhtml"  => "application/xhtml+xml",\n' && \
    printf '  ".ttf"    => "font/ttf",\n' && \
    printf '  ".eot"    => "application/vnd.ms-fontobject",\n' && \
    printf '  ".otf"    => "font/otf",\n' && \
    printf '  ".woff"   => "font/woff",\n' && \
    printf '  ".woff2"  => "font/woff2",\n' && \
    printf '  ".wasm"   => "application/wasm",\n' && \
    printf '  ".svg"    => "image/svg+xml",\n' && \
    printf '  ".txt"    => "text/plain",\n' && \
    printf '  ".md"     => "text/markdown",\n' && \
    printf '  ".map"    => "application/json",\n' && \
    printf '  ""        => "application/octet-stream"\n' && \
    printf ')\n' && \
    printf 'server.document-root = "/srv/app"\n' && \
    printf 'server.feature-flags += (\n' && \
    printf '  "server.h2c" => "disable",\n' && \
    printf '  "server.h2proto" => "disable"\n' && \
    printf ')\n' && \
    printf 'server.groupname = "%s"\n' "${user}" && \
    printf 'server.port = %d\n' "${port}" && \
    printf 'server.username = "%s"\n' "${user}" && \
    printf 'static-file.exclude-extensions ' && \
    printf '= ( ".fcgi", ".php", ".rb", "~", ".inc" )\n'; \
  } >/etc/lighttpd/lighttpd.conf
COPY \
  --chown=${user}:${user} \
  --from=builder \
  ["/srv/app/webApp/build/dist/wasmJs/productionExecutable", "${app_dir}"]
EXPOSE ${port}
USER ${user}
WORKDIR ${app_dir}
ENTRYPOINT ["/usr/sbin/lighttpd"]
CMD ["-D", "-f", "/etc/lighttpd/lighttpd.conf"]
