# Nucleus

[![Build Status](https://app.travis-ci.com/iteratoruk/nucleus.svg?token=Cm8mrHFVrpV3PidDsXeL&branch=main)](https://app.travis-ci.com/iteratoruk/nucleus)

Nucleus is a banking microservice that provides parameter-driven account feature configuration,
with account management, scheduled financial processing, and ledger functionality planned. Built
with Kotlin and Spring Boot 3, it integrates PostgreSQL, Redis, and Kafka to support
event-driven workflows and scheduled operations.

## Architecture

Domain models and Architecture Decision Records are in `docs/architecture/`. Read these before
implementing features — they record decisions and rationale that are not derivable from the code
alone.

## Building

Use the Gradle wrapper to build the project:

```bash
./gradlew build
```

This runs the full test suite and produces `build/libs/nucleus.jar`. Tests use Testcontainers
and require no locally running services.

To run tests without building the jar:

```bash
./gradlew test
```

## Static Analysis

```bash
./gradlew detekt          # lint
./gradlew spotlessCheck   # formatting
./gradlew spotlessApply   # auto-fix formatting
```

All three must pass before opening a pull request.

## Running Locally

Start PostgreSQL, Redis, and Kafka using Docker:

```bash
# Network for the containers
docker network create nucleus-net

# PostgreSQL
docker run --rm -d --network nucleus-net --name postgres \
  -p 5432:5432 \
  -e POSTGRES_USER=nucleus \
  -e POSTGRES_PASSWORD=password \
  -e POSTGRES_DB=nucleus \
  postgres:17.5

# Redis
docker run --rm -d --network nucleus-net --name redis -p 6379:6379 redis:8.0

# Kafka (KRaft mode — no Zookeeper required)
docker run --rm -d --network nucleus-net --name kafka -p 9092:9092 \
  -e KAFKA_NODE_ID=1 \
  -e KAFKA_PROCESS_ROLES=broker,controller \
  -e KAFKA_CONTROLLER_QUORUM_VOTERS=1@localhost:29093 \
  -e KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER \
  -e KAFKA_LISTENERS=PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:29093 \
  -e KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://localhost:9092 \
  -e KAFKA_INTER_BROKER_LISTENER_NAME=PLAINTEXT \
  -e KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1 \
  -e KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1 \
  -e KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1 \
  -e CLUSTER_ID=MkQkRjc0NzQwNTJENDM2Qk= \
  confluentinc/cp-kafka:8.10.0
```

Then start the application:

```bash
SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
```

## Kubernetes via Minikube

The `k8s/minikube.yaml` and `skaffold.yaml` files allow running the service on a local
Kubernetes cluster.

Start Minikube and deploy using Skaffold:

```bash
minikube start
skaffold dev
```

Alternatively you can apply the manifests manually:

```bash
minikube start
kubectl apply -f k8s/minikube.yaml
```

## Contributing

Please read [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines on committing, testing, and
opening pull requests.