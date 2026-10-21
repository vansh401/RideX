# Kubernetes, explained from zero

The "why" behind Phase 10. The [phase 10 review](phase-10-review.md) shows what was built and how to run and check it. It assumes you read [docker-concepts.md](docker-concepts.md): Kubernetes runs the containers you already know.

---

## 1. The problem Compose cannot solve

Docker Compose runs a set of containers on **one machine**. That leaves questions it has no answer to:

* The machine dies. Everything is down until a person starts it again somewhere else.
* One service needs three copies behind one address. Who spreads the requests, and who notices when one copy is unhealthy?
* You release a new version. How do you replace the old containers one by one without a moment of downtime, and go back if it is broken?
* The load doubles at 6 pm. Who starts more copies, and on which machine?
* Fifty containers on ten machines: which container goes where, given their memory needs?

**Kubernetes (K8s)** is a system that runs containers across many machines and keeps them in the state you asked for. You do not say "start this container"; you say **"I want three copies of this, always"** and Kubernetes makes it true, and keeps making it true.

### The idea that explains everything: desired state and reconciliation
You describe the **desired state** in YAML ("3 replicas of image X, reachable at name Y"). Kubernetes stores it and runs **controllers**: loops that compare *what is* with *what you wanted* and act on the difference, forever. Delete a pod by accident: the controller sees 2 of 3 and creates another. Change the image: it sees pods that no longer match and replaces them. This is called **reconciliation**, and it is why Kubernetes is declarative, not a list of commands.

---

## 2. Step 10.1: cluster, node, pod, kubectl

```
cluster
├── control plane            the brain
│     ├── API server         every request goes through it (kubectl talks to it)
│     ├── etcd               the database holding the desired state
│     ├── scheduler          decides which node a new pod runs on
│     └── controller manager the reconciliation loops
└── nodes (workers)          the machines that run containers
      ├── kubelet            the agent: starts containers, runs probes, reports health
      └── container runtime  actually runs the containers (containerd)
```

| Term | Meaning |
|---|---|
| **Cluster** | The control plane and all its nodes |
| **Node** | One machine (physical or virtual) that runs pods. On your laptop: one node |
| **Pod** | The smallest thing Kubernetes runs: **one or more containers that share a network address and storage**. Usually one container. A pod is *disposable*: it is never repaired, only replaced, and its IP address changes when it is |
| **Namespace** | A named partition of a cluster (section 4) |
| **kubectl** | The command-line client of the API server |

### A local cluster
We use **kind** ("Kubernetes IN Docker"): the whole cluster, control plane and node, is one Docker container. It starts in about a minute, and deleting it leaves nothing behind. Alternatives: minikube, k3d, and the Kubernetes option inside Docker Desktop.

### kubectl in ten commands
| Command | What it does |
|---|---|
| `kubectl get pods -n ridex-dev` | List pods in a namespace (`-A` = all namespaces) |
| `kubectl get all -n ridex-dev` | The main objects at once |
| `kubectl describe pod <name> -n ridex-dev` | Everything about one object, including its **Events** (why it did not start) |
| `kubectl logs <pod> -n ridex-dev` | Container output (`-f` follow, `--previous` the crashed run) |
| `kubectl exec -it <pod> -n ridex-dev -- sh` | A shell inside the container |
| `kubectl apply -k infrastructure/k8s/overlays/dev` | Make the cluster match these files |
| `kubectl diff -k ...` | Show what `apply` would change |
| `kubectl rollout status deployment/pricing -n ridex-dev` | Wait for an update to finish |
| `kubectl port-forward svc/gateway 8000:8000 -n ridex-dev` | Reach a cluster-internal service from your machine |
| `kubectl delete pod <name> -n ridex-dev` | Kill a pod (and watch it come back) |

---

## 3. Step 10.2: Deployments and Services

### Deployment, ReplicaSet, Pod
You almost never create pods by hand. You create a **Deployment**:

```
Deployment "pricing"      what you wrote: image, replicas: 2, update strategy
   └── ReplicaSet         created by the Deployment: "keep exactly 2 pods of THIS pod template"
          ├── Pod pricing-7d9f-abc12
          └── Pod pricing-7d9f-xk4p9
```

* The **ReplicaSet** is the reconciliation loop for "N copies".
* When you change the pod template (a new image, a new setting), the Deployment creates a **new** ReplicaSet and moves pods across gradually (section 7). The old ReplicaSet is kept (scaled to 0) so a rollback is instant: `kubectl rollout undo deployment/pricing`.
* Pod names are `<deployment>-<template hash>-<random>`: they are not meant to be remembered.

