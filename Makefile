# VideoBridge — developer commands. Needs Node LTS and JDK 17+; Docker only for local services.
COMPOSE_DEV  := docker compose -f infra/docker-compose.yml
COMPOSE_TEST := docker compose -f infra/docker-compose.test.yml

.PHONY: dev services test-services down migrate test test-backend test-android lint lint-backend lint-android android openapi

dev: ## backend in watch mode against the MongoDB and Redis named in backend/.env
	cd backend && npm run prisma:migrate && npm run start:dev

services: ## optional: local MongoDB + Redis in Docker instead of Atlas
	$(COMPOSE_DEV) up -d --wait

down:
	$(COMPOSE_DEV) down
	$(COMPOSE_TEST) down

migrate: ## MongoDB has no migration files; this syncs collections and indexes to the schema
	cd backend && npm run prisma:migrate

test: test-backend test-android

test-backend: ## e2e needs the test database from backend/.env.test, or `make test-services`
	cd backend && npm test && npm run test:e2e

test-services:
	$(COMPOSE_TEST) up -d --wait

test-android:
	cd android && ./gradlew testDevDebugUnitTest

lint: lint-backend lint-android

lint-backend:
	cd backend && npm run lint && npm run format:check && npm run typecheck

lint-android:
	cd android && ./gradlew spotlessCheck detekt lintDevDebug

android:
	cd android && ./gradlew :app-phone:assembleDevDebug :app-tv:assembleDevDebug

openapi:
	cd backend && npm run openapi:export
