# AGENTS.md — link-hoarder

> "Simple is the opposite of complex. Easy is the opposite of hard."
> — Rich Hickey

## What This Is

A Clojure CLI tool that scrapes metadata and links from markdown show notes, then pushes them to Fireside (podcast hosting platform). Used in Jupiter Broadcasting podcast production.

## Philosophy: Grumpy Pragmatism

We write **situated programs** that are reliable, robust, and data-driven. We reject OOP nonsense in favor of simple, composable systems. Follow the philosophy of grumpy senior developers who've been burned by complexity.

### Simple Over Easy (Hickey)

- **Complection is the enemy** - Don't twist things together
- Separate concerns: data, behavior, state, identity, time
- Choose simple constructs even when they're unfamiliar
- Easy now often means painful later

### Actions, Calculations, Data (Normand)

Keep these distinct:

- **Actions** - Functions with side effects (HTTP calls, I/O)
- **Calculations** - Pure functions, deterministic, testable
- **Data** - Maps, vectors, primitives - just data

### Functional Core, Imperative Shell

- Core business logic: pure functions (calculations)
- Shell: handles side effects, orchestration, I/O
- Push impurity to the edges
- Test the core thoroughly, shell sparingly

### YAGNI (You Aren't Gonna Need It)

- Don't build for imagined futures
- Solve today's problem, make change easy
- Abstractions are a cost, not a benefit
- "What if we need..." is usually wrong

### Data-Driven, Not Class-Driven

- Maps over objects
- Functions over methods
- Transformations over mutations
- Composition over inheritance
- "Just use a map" is often the right answer

## Your Defaults

**Language:** Plain Clojure with `deps.edn`. Use Babashka only if startup time matters for your specific use case.

**Data:** Plain maps and vectors. EDN for config and internal formats. JSON only at the boundary (HTTP in/out). Don't invent wrapper types when a map with a well-chosen key does the job.