### Labels and selectors: how things find each other
Everything has **labels** (key=value). A Deployment's **selector** says which pods are its own; a Service's **selector** says which pods to send traffic to. They are matched by label, never by name. A selector that does not match its pods is the classic silent bug, so a test checks every one.

### Service: a stable address for changing pods
Pods come and go and get new IPs, so nothing can call a pod directly. A **Service** gives a group of pods a **stable virtual IP and DNS name**, and spreads requests over the pods that are **ready**:

| Type | Reachable from | Used here for |
|---|---|---|
| **ClusterIP** (default) | inside the cluster only | `core`, `pricing`, `gateway`, `web`... |
| **Headless** (`clusterIP: None`) | inside; the name resolves straight to the pod(s), no virtual IP | PostgreSQL and Kafka (they need a stable per-pod identity) |
| NodePort / LoadBalancer | from outside | not used: the Ingress is the way in (section 5) |

DNS: inside the namespace the name `pricing` works. From another namespace, `pricing.ridex-dev`; fully `pricing.ridex-dev.svc.cluster.local`. This is the answer to "service discovery" from Phase 8: Kubernetes already does it.

### Two traps we hit by design
1. **Service links.** By default Kubernetes injects variables for every Service into every pod (`REDIS_PORT=tcp://10.0.0.5:6379`). Spring reads `REDIS_PORT` as a port *number* and would fail; the Kafka image turns any `KAFKA_*` variable into broker configuration. We set `enableServiceLinks: false` on every pod.
2. **`kubectl apply` is not instant.** It stores the desired state; the controllers then work. `rollout status` and probes tell you when it is real.

---

## 4. Step 10.3: ConfigMaps, Secrets, Namespaces, and one set of files for three environments

### ConfigMap and Secret
| | ConfigMap | Secret |
|---|---|---|
| Holds | non-secret settings | passwords, tokens, keys |
| Delivered as | environment variables or files | usually **files** (we mount them under `/run/secrets`) |
| Stored | plain text in etcd | base64 in etcd (**encoding, not encryption**); access limited by RBAC, encryption at rest can be switched on |

The pods are the same as in Docker Compose: the same image, the same `SPRING_CONFIG_IMPORT=configtree:/run/secrets/` trick. *The image never changes between environments; only what is delivered to it does.*

**A Secret in git is a leak.** So: the dev Secret is generated on your machine from git-ignored files; staging and production Secrets are created outside the repository (by the team that owns them or by a secret manager: External Secrets Operator, Sealed Secrets, Vault).

### ConfigMaps roll the pods
Kustomize adds a **hash of the content to the ConfigMap name** (`ridex-config-ktk8hg28m9`). Change a value and the name changes, so the Deployments that reference it get a new pod template and **roll out new pods by themselves**. A plain ConfigMap edit would leave running pods on the old values until someone restarted them.

### Namespaces
A namespace partitions one cluster. Names only need to be unique inside a namespace, so `ridex-dev`, `ridex-staging` and `ridex-prod` can each contain a Service called `core`. Namespaces also scope permissions (RBAC), quotas and network policies. **Pod Security Admission** labels on a namespace make the cluster itself refuse dangerous pods (`enforce=baseline`) and warn about anything short of the strictest profile (`warn=restricted`).

### Kustomize: base plus overlays
```
infrastructure/k8s/
  base/                  everything that is the same: Deployments, Services, StatefulSets, Ingress, policies
  overlays/dev/          + namespace ridex-dev, mail catcher, generated secrets
  overlays/staging/      + 2 replicas of stateless parts, real mail host, TLS, release-candidate images
  overlays/prod/         + autoscaling, TLS, versioned registry images, more memory
```
Each overlay says *what differs* ("replicas: 3", "this host name", "this image tag"), and kustomize merges it over the base. No templating language, no `{{ }}`: plain YAML in, plain YAML out. `kubectl kustomize overlays/prod` prints the result without touching a cluster. (Helm is the alternative: templates with values files. It is stronger for software you *distribute*; kustomize is simpler for software you *deploy yourself*.)

---

## 5. Step 10.4: Ingress

Services are reachable only inside the cluster. To let a browser in you need an entry point:

* An **Ingress** object is a set of *rules*: "requests for host `ridex.example.com`, path `/api` go to the Service `gateway`; `/` goes to `web`".
* An **ingress controller** is the *program* that reads those rules and does the routing (ingress-nginx, Traefik, a cloud load balancer). An Ingress with no controller does nothing. Installing one is a one-time job per cluster.

