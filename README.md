# Nucleus

A banking application.
Nucleus provides account management, customer onboarding, parameter-driven configuration and ledger functionality. Built with Kotlin and Spring Boot, it integrates Postgres, Redis and Kafka to support event-driven workflows and scheduled operations.

## Building

Use the Gradle wrapper to build the project:

```bash
./gradlew build
```

This also runs the test suite and creates `build/libs/nucleus.jar`.

## Running Tests

Ensure the services below are available, then run:

```bash
./gradlew test
```

## Required Services

Nucleus depends on PostgreSQL, Redis and Kafka. The examples below use Docker containers:

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

# Zookeeper for Kafka
docker run --rm -d --network nucleus-net --name zookeeper -p 2181:2181 \
  -e ZOOKEEPER_CLIENT_PORT=2181 \
  confluentinc/cp-zookeeper:8.10.0

# Kafka
docker run --rm -d --network nucleus-net --name kafka -p 9092:9092 \
  -e KAFKA_BROKER_ID=1 \
  -e KAFKA_ZOOKEEPER_CONNECT=zookeeper:2181 \
  -e KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=PLAINTEXT:PLAINTEXT \
  -e KAFKA_LISTENERS=PLAINTEXT://0.0.0.0:9092 \
  -e KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://localhost:9092 \
  -e KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1 \
  -e KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1 \
  -e KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1 \
  confluentinc/cp-kafka:8.10.0
```

With these services running you can start the application locally:

```bash
SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
```

## Kubernetes via Minikube

The `k8s/minikube.yaml` and `skaffold.yaml` files allow running the service on a local Kubernetes cluster.

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

Please read [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines on committing, testing and opening pull requests.
