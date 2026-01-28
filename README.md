# Agent Intergraph

The Distributed Agent Node for the MoM Intergraph ecosystem.

## Overview
This project implements a standalone Agent that communicates with the MoM (Mind of Minds) server over HTTP. 

## Features
- **Event Listener**: Listens for Intergraph events on `POST /v1/event`.
- **Action Submission**: Submits "Moves" (Actions) back to MoM for settlement.
- **Lifecycle Management**: Can be started/stopped remotely by MoM.

## Running the Agent
```bash
lein start-test-agent
```
