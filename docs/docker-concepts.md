# Docker, explained from zero

The "why" behind Phase 9. The [phase 9 review](phase-9-review.md) shows what was built and how to run and check it.

---

## 1. The problem

"It works on my machine." A program depends on much more than its own code: the Java version, libraries, environment variables, the operating system, other programs it talks to. Install RideX on a new computer and you must repeat every step exactly: Java 21, Maven, Node, PostgreSQL, Redis, Kafka, six start commands, the right ports. One missed step and it fails, in a way that is hard to explain.

**Docker packages a program together with everything it needs to run**, and runs it isolated from everything else. The package runs the same way on your laptop, a teammate's laptop, a test server and production.

### Containers are not virtual machines

| | Virtual machine | Container |
|---|---|---|
| What it contains | A whole operating system, its own kernel | Only the program and its files; **shares the host's kernel** |
| Size | Gigabytes | Megabytes to a few hundred MB |
| Start time | Minutes | Seconds (RideX's pricing service: about 20 s, and that is Spring Boot starting) |
| Isolation | Very strong (separate kernel) | Good (separate view of processes, files, network, users), enforced by the kernel |

On Windows, Docker Desktop runs one small Linux virtual machine and all containers share *its* kernel. That VM has a fixed memory budget (on this machine 4 GB), which is why every container here has a memory limit.

---

## 2. Vocabulary: image, container, registry

| Term | Meaning | Analogy |
|---|---|---|
| **Image** | A read-only, layered package: files + the command to run. Built from a Dockerfile | A class, or an installer |
| **Container** | A running (or stopped) instance of an image, with a thin writable layer on top | An object, or a running program |
| **Dockerfile** | The recipe for building an image | A build script |
| **Registry** | A server that stores images (Docker Hub, GitHub Container Registry) | An app store |
| **Volume** | Storage that lives outside any container and survives its deletion | An external disk |
| **Network** | A private virtual network between containers, with DNS by name | A LAN |

You can delete and recreate a container at any time. **Anything that must survive goes in a volume** (database files, the core's token key), everything else is disposable. That is what makes upgrades simple: build a new image, replace the container.

---

## 3. Step 9.1: Dockerfiles for the Spring services

### Layers, and why order matters
Every instruction in a Dockerfile (`COPY`, `RUN`...) creates a **layer**, a snapshot of the files it changed. Docker caches layers: if an instruction and everything above it are unchanged, the cached layer is reused instead of running it again. Consequences:

* Put what **changes rarely** first (the libraries) and what **changes often** last (our code). Edit one line of Java and only the last, tiny layer is rebuilt.
* Spring Boot cooperates: it can split its jar into **layers** (`dependencies`, `application`...). For the pricing service the libraries are a 64.6 MB layer and our own code is **98 kB**. After a code change Docker rebuilds 98 kB, not 65 MB, and a registry pushes 98 kB, not 65 MB.

### Multi-stage builds
Compiling needs a JDK, Maven and a few hundred MB of downloaded libraries. Running needs none of that. A **multi-stage build** uses several `FROM` stages in one Dockerfile and copies only what is needed from one stage to the next; everything else is discarded.

```
stage "build"    maven + JDK (814 MB)   compiles all modules into jars          -> thrown away
stage "layers"   (same tools)           splits the jar into layers              -> thrown away
stage "runtime"  JRE on Alpine          copies only the layers  -> the image    -> 410 MB for pricing, 478 MB for the core
```

The final image has **no compiler, no Maven, no source code**. That is smaller, faster to pull and, importantly, safer: an attacker who gets into the container finds no tools to build attacks with.

### Small images
* **JRE, not JDK**: the runtime without the compiler.
* **Alpine Linux**: a base of about 5 MB instead of a full distribution.
* **Only what is needed**: the frontend image is **86 MB** (nginx + static files); the Node stage that built it (hundreds of MB) is not in it.

### A non-root user
By default a process in a container runs as **root** (inside the container's own view, but still powerful). If the application has a vulnerability, the attacker inherits those rights. The Dockerfile creates an ordinary user (`ridex`, id 10001, no login shell) and switches to it with `USER`. The nginx image we use (`nginx-unprivileged`) is built to run as a normal user and listens on port 8080, because ports below 1024 require root.

### Pinned versions
`FROM eclipse-temurin:21-jre-alpine`, never `:latest`. `latest` means "whatever was published most recently", so the same Dockerfile builds a different image next month. A test fails the build if any image is unpinned.

### Other details worth knowing
* **`.dockerignore`** lists what is *not* sent to the builder: `target/`, `data/`, `.env`, `*.pem`. Faster builds, and a secret that was never sent cannot end up in an image.
* **`exec java ...`** in the entrypoint makes Java process number 1, so Docker's stop signal (SIGTERM) reaches it and Spring shuts down gracefully (finishes requests, commits Kafka offsets). Without `exec`, a shell would be PID 1, swallow the signal, and Docker would kill the app after 10 seconds.
* **`-XX:MaxRAMPercentage=70`** sizes the Java heap as a percentage of the *container's* memory limit. Old JVMs looked at the host's RAM, ignored the limit, and were killed by Docker (OOM kill) when they grew.
* **Cache mounts** (`RUN --mount=type=cache,target=/root/.m2`) keep Maven's download folder between builds without putting it in any layer: the first build downloads the internet, later ones only compile.
* **One Dockerfile, six services**, selected by `--build-arg MODULE=...`. Six copies would drift apart.

---

## 4. Step 9.2: the React app in nginx

A React app is, after `npm run build`, just files: HTML, JavaScript, CSS. No Node is needed to serve them, only a web server. **nginx** is the standard: tiny, fast, battle-tested.

What `nginx.conf` does, and why:

| Rule | Why |
|---|---|
| `try_files $uri /index.html` | A single-page app has one real HTML file; `/rides/42` is handled by React's router in the browser. If someone opens or refreshes that URL, nginx must serve `index.html`, not a 404 |
| `/assets/` cached for a year, `immutable` | Vite puts a content hash in every file name (`index-3f9a1c.js`): a changed file gets a new name, so old names can be cached for ever |
| `index.html` is `no-cache` | It points at the hashed file names; if a browser kept an old copy it would ask for files that no longer exist |
| `location /api/` → `http://gateway:8000` | The production version of the Vite dev proxy. The browser sees **one origin**, so no CORS |
| `location /ws` with `Upgrade`/`Connection` headers | A WebSocket starts as an HTTP request asking to *upgrade*. A proxy must pass that request on, or live updates silently never connect |
| `proxy_set_header X-Forwarded-For $remote_addr` | Tells the gateway the real client. nginx **overwrites** any value the client sent, instead of appending to it |
| `server_tokens off`, `X-Content-Type-Options`, `X-Frame-Options` | Don't advertise the nginx version; stop browsers guessing types; forbid putting the site in an iframe (clickjacking) |
| `/healthz` | A cheap URL for the container health check |

One thing to know: nginx looks up the name `gateway` **when it starts**. If the gateway does not exist yet, nginx refuses to start ("host not found in upstream"). That is why the `web` container waits for the gateway to be healthy.

---

## 5. Step 9.3: Docker Compose

Running eleven containers with `docker run` means eleven long commands with the right order, networks, variables and volumes. **Compose** describes the whole system in one YAML file and runs it with `docker compose up`.

### Networks and DNS
Compose creates private networks. Containers on the same network reach each other **by service name**: `postgres`, `kafka`, `gateway`. Docker's built-in DNS turns the name into the container's current address, which changes whenever a container is recreated, which is why hard-coded IP addresses never work.

We use two networks:

```
edge       web, gateway
internal   gateway, core, notification, payment, pricing, analytics, postgres, redis, kafka, mailpit
```

The website container can only reach the gateway. The database is on a network the website is not on. Even if the web container were taken over, PostgreSQL is not reachable from it. This is **network segmentation**.

### Published ports
A container's ports are private until you **publish** them (`ports: "127.0.0.1:3000:8080"` = host port 3000 → container port 8080). We publish as little as possible:

* the website (3000), the gateway (8000), and developer tools (Postgres, Redis, Kafka, the Mailpit inbox);
* **always on `127.0.0.1`**, so other computers on your Wi-Fi cannot reach them;
* **never** the core or the other four services (8080 to 8084). They are reachable only from inside the network, through the gateway. A smoke test checks that those ports are closed on the host.

### Health checks and `depends_on`
`depends_on: [postgres]` alone only waits until the container has *started*, which is not the same as *ready to accept connections*. The service would start, fail to connect, and crash.

* A **health check** is a command Docker runs every few seconds inside the container (`pg_isready`, `wget .../actuator/health`). The container is `starting`, then `healthy` or `unhealthy`.
* `depends_on: { postgres: { condition: service_healthy } }` waits for `healthy`.
* `start_period` gives slow starters time: Spring Boot needs up to a minute, and failures during that time do not count.

The resulting order: postgres, redis, kafka → core, notification, payment, pricing, analytics → gateway (needs the core healthy) → web (needs the gateway healthy).

Note that depends_on only orders *startup*. It is not a promise that a dependency stays up, so every service must still cope with a dependency disappearing (Phase 7's retries and circuit breakers).

### Two Kafka addresses
Kafka tells every client "reach me at this address", and the address must work *for that client*. Services inside Docker need `kafka:29092`; the programs on your own machine (IDE, `start-services.ps1`) need `localhost:9092`. So the broker has two **listeners**, `INTERNAL` and `EXTERNAL`, each advertising its own address. With one address, one kind of client would be sent somewhere it cannot reach.

### Environment variables and secrets
Configuration differs per environment (database host, URLs), and the same image must run everywhere. So **configuration comes from outside**, through environment variables, never baked into the image. Spring's relaxed binding makes `SPRING_DATASOURCE_URL` override `spring.datasource.url`.

Passwords need more care than environment variables:

| | Environment variable | Docker secret (file) |
|---|---|---|
| Visible in `docker inspect` | **Yes** | No |
| Inherited by every child process, shown in crash dumps and diagnostics | **Yes** | No |
| Where it lives | the container's configuration | a file at `/run/secrets/NAME` inside only the containers that list it (with plain Compose it is the host file mounted in; Swarm and Kubernetes keep secrets in memory) |

So the database password and the shared service token are **secrets**: files in a git-ignored folder, mounted into only the containers that need them (the gateway and web get none). Spring reads them with `spring.config.import=configtree:/run/secrets/`, which turns every file into a property named after the file, so `${DB_PASSWORD}` in `application.properties` keeps working with no code change. A test checks the compose file contains no literal password.

Your own admin login (`ADMIN_EMAIL` / `ADMIN_PASSWORD`) is read from the shell or the git-ignored `.env`; never put it in a tracked file.

### Volumes
`ridex_pgdata`, `ridex_redisdata`, `ridex_kafkadata`: the data of the three infrastructure services. `core_data`: the token **signing key** (without it every user would be logged out at every restart) and the uploaded driver documents. `docker compose down` keeps volumes; `docker compose down -v` **deletes them and all your data**.

### Hardening applied to every application container
| Setting | Effect |
|---|---|
| `USER` non-root | A compromised app is not root |
| `read_only: true` + `tmpfs: /tmp` | The filesystem cannot be modified; only an in-memory scratch folder is writable. Malware cannot drop files |
| `cap_drop: ALL` | Removes all Linux capabilities (binding low ports, changing file owners...) |
| `no-new-privileges` | A process can never gain more rights than it started with |
| `mem_limit` | One runaway service cannot starve the others; the JVM sizes itself to the limit |
| `init: true` | A real PID 1 that forwards signals and cleans up zombie processes |
| `restart: unless-stopped` | A crashed container is started again |
| log rotation (`max-size`) | Logs are files; without a limit they fill the disk |

### Behind two proxies: the real client address
The browser's request reaches the gateway from the nginx container, not from the user. The gateway would see one "client" for everybody and rate-limit them all together. So nginx writes the real address in `X-Forwarded-For`, and the gateway believes that header **only when the connection comes from a trusted proxy address** (the Docker network ranges). A request that arrives from anywhere else, with a forged header, is ignored. The same idea one hop further: the gateway passes the address on and the core accepts it only from internal addresses.

---

## 6. Day-to-day commands

| Goal | Command (in `infrastructure/docker-compose`) |
|---|---|
| Create the secret files, once | `..\..\scripts\docker-init.ps1` |
| Build and start everything | `docker compose up -d --build` |
| Start only the infrastructure (run Java from your IDE) | `docker compose up -d postgres redis kafka mailpit` |
| What is running, and healthy? | `docker compose ps` |
| Follow one service's log | `docker compose logs -f core` |
| Rebuild and replace one service after a code change | `docker compose up -d --build core` |
| Restart one service | `docker compose restart pricing` |
| Memory and CPU used | `docker stats` |
| A shell inside a container | `docker exec -it ridex-core sh` |
| Stop everything, keep the data | `docker compose down` |
| Stop everything and **delete the data** | `docker compose down -v` |

---

## 7. Vocabulary

| Term | Meaning |
|---|---|
| Image / container | The package / a running instance of it |
| Layer | One cached filesystem change in an image |
| Multi-stage build | Several `FROM` stages in one Dockerfile; only the last becomes the image |
| Build context / `.dockerignore` | The files sent to the builder / what to leave out |
| Base image | The image a Dockerfile starts `FROM` |
| Volume | Persistent storage outside the container |
| Bridge network | A private virtual network between containers, with name resolution |
| Health check | A command that tells Docker whether the app inside really works |
| Secret | A sensitive value delivered as a file, not as an environment variable |
| Listener (Kafka) | An address and protocol the broker accepts connections on |
| Capability | One slice of root's power; dropped when not needed |
| OOM kill | The kernel killing a process that used more memory than its limit |