```
browser ─► localhost:8088 ─► [kind node] ─► ingress-nginx ─┬─ /api, /ws ─► Service gateway ─► gateway pods
                                                           └─ /         ─► Service web     ─► web pods
```

* The gateway of Phase 8 sits behind the controller: it learns the real client from `X-Forwarded-For`, which the controller sets, and believes it only because the connection comes from a trusted private address (`GATEWAY_TRUSTED_PROXIES`).
* TLS (HTTPS) terminates at the Ingress: the `tls` section names a Secret with the certificate; in production **cert-manager** requests and renews certificates automatically.
* The host `ridex.localhost` resolves to your own machine in browsers and recent curl, so no hosts-file edit is needed locally.

### Network policies
By default every pod can reach every pod. A **NetworkPolicy** is a firewall rule between pods. Ours start from **deny everything incoming** and then allow exactly what RideX needs: only the ingress controller reaches the gateway and website; only the gateway reaches the services; only the five services reach PostgreSQL, and so on. A compromised pod then cannot freely scan the database. *Policies are enforced only if the cluster's network plugin implements them*; otherwise they are silently ignored. Test it, do not assume (the review shows how).

---

## 6. Step 10.5: stateful components

A Deployment treats its pods as **cattle**: identical, interchangeable, replaceable, with empty disks. That is perfect for a stateless service and wrong for a database, which needs:

1. **A stable identity.** `postgres-0` must stay `postgres-0` after a restart, so other things can find it and so it keeps its disk.
2. **Its own durable disk** that follows it.
3. **Ordered start and stop** (for clusters: the primary before the replicas).

A **StatefulSet** provides exactly that:

| Object | Role |
|---|---|
| **StatefulSet** | Like a Deployment, but pods get stable names (`postgres-0`, `postgres-1`) and are started/stopped in order |
| **Headless Service** | Gives each pod its own DNS name (`kafka-0.kafka.ridex-dev.svc.cluster.local`) |
| **PersistentVolumeClaim (PVC)** | A *request* for disk: "2 GiB, one node at a time" |
| **PersistentVolume (PV)** | The real disk that satisfies it; made automatically by the cluster's **StorageClass** |
| `volumeClaimTemplates` | The StatefulSet makes one PVC per pod. Deleting the pod keeps the PVC; the new `postgres-0` re-attaches to the same data |

PVC vs PV, in one sentence: the PVC is what the application *asks for*, the PV is what the cluster *provides*; the pod only ever mentions the claim, so the same file works on kind, a cloud or a data centre.

### Why databases are not "just pods"
A pod that restarts keeps its disk now. What the StatefulSet does **not** give you: backups, point-in-time recovery, replication, automatic failover, safe major-version upgrades, tuned storage. That is the job of a **managed database** (RDS, Cloud SQL) or an **operator** (CloudNativePG...), which encode that knowledge. Single-pod StatefulSets are fine for development; production replaces them. The production overlay says so.

### The core is stateful too
It keeps the token signing key and uploaded driver documents on disk. A `ReadWriteOnce` volume attaches to one node at a time, so a rolling update (new pod starts while the old still runs) can deadlock on another node. The core therefore uses `Recreate` (a short outage on update) and stays at 1 replica. The real fix is design, not Kubernetes: documents in object storage, the key in a Secret. It is the same lesson as Phase 7 ("what shares a disk shares a fate").

---

## 7. Step 10.6: probes and rolling updates

### Three probes, three different questions
| Probe | Question | If it fails | Ours |
|---|---|---|---|
| **Startup** | Has it finished starting? | After many tries: restart. **Until it passes, the other two do not run** | `/actuator/health/liveness`, up to 4 minutes (Spring Boot is slow to start) |
| **Liveness** | Is it **stuck** (deadlock, out of memory loop)? | The container is **restarted** | `/actuator/health/liveness` |
| **Readiness** | Can it take traffic **now**? | The pod is taken **out of the Service** (not restarted) | `/actuator/health/readiness` |

The distinction matters. A slow start killed by an impatient liveness probe never starts (a restart loop); a database blip that fails *liveness* restarts every pod at once and turns a small problem into an outage.

**What the probes check:** Spring's liveness/readiness probes report **the application's own state only, never the database or Kafka**. If readiness depended on PostgreSQL, a database hiccup would pull *every* pod out of its Service simultaneously: the gateway would have no backend, and the outage would spread. This is the cascading failure of Phase 7 again. Dependencies are handled by retries, circuit breakers and the health of those dependencies themselves.

