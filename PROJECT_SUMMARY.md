# Prayer App (When Danes Prayed in German) - Project Summary

## Overview
The Prayer App is a sophisticated digital humanities project that creates an interactive website ([bedebog.dk](https://bedebog.dk)) for exploring digitized historical prayer books. The system indexes a directory of TEI (Text Encoding Initiative) XML documents representing old prayer books, making them searchable through a custom query language and providing features like document pinning and customizable text display.

## Architecture
The application is architected as a **single-page app (SPA)** with client-side routing. The backend serves the same skeleton HTML page for all routes while providing a REST API that serves transit-encoded EDN data. The frontend caches this data in localStorage and handles all navigation client-side.

### Core Components
- **Backend**: Clojure web service using Pedestal
- **Frontend**: ClojureScript SPA using Replicant for HTML generation
- **Database**: Datalevin (read-only, ephemeral, rebuilt on boot)
- **Search**: Custom EBNF grammar parsed by Instaparse
- **Deployment**: Docker containers with Caddy reverse proxy

## Key File Structure

### Configuration Files
- `deps.edn` - Clojure dependencies and project configuration
- `shadow-cljs.edn` - Frontend build configuration
- `package.json` - NPM dependencies (mainly shadow-cljs)
- `build.clj` - Build automation for uberjar creation

### Core Namespaces
- `src/dk/cst/prayer.clj` - Main entry point and system bootstrap
- `src/dk/cst/prayer/db.clj` - Database creation, search logic, and TEI parsing
- `src/dk/cst/prayer/search.cljc` - Search query language parser (shared)
- `src/dk/cst/prayer/web.cljc` - Shared web utilities and routes
- `src/dk/cst/prayer/tei.clj` - TEI document processing
- `src/dk/cst/prayer/static.cljc` - Static data and constants

### Backend Web Layer
- `src/dk/cst/prayer/web/backend.clj` - Pedestal service configuration
- `src/dk/cst/prayer/web/backend/html.clj` - HTML page generation
- `src/dk/cst/prayer/web/backend/interceptor.clj` - Custom interceptors

### Frontend Layer
- `src/dk/cst/prayer/web/frontend.cljs` - Main frontend entry point
- `src/dk/cst/prayer/web/frontend/api.cljs` - API client functions
- `src/dk/cst/prayer/web/frontend/state.cljs` - Application state management
- `src/dk/cst/prayer/web/frontend/html.cljs` - HTML/UI components
- `src/dk/cst/prayer/web/frontend/event.cljs` - Event handling
- `src/dk/cst/prayer/web/frontend/error.cljs` - Error handling

### Resources
- `resources/search.ebnf` - Search query language grammar
- `resources/schema/` - TEI schema files (XSD)
- `resources/public/` - Static web assets (CSS, fonts, images, JS)

### Development & Deployment
- `dev/src/user.clj` - Development REPL utilities
- `docker/` - Docker configuration and Compose files
- `system/prayer.service` - Systemd service configuration

## Dependencies and Versions

### Core Clojure Dependencies
- **Clojure 1.12.1** - Main language runtime
- **Datalevin 0.9.22** - Datalog database for document indexing
- **Pedestal 0.8.0-beta-1** - Web service framework (service, route, jetty)
- **Reitit 0.7.2** - Frontend routing
- **Transito 2021.07.04** - Transit encoding/decoding

### Logging & Monitoring
- **Telemere 1.0.1** - Universal logging library
- **Telemere SLF4J 1.0.1** - SLF4J integration
- **SLF4J API 2.0.17** - Logging facade

### Text Processing & Parsing
- **Instaparse 1.5.0** - EBNF grammar parsing for search queries
- **Huff 0.2.12** - Text processing utilities

### Custom Libraries (Git Dependencies)
- **dk.cst/xml-hiccup** - TEI XML to Hiccup conversion
- **dk.cst/hiccup-tools** - Hiccup manipulation utilities

### Frontend Dependencies
- **Shadow-cljs 3.1.7** - ClojureScript build tool
- **Replicant 2025.03.27** - React-free HTML generation
- **Lambdaisland/fetch 1.5.83** - HTTP client for ClojureScript

## Available APIs and Functions

### Search API
The system provides a sophisticated search interface through several key functions:

```clojure
;; Main search entry point
(dk.cst.prayer.db/search db query)

;; Query parsing and AST generation
(dk.cst.prayer.search/query->ast "field:value AND other:term")

;; Database operations
(dk.cst.prayer.db/build-db! db-path files-path)
(dk.cst.prayer.db/top-items db)
```

### Backend API Endpoints
The backend serves transit-encoded EDN data at these endpoints:
- Search results
- Document metadata
- Manuscript information
- Text content

### Frontend State Management
State is managed through a centralized state atom with event-driven updates:

```clojure
;; State management (frontend)
(dk.cst.prayer.web.frontend.state/update-state! update-fn)
(dk.cst.prayer.web.frontend.event/handle-event event-type data)
```

## Data Model

The system models three primary entities based on TEI standards:

### Manuscript
A physical object containing one or more texts
- **TEI mapping**: `msIdentifier` tag contains identification info
- **Database**: Stored with manuscript-specific metadata

### Text
An instance of a work located within a manuscript  
- **TEI mapping**: `msItem` tag within `msContents`
- **Database**: Links manuscripts to works

### Work
A class of text instances (the abstract work)
- **TEI mapping**: Referenced via `xml:id` or `key` attributes
- **Database**: Canonical work definitions

## Search Query Language

The system implements a custom search language with EBNF grammar supporting:
- **Field searches**: `field:value` or `field=value`
- **Boolean operators**: `AND`, `OR`, `NOT` (also `&`, `|`, `!`)
- **Phrase searches**: `"exact phrase"`
- **Grouping**: `(term1 OR term2) AND field:value`
- **Negation**: `NOT field:value` or `!term`

## Development Workflow

### REPL Development
1. Start REPL with `clojure -M:nrepl:jvm-base`
2. Navigate to `dk.cst.prayer` namespace comment block
3. Evaluate system startup code
4. Requires TEI files at path specified in `dk.cst.prayer.db/files-path`

### Frontend Development
```bash
# Start shadow-cljs hot reload server
npx shadow-cljs watch app
# Access at localhost:9876
```

### Database Setup
The database is ephemeral and rebuilt on system boot from TEI files in the configured directory.

### Dependency Management
```bash
# Update dependencies using antq
clojure -Tantq outdated :check-clojure-tools true :upgrade true
```

## Production Deployment

### Docker Setup
The production system uses Docker Compose with two containers:
1. **Application container**: Runs the Clojure system
2. **Caddy container**: Reverse proxy with SSL termination

Required environment variables:
- `PRAYER_APP_FILES_DIR`: Path to TEI files directory

### Systemd Integration
```bash
# Install and enable service
cp system/prayer.service /etc/systemd/system/
systemctl enable prayer

# Management commands
systemctl restart prayer
systemctl status prayer
```

## Implementation Patterns

### Shared Code (.cljc)
Several namespaces use `.cljc` extension for code shared between frontend and backend:
- Route definitions
- Data transformations
- Search query parsing
- Static constants

### Reader Conditionals
Platform-specific code uses reader conditionals:
```clojure
#?(:clj (server-only-code)
   :cljs (client-only-code))
```

### Transit Encoding
All API communication uses transit-encoded EDN for efficient data transfer between backend and frontend.

### Component Architecture
The frontend uses a component-based architecture with Replicant instead of React, providing:
- Reactive DOM updates
- Component lifecycle management
- Event handling system

## LLM Code Style Preferences

### Conditionals
- Use `if` for single condition checks, not `cond`
- Only use `cond` for multiple condition branches
- Prefer `if-let` and `when-let` for binding and testing a value in one step
- Consider `when` for conditionals with single result and no else branch
- Consider `cond->` and `cond->>` for conditional threading

### Variable Binding
- Minimize code points by avoiding unnecessary `let` bindings for simple values
- However, do use `let` bindings to keep more complex expressions from disrupting the application logic
- Use threading macros (`->`, `->>`, etc.) to eliminate intermediate bindings
- Prefer transducers for threading macros with significantly more steps than usual

### Parameters & Destructuring
- Use destructuring in function parameters when accessing keys in map
- Also use destructuring in let bindings when accessing keys in map
- Example: `[{:keys [zloc match-form] :as ctx}]` for regular keywords

### Control Flow
- Track actual values instead of boolean flags where possible
- Use early returns with `when` rather than deeply nested conditionals
- Return `nil` for "not found" conditions rather than objects with boolean flags (i.e. nil punning)

### Comments
- Use comments sparingly unless they are docstrings or explain the purpose of a larger section of code
- Docstrings should use basic markdown formatting, e.g. `x` and `y` might indicate function params
- Docstrings should generally have 1-2 lines explaining what the function returns and which params it takes
- Other optional parts of the docstring should appear as a separate paragraph of text separated by newlines

### Nesting
- Minimize nesting levels by using proper control flow constructs
- Use threading macros (`->`, `->>`) for sequential operations

### Function Design
- Functions should generally do one thing
- Pure functions preferred over functions with side effects
- Functions with side effects should be appended by `!`
- Return useful values that can be used by callers
- Smaller functions make edits faster and reduce the number of tokens
- Reducing tokens makes me happy

### Library Preferences
- Prefer `clojure.string` functions over Java interop for string operations
  - Use `str/ends-with?` instead of `.endsWith`
  - Use `str/starts-with?` instead of `.startsWith`
  - Use `str/includes?` instead of `.contains`
  - Use `str/blank?` instead of checking `.isEmpty` or `.trim`
- Follow Clojure naming conventions (predicates end with `?`)
- Favor built-in Clojure functions that are more expressive and idiomatic

### REPL Best Practices
- Always reload namespaces with `:reload` flag: `(require '[namespace] :reload)`
- Always change into namespaces that you are working on

### Testing Best Practices
- Always reload namespaces before running tests with `:reload` flag: `(require '[namespace] :reload)`
- Test both normal execution paths and error conditions

### Using Shell Commands
- Prefer the idiomatic `clojure.java.shell/sh` for executing shell commands
- Always handle potential errors from shell command execution
- Use explicit working directory for relative paths: `(shell/sh "cmd" :dir "/path")`
- For testing builds and tasks, run `clojure -X:test` instead of running tests piecemeal
- When capturing shell output, remember it may be truncated for very large outputs
- Consider using shell commands for tasks that have mature CLI tools like diffing or git operations

### Context Maintenance
- Use `clojure_eval` with `:reload` to ensure you're working with the latest code
- Always switch into `(in-ns ...)` the namespace that you are working on
- Keep function and namespace references fully qualified when crossing namespace boundaries

## Extension Points

### Adding New Search Fields
1. Extend EBNF grammar in `resources/search.ebnf`
2. Update field mappings in `dk.cst.prayer.db/field-by-label`
3. Add corresponding database attributes

### TEI Schema Extensions
1. Modify schema files in `resources/schema/`
2. Update parsing logic in `dk.cst.prayer.tei`
3. Extend database schema in `dk.cst.prayer.db`

### Frontend Features
1. Add new components in `dk.cst.prayer.web.frontend.html`
2. Extend state management in `dk.cst.prayer.web.frontend.state`
3. Add routes in `dk.cst.prayer.web/frontend-routes`

### API Endpoints
1. Add interceptors in `dk.cst.prayer.web.backend.interceptor`
2. Extend route definitions in `dk.cst.prayer.web.backend`

## Performance Considerations

### Database
- Read-only database optimized for search operations
- Full-text search capabilities via Datalevin
- Ephemeral nature allows for clean rebuilds

### Frontend Caching
- localStorage used for API response caching
- Client-side routing eliminates server round-trips
- Lazy loading of large document content

### Build Optimization
- Shadow-cljs provides advanced compilation
- Module hash names for cache busting
- Separate artifact builds for development and production
