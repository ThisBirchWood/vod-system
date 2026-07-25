# VoD System

A self-hosted clip management and live streaming platform. Upload videos, trim and compress clips, save sections of a live stream, and organise everything through a clean web interface — all running locally under Docker.

This project started in secondary school during lockdown as a Python script to squeeze game clips under Discord's 8 MB file size limit. It has since grown into a full-stack application with a Spring Boot backend, a React/Vite/TypeScript frontend, and an nginx-rtmp sidecar for live streaming.

---

## Tech Stack

| Layer      | Technology                                              |
|------------|---------------------------------------------------------|
| Backend    | Java 21, Spring Boot 3.4, Spring Security, JPA, FFmpeg |
| Frontend   | React 19, TypeScript, Vite, Tailwind CSS v4            |
| Database   | PostgreSQL 15                                           |
| Streaming  | nginx-rtmp                                              |
| Auth       | Google OAuth 2.0 + JWT                                  |
| Runtime    | Docker Compose                                          |

---

## Requirements

- Docker and Docker Compose
- A Google Cloud project with OAuth 2.0 credentials (Client ID and Secret)

That is all. The backend, frontend, database, and RTMP server all run as containers — no local JDK, Node, or FFmpeg install required.

---

## Getting Started

### 1. Configure the environment

Copy the example file and fill in the values:

```bash
cp .env.example .env
```

Open `.env` and set the following:

```dotenv
# OAuth credentials from your Google Cloud Console project
GOOGLE_CLIENT_ID=
GOOGLE_CLIENT_SECRET=

# A long random string used to sign JWTs — treat it like a password
JWT_SECRET_KEY=

# Absolute paths on the host where persistent data will be stored
POSTGRES_DATA=/path/to/postgres/data
STREAM_DATA=/path/to/stream/data
MEDIA_DATA=/path/to/media/data

# Database credentials — change these from the defaults before deploying
POSTGRES_URL=jdbc:postgresql://postgres:5432/vodSystem
POSTGRES_USER=myuser
POSTGRES_PASSWORD=mypassword
POSTGRES_DB=vodSystem

# The URLs the frontend will use to reach itself and the backend
FRONTEND_URL=http://localhost:5173
BACKEND_URL=http://localhost:8080
```

### 2. Start the application

```bash
docker compose up --build
```

This builds and starts four containers: `vod_postgres`, `vod_backend`, `vod_frontend`, and `vod_rtmp`.

Once everything has started, the interfaces are available at:

- Frontend: [http://localhost:5173](http://localhost:5173)
- Backend API: [http://localhost:8080/api/v1](http://localhost:8080/api/v1)
- RTMP ingest: `rtmp://localhost:1935/live`

### 3. Authenticate

Log in through the frontend using your Google account. The backend exchanges the Google ID token for a short-lived JWT that is used on all subsequent API requests.

---

## Development Mode

For local backend or frontend development, a separate Compose file is provided that starts only the infrastructure services (PostgreSQL and the RTMP server), leaving the backend and frontend to run directly on the host:

```bash
docker compose -f docker-compose.dev.yml up
```

Then run the backend and frontend separately in your usual workflow.

- **Important**: Rootless docker should be used to avoid permission conflicts between the RTMP container and the Spring Boot host

---

## API Reference

A [Bruno](https://www.usebruno.com/) collection is included under `bruno/VoD-System/` with pre-configured requests covering all major endpoints. Import it directly into Bruno and point the `local` environment at `http://localhost:8080/api/v1`.

The collection is organised into four folders:

| Folder   | Description                                      |
|----------|--------------------------------------------------|
| Users    | Google OAuth login flow, current user            |
| Media    | Upload, compress, clip stream, save section      |
| Jobs     | Poll job status, download completed output       |
| Streams  | Stream history                                   |

---

## Future Plans

- **Format handling** — backend conversion of non-MP4 files via FFmpeg for broader input format support
- **Input sources** — YouTube imports and additional local upload options
- **Testing** — unit and integration test coverage, including FFmpeg-related functionality
- **API documentation** — comprehensive, auto-generated backend API docs
