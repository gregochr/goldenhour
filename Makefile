.PHONY: dev stop backend

dev: ## Start infrastructure and run the backend
	docker-compose up -d
	@echo "Waiting for PostgreSQL to be ready..."
	@until docker-compose exec -T postgres pg_isready -U sunset -d goldenhour > /dev/null 2>&1; do \
		sleep 1; \
	done
	@echo "PostgreSQL is ready."
	cd backend && ./mvnw spring-boot:run

stop: ## Stop all Docker containers
	docker-compose down

backend: ## Run the backend only (assumes Docker is already running)
	cd backend && ./mvnw spring-boot:run
