# K3s Phone Server - Assets

This directory contains static assets for the simplified K3s Phone Server Android app.

## App Information

This is a simplified Android app that provides:
- GPS location services via `/location` endpoint
- Device orientation data via `/orientation` endpoint
- Simple HTTP API on port 8005

## No AI Models

This simplified version **does not include AI models** for improved:
- Stability and reliability
- Faster startup times
- Smaller app size
- Better battery life

## API Endpoints

- `GET /status` - Server status and health
- `GET /health` - Health check
- `GET /location` - GPS location data
- `GET /orientation` - Device compass/orientation
- `GET /` - API documentation

All endpoints are available on port 8005.