**State:** Atoms for shared mutable state. Refs if you need coordinated transactions (you usually don't).

**I/O:** `hato` for HTTP (already in deps). `clojure.java.io` for file operations.

**Dependencies:** As few as possible. Check what's already available before adding a dep.

**Error handling:** `ex-info` with a data map. Catch at the boundary. Don't swallow exceptions silently. Log the message and the relevant context.

## Project Structure

```
link-hoarder/
├── src/noblepayne/
│   ├── link_hoarder.clj    # Main: markdown parsing, link extraction
│   └── fireside.clj       # Fireside API client
├── test/                  # Tests (aspirational)
│   └── noblepayne/
│       └── link_hoarder_test.clj
├── deps.edn               # Dependencies
├── flake.nix              # Nix + devenv + GraalVM native image
├── GUIDE.md               # Usage guide (update when adding features!)
└── README.md              # High-level overview
```

## Running

```bash
# Enter development shell
nix develop

# Run with clojure
clojure -M -m noblepayne.link-hoarder <url>
```

## Development Workflow

**Always run linting and formatting before committing:**

```bash
# Format Clojure code
nix run nixpkgs#cljfmt -- fix src/

# Check formatting
nix run nixpkgs#cljfmt -- check src/
```

## Testing Philosophy

> "Write tests. Not too many. Mostly integration."

- **Integration tests** verify the system actually works
- **Unit tests** are guardrails for pure functions
- Don't mock what you don't own
- Test behavior, not implementation
- Avoid testing trivial code

Tests live in `test/` directory with `*_test.clj` naming.

### Testing (Aspirational)

We aspire to have real integration tests with test servers:

**Test Infrastructure Pattern:**

```
Test HTTP Server (mock Fireside/docs.lol)
    ↓
link-hoarder (system under test)
    ↓
Verify results
```

**Real Servers Pattern:**

Instead of mocking HTTP calls, spin up real in-process servers:

```clojure
;; Test server - mimics Fireside or docs.lol
(start-test-server)
;; Returns: {:port 12345 :stop fn :received-requests atom}

;; Use with-redefs to point HTTP calls to test server
(with-redefs [hato.client/http/get (fn [url & _] ...)]
  (is (= expected (fetch-markdown url))))
```

**Test Fixture Pattern:**

```clojure
(use-fixtures :once
  (fn [test-run]
    (let [server (start-test-server)]
      (try
        (test-run)
        (finally
          (stop-server server))))))
```

**Key Testing Principles:**

1. **Port 0 allocation** - Let OS assign random free ports
2. **Request tracking** - Test servers store received requests for assertions
3. **Fast startup** - http-kit servers start in <100ms
4. **In-process** - Everything runs in same JVM for easy debugging

**Example Test:**

```clojure
(deftest extract-links-from-markdown-test
  (testing "Parses links from markdown with quotes"
    (let [md "#### Links
+ [Example](https://example.com)
  > This is a quote"
          result (parse-data-from-markdown (fetch-markdown-from-string md))]
      (is (= 1 (count (:links result))))
      (is (= "https://example.com" (get-in result [:links 0 :href])))
      (is (= "This is a quote" (get-in result [:links 0 :quote]))))))
```

**When to write tests:**

- When fixing a bug (write test first)
- When adding complex business logic
- When the cost of failure is high

**When NOT to write tests:**

- Configuration code
- Simple data transformations
- Code that's already covered by integration tests

## Code Style Guidelines

### Clojure Conventions

**Naming:**

- Functions: `kebab-case` (e.g., `extract-link-data`, `fetch-markdown`)
- Constants: `UPPER_SNAKE_CASE` for env vars
- Namespaces: `noblepayne.module-name`
- Private functions: suffix with `-` (e.g., `helper-fn-`)

**Imports:**

```clojure
(ns noblepayne.module
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [hato.client :as http]))
```

- Group: standard lib → third-party → internal
- Always use `:as` aliases
- Prefer `:require` over `:use`

**Formatting:**

- 2-space indentation
- 80-100 character line limit
- Align map values and let bindings
- Trailing newline at EOF

### Error Handling

```clojure
;; Use try/catch with specific exceptions
(try
  (risky-operation)
  (catch Exception e
    (log/error e "Operation failed")
    {:error "friendly message"}))

;; Return consistent error shapes
{:error "description" :details additional-info}
```

### Clojure Coding Standards

**Single file is fine.** If a program fits in one file (<1000 lines), don't split it into namespaces.

**Comments explain WHY not WHAT.** The code shows what. Comments explain tradeoffs, non-obvious choices, and the reasoning behind a decision.

**JSON at the boundary, keywords inside.** Decode with `true` for keyword keys. Never pass JSON strings around internally.

## Nix Commands

```bash
# Enter dev shell
nix develop

# Build native image
nix build

# Format Nix files
nixfmt *.nix

# Update flake inputs
nix flake update
```

## Common Gotchas

### Markdown Parsing

- Links section must start with `### Links` or have id `show-links`
- End section should have `### End Links` or id `end-links`
- Blockquotes after links are captured as quotes
- Links starting with `READ:` are skipped

### Fireside API

- Authentication required (see GUIDE.md)
- `linuxunplugged` uses guid from markdown
- `adfree` requires explicit guid override
- Purging links before adding new ones is needed for updates

### HTTP Client

- `hato` is async by default - use sync variant with `:sync? true` or deref properly
- Handle timeouts gracefully

### Podcasting 2.0 Person Tags

When adding host/guest support, use `<podcast:person>` tags:

**XML Example:**
```xml
<podcast:person role="host" group="cast">John Smith</podcast:person>
<podcast:person role="guest" href="https://example.com/jane">Jane Doe</podcast:person>
```

**Key Points:**

- Can be in `<channel>` (podcast level) or `<item>` (episode level)
- At episode level, `<podcast:person>` **replaces** all channel-level people
- Default role: `host`
- Default group: `cast`
- Attributes: `role`, `group`, `img`, `href`
- Role/group are case-insensitive

**Common Roles (from Podcast Taxonomy):**

| Role | Description |
|------|-------------|
| `host` | On-air master of ceremonies, consistent presence |
| `co-host` | Secondary host, shares duties |
| `guest host` | Temporary host |
| `guest` | Outside party, interview subject |

**Common Groups:**

| Group | Description |
|-------|-------------|
| `cast` | On-air talent |
| `writing` | Writers, editors |
| `audio production` | Engineers, editors |
| `administration` | Coordinators, managers |

See [Podcast Taxonomy Project](https://podcasttaxonomy.com/) for full list.

## Lessons Learned

### When Code Goes Wrong

**Parenthesis Issues:**

- Don't spin for hours fixing unbalanced parens
- After 2-3 attempts, just rewrite the function/file

### Debugging Strategies

**1. Print Debugging (Still Valid):**

```clojure
(println "Debug:" variable)
```

**2. REPL-Driven Development:**

```clojure
;; Load namespace
(require 'noblepayne.link-hoarder :reload)

;; Test function
(noblepayne.link-hoarder/-main "https://example.com")
```

## Commit Message Guidelines

**Format:**

```
<type>: <subject>

<body>
```

**Types:**

- `feat:` - New feature
- `fix:` - Bug fix
- `style:` - Formatting, linting
- `docs:` - Documentation
- `refactor:` - Code restructuring
- `test:` - Adding tests
