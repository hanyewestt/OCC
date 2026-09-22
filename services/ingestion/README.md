# OCC Ingestion Service

## Overview
This service is responsible for polling Bus Arrival predictions and logging them (the "retrospective data" for analytics and ML).

## Requirements
- Java 25
- Docker Desktop with WSL integration enabled
- PostgreSQL database (running locally via Docker Compose at localhost:5432)
- LTA DataMall API Key

## First-time setup

From the repository root in PowerShell:

```powershell
Copy-Item infra\local\.env.example infra\local\.env
docker compose -f infra\local\compose.yaml up -d
```

The database password in `infra/local/.env` is local-only. Do not commit that file.

Set the DataMall key in the same PowerShell window. Keep it out of Git:

```powershell
$env:DATAMALL_API_KEY = "your-real-api-key"
```

## Running from PowerShell

The Gradle wrapper is the recommended option and does not require Gradle to be installed globally:

```powershell
cd services\ingestion
$env:OCC_INGESTION_DB_PASSWORD = ((Get-Content ..\..\infra\local\.env | Where-Object { $_ -like "OCC_INGESTION_DB_PASSWORD=*" }) -split "=", 2)[1].Trim()
.\gradlew.bat bootRun
```

If the API key is stored in `.env`, add this line first:

```env
DATAMALL_API_KEY=your-real-api-key
```

Then load it with:

```powershell
$env:DATAMALL_API_KEY = ((Get-Content ..\..\infra\local\.env | Where-Object { $_ -like "DATAMALL_API_KEY=*" }) -split "=", 2)[1].Trim()
```

Alternatively, open the `services/ingestion` folder in IntelliJ IDEA, set `OCC_INGESTION_DB_PASSWORD` and `DATAMALL_API_KEY` in the `IngestionApplication` run configuration, and run the main class.

On later runs, start the database if needed and run the application again:

```powershell
docker compose -f infra\local\compose.yaml up -d
cd services\ingestion
.\gradlew.bat bootRun
```

## Data Seeding
On startup, if the `poll_target` table is empty, the `InitSeeder` will automatically connect to DataMall's `BusRoutes` endpoint to dynamically discover all stops for routes **190** and **972** and insert them into the target list. 

Every minute, `BusArrivalPoller` fetches the arrivals for these stops and writes to `poll_run` and `raw_observation`. Stop the application with `Ctrl+C`.
