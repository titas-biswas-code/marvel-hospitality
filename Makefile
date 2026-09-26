# Convenience targets. Each service still builds on its own (cd <service> && ./gradlew build) — ADR-0001.
PLATFORM := platform
SERVICES := room-reservation-service bank-transfer-payment-service credit-card-payment-service notification-service
ENV_FILE := infra/.env
COMPOSE  := docker compose -f infra/docker-compose.yml --env-file $(ENV_FILE)
KEYCLOAK := http://localhost:8180/realms/marvel/protocol/openid-connect/token

# `USER` is also the shell's login name, so only a value given on the command line counts.
TOKEN_USER := $(if $(filter command line,$(origin USER)),$(USER),alice)
PASSWORD   ?= password
CLIENT     ?= bank-simulator

.PHONY: help build-all test-all check-contracts up up-apps down logs reset token client-token replay-dlt

help:
	@echo "build-all | test-all | check-contracts | up | up-apps | down | logs | reset | token USER=alice | client-token CLIENT=bank-simulator | replay-dlt TOPIC=bank-transfer-payment-update [MAX=N] [DRY_RUN=1]"

# The credit-card spec exists twice on purpose: the provider's copy (served by the stub) and the consumer's copy (the
# reservation service generates its client from it). Each service builds from its own file; this keeps them identical.
CC_SPEC_PROVIDER := credit-card-payment-service/src/main/resources/openapi/credit-card-payment-api.yaml
CC_SPEC_CONSUMER := room-reservation-service/src/main/resources/openapi/credit-card-payment-api.yaml

check-contracts:
	@cmp -s $(CC_SPEC_PROVIDER) $(CC_SPEC_CONSUMER) \
	  || { echo "credit-card spec copies differ: diff $(CC_SPEC_PROVIDER) $(CC_SPEC_CONSUMER)" >&2; exit 1; }
	@echo "credit-card spec copies identical"

build-all: check-contracts
	@echo "==> $(PLATFORM)"; (cd $(PLATFORM) && ./gradlew build --console=plain)
	@set -e; for s in $(SERVICES); do echo "==> $$s"; (cd $$s && ./gradlew build --console=plain); done

test-all: check-contracts
	@echo "==> $(PLATFORM)"; (cd $(PLATFORM) && ./gradlew test --console=plain)
	@set -e; for s in $(SERVICES); do echo "==> $$s"; (cd $$s && ./gradlew test --console=plain); done

$(ENV_FILE):
	cp infra/.env.example $(ENV_FILE)

up: $(ENV_FILE)
	$(COMPOSE) up -d --wait

up-apps: $(ENV_FILE)
	$(COMPOSE) --profile apps up -d --wait --build

down: $(ENV_FILE)
	$(COMPOSE) --profile apps down

logs: $(ENV_FILE)
	$(COMPOSE) --profile apps logs -f

# Wipes every volume (Postgres, Kafka, Keycloak) and starts again. Keycloak re-imports
# infra/keycloak/realm/marvel-realm.json only because its database is empty again.
reset: $(ENV_FILE)
	$(COMPOSE) --profile apps down -v --remove-orphans
	$(COMPOSE) up -d --wait

# Access token for a dev user (password grant via the public marvel-postman client).
token:
	@curl -sf -X POST $(KEYCLOAK) -d grant_type=password -d client_id=marvel-postman \
	  -d username=$(TOKEN_USER) -d password=$(PASSWORD) | jq -r .access_token

# Access token for a service-account client (client-credentials grant; secret from infra/.env).
client-token: $(ENV_FILE)
	@secret=$$(grep -E "^$$(echo $(CLIENT) | tr 'a-z-' 'A-Z_')_CLIENT_SECRET=" $(ENV_FILE) | cut -d= -f2-); \
	  curl -sf -X POST $(KEYCLOAK) -d grant_type=client_credentials -d client_id=$(CLIENT) \
	  -d client_secret=$$secret | jq -r .access_token

# Replays dead-lettered records from <topic>.DLT back onto <topic> (ADR-0008's manual DLT tool).
replay-dlt:
	@if [ -z "$(TOPIC)" ]; then echo "usage: make replay-dlt TOPIC=<topic> [MAX=N] [DRY_RUN=1]" >&2; exit 1; fi
	./scripts/replay-dlt.sh $(TOPIC) $(if $(MAX),--max $(MAX)) $(if $(DRY_RUN),--dry-run)