### Rolling update, step by step
`strategy: RollingUpdate, maxSurge: 1, maxUnavailable: 0`, with 2 replicas:

```
start:      [old][old]
surge:      [old][old][new]        a 3rd pod starts (surge 1); not in the Service yet
ready:      [old][old][new ✓]      readiness passes → it joins the Service
replace:    [old][new ✓]           one old pod is removed
repeat:     [new ✓][new ✓]
```
Capacity never drops below 2 ready pods: `maxUnavailable: 0` forbids removing an old pod before a new one is ready. **This is why the readiness probe is the heart of zero-downtime deployment.** If the new version is broken, it never becomes ready, the old pods stay, and the update stalls instead of causing an outage; `kubectl rollout undo` goes back.

### Stopping a pod without dropping requests
Deleting a pod starts two things *at the same time*: "remove it from the Service endpoints" and "send SIGTERM". Proxies learn about the removal a moment *later*, so requests can still arrive at a pod that has begun to shut down. We close the gap three ways:
1. `preStop: sleep 8`: a pause **before** SIGTERM, while the removal propagates.
2. `server.shutdown=graceful` (Spring): after SIGTERM, finish requests in progress (up to 25 s) and refuse new ones.
3. `terminationGracePeriodSeconds: 40` ≥ 8 + 25: Kubernetes does not kill the pod (SIGKILL) before it finished.

### Other things that make updates safe
* **PodDisruptionBudget**: how many pods may be down at once during *voluntary* disruption (node drain). 
* **Resource requests and limits.** The *request* is what the scheduler reserves ("this pod needs 250 Mi") and decides which node fits; the *limit* is the ceiling (above it the container is killed). We set a memory limit, and deliberately no CPU limit (CPU limits throttle and add latency).
* **Autoscaling (HPA)** in the production overlay: more pods when CPU is high. Needs the metrics-server add-on.

---

## 8. From Docker Compose to Kubernetes

| Docker Compose (Phase 9) | Kubernetes (Phase 10) |
|---|---|
| `services:` entry | Deployment (or StatefulSet) + Service |
| `image:` | `image:` (the same images) |
| `environment:` | ConfigMap + `envFrom`, or `env` |
| `secrets:` (files) | Secret mounted as files |
| `volumes:` | PersistentVolumeClaim (+ StorageClass) |
| `ports:` published on 127.0.0.1 | Ingress (and `port-forward` for debugging) |
| `networks:` edge / internal | Namespaces + NetworkPolicies |
| `depends_on: service_healthy` | Nothing: pods retry until dependencies are ready; **readiness probes** keep traffic away from pods that are not |
| `healthcheck:` | startup / liveness / readiness probes |
| `mem_limit` | `resources.limits.memory` (+ `requests`) |
| `restart: unless-stopped` | Always, by the ReplicaSet controller |
| `read_only`, `cap_drop`, `no-new-privileges`, `USER` | `securityContext` (same ideas, plus seccomp) |
| `docker compose up --build core` | build and push an image, change the tag, `kubectl apply` (rolling update) |

Note the row for `depends_on`: Kubernetes has **no startup ordering between services**. Your application must tolerate a dependency that is not up yet (retry), which Phase 7's resilience work already does.

---

## 9. Vocabulary

| Term | Meaning |
|---|---|
| Desired state / reconciliation | What you declared / the loops that make reality match it |
| Control plane / node / kubelet | The brain / a worker machine / its agent |
| Pod | One or more containers sharing a network address; disposable |
| ReplicaSet / Deployment | Keeps N pods / manages ReplicaSets across updates |
| StatefulSet | Pods with stable identity and their own disks |
| Service (ClusterIP / headless) | Stable address and DNS name for a set of pods |
| Label / selector | Key-value tags / how objects pick each other |
| ConfigMap / Secret | Non-secret / secret configuration |
| Namespace | A named partition of the cluster |
| Ingress / ingress controller | Routing rules / the program that applies them |
| PV / PVC / StorageClass | A disk / a request for a disk / how disks are made |
| Probe (startup, liveness, readiness) | Health questions the kubelet asks |
| Rolling update | Replacing pods gradually, keeping capacity |
| PodDisruptionBudget | Limit on voluntary simultaneous downtime |
| HPA | Horizontal Pod Autoscaler |
| Kustomize / overlay | Patch-based configuration / the environment-specific patch |
| CNI | The network plugin; decides whether NetworkPolicies are enforced |
