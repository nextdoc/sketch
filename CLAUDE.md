# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Sketch is a Clojure library for modeling distributed systems before implementation. It generates Malli registries from system models, creates visual sequence diagrams, and validates data flow between actors and state stores.

## Common Commands

### Development Server
```bash
# Start ClojureScript development server with hot reload (port 8000)
clj -M:cljs-diagram -m shadow.cljs.devtools.cli watch diagram
```

### Testing
```bash
# Run all tests (includes examples directory)
clj -M:test-cli

# Run tests with specific pattern
clj -M:test-cli --focus-meta :integration
```

### Documentation
```bash
# Generate HTML documentation from Markdown
bb generate
```

### Build
```bash
# Build ClojureScript for production
clj -M:cljs-diagram -m shadow.cljs.devtools.cli release diagram
```

## Architecture

### Core Components

- **`src/io/nextdoc/sketch/core.clj`**: Schema generation engine and model validation
- **`src/io/nextdoc/sketch/state.clj`**: State store protocols (StateDatabase, StateAssociative)  
- **`src/io/nextdoc/sketch/run.clj`**: Test execution engine with step orchestration
- **`src/io/nextdoc/sketch/browser/diagram_app.cljs`**: React/Reagent visualization frontend

### Data Flow

1. **Model Definition**: EDN files define network locations, actors, and message flow
2. **Schema Generation**: `core.clj` generates Malli schemas from models
3. **Test Execution**: `run.clj` orchestrates test steps, validates messages, tracks state changes
4. **Visualization**: Browser app renders Mermaid diagrams and state tables with diff highlighting

### State Management

The library supports two state store patterns:
- **StateDatabase**: Database-like operations (create, read, update, delete, query)
- **StateAssociative**: Key-value store operations (get, assoc, dissoc)

State changes are tracked using editscript diffs and visualized with color-coded modifications.

## Key Technologies

- **Clojure/ClojureScript**: Primary language
- **Malli**: Schema validation and generation
- **Shadow-CLJS**: ClojureScript compilation (builds to `public/diagram-js/`)
- **Reagent**: React wrapper for interactive diagrams
- **Specter**: Data transformation and querying
- **Kaocha**: Testing framework
- **Babashka**: Documentation generation

## Development Workflow

1. Model systems in EDN format with locations, actors, and data schemas
2. Define registry namespaces for schema generation
3. Write test scenarios as step sequences
4. Use browser visualization (localhost:8000) for live diagram updates
5. File watcher in `watcher.clj` provides live model synchronization

## Testing

Tests include both unit tests in `test/` and example implementations in `examples/`. The examples serve as integration tests demonstrating full system modeling workflows.

Kaocha configuration includes randomization, output capture, and documentation reporting for comprehensive test feedback.