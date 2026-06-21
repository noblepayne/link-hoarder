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

**Safety Protocol: Destructive Git Commands:**

- NEVER use `git checkout -- file` to "fix" syntax errors. It discards ALL uncommitted work.
- Use VSCode Local History (`~/.config/Code/User/History`) or LSP undo features instead.
- If you mess up, check `RECOVERY_SOURCE.clj` patterns or LSP backups first.

### Agent + REPL Interaction Pattern

#### The Core Problem

In Clojure, there are two sources of truth:

1. **Files on disk** - Permanent, but can't execute
2. **REPL namespace** - Can execute, but ephemeral

When an agent connects to a running REPL, it sees whatever the human has loaded—not necessarily what's in the files.

#### Recommended Workflow

**Step 1: Fresh Agent Session**

```
1. Read source files from disk (use glob + read)
2. Find running nREPL (use list-nrepl-ports)
3. Connect and inspect what's loaded
```

**Step 2: Understand What's There**

```clojure
;; Check what data is already loaded
(if-let [v (resolve 'noblepayne.link-hoarder/data)]
  @v
  "not found")

;; List all vars in a namespace
(keys (ns-publics 'noblepayne.link-hoarder))
```

**Step 3: Decide Mode**

- **REPL-first**: Use clojure_eval to test/verify, then persist with file_edit
- **File-first**: Edit files, then human must reload in REPL

#### Comment Blocks as Scratch Space

Source files contain `(comment ...)` blocks at the bottom. These are evaluated when loaded into REPL, creating persistent vars for exploration:

```clojure
;; From link_hoarder.clj comment block:
(def data
  (-main
   "https://h.docs.lol/6qhYpqFBQiOc5vdJE70wjg?both#"))

;; After evaluation, available as:
noblepayne.link-hoarder/data
```

**Pattern:**

1. Human loads namespace (comment block runs)
2. Agent connects, finds `data` var
3. Agent can read `@(resolve 'noblepayne.link-hoarder/data)`

#### Key Commands for Agent

```clojure
;; Find nREPL port
clojure-dev_list_nrepl_ports

;; Evaluate in running REPL
clojure-dev_clojure_eval {:code "(+ 1 2)" :port 36739}

;; Reload namespace (for file changes)
(require 'noblepayne.link-hoarder :reload)

;; Check what's in namespace
(ns-publics 'noblepayne.link-hoarder)
(ns-map 'noblepayne.link-hoarder)
```

#### Sync Strategy

When agent edits a file:

1. Edit the file (file_edit or clojure_edit)
2. Tell human: "Please reload the namespace"
3. Human runs: `(require 'noblepayne.link-hoarder :reload)`
4. Agent verifies: re-evaluate to confirm

#### Debugging Strategies

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

## Link Cleanup Workflow (Episode Publishing)

This workflow is used to prepare links from show notes for publication (e.g., episode 662 "The GitHub Diet").

### The Process

1. **Load data** - Connect to nREPL, find the data var (usually `noblepayne.link-hoarder/data`)
2. **Initial cleanup** - Simplify long titles, add "Pick:" prefix to last 3 links
3. **Quote extraction** - Fetch pages, extract meaningful descriptions
4. **Polish** - Capitalize, fix grammar, use semicolons instead of em-dashes
5. **Preview** - Generate HTML preview for approval

### Tools for Quote Extraction

**Primary tool: `searxng_read_url`**
- Converts pages to clean markdown
- Good for project homepages, docs, tutorials

**For technical content: `websearch` + `searxng_read_url`**
- Search for context on complex topics (e.g., "Btrfs remap-tree Linux 7.0")
- Read the LWN article or Reddit discussion for better quotes

**For raw HTML: `searxng_http_request`**
- Use when searxng markdown conversion fails
- Check for embedded meta descriptions

**Last resort: Chrome tools**
- Use only if searxng fails completely
- Good for interactive pages or JavaScript-rendered content

### What Makes a Good Quote

- **From the project itself** - Forgejo's own description tells the story best
- **Technical accuracy** - Btrfs "translation layer" is more informative than "new feature"
- **Explains the "why"** - "Liberate your software from proprietary shackles"
- **Avoid marketing fluff** - Skip generic "revolutionary" type language

### Quote Style Guide

- Capitalize first letter
- Use semicolons to separate clauses (e.g., "Self-hosted alternative to GitHub; liberate your software...")
- Keep it concise but descriptive
- No em-dashes unless necessary
- Use `<quote>` to indicate when quoting directly from source

### REPL Workflow for Updates

```clojure
;; Update links in memory
(def updated-links
  (mapv (fn [link]
          (case (:href link)
            "https://example.com"
            (assoc link :quote "New quote here")
            link))
        (:links noblepayne.link-hoarder/data)))

(def data (assoc data :links updated-links))

;; Push to namespace var
(alter-var-root #'noblepayne.link-hoarder/data (fn [_] data))

;; Generate preview
(noblepayne.link-hoarder/save-preview data)
```

### Common Patterns

**Linux kernel features** (phoronix):
- Search for technical details if title is vague
- LWN.net often has excellent technical explanations
- Quote the key innovation, not just the feature name

**Self-hosted software** (Forgejo, opengist):
- Use the project's own tagline
- Explain what problem it solves

**Picks** (last 3 links):
- Already have "Pick:" prefix
- Add quotes that explain why it's interesting
- Keep short and punchy

### What We Learned

- It's okay to be "maximalist" with quotes - more context is better than less
- One round of polish isn't enough; three passes: initial → dig deeper → polish
- The REPL is the source of truth during editing, not the file
- `save-preview` generates the approved preview file

### HedgeDoc Sync Workflow

We ARE the scraper - our data comes FROM HedgeDoc. When we sync back, we're sending our polished corrections BACK to the source.

#### HedgeDoc Structure

- **Links scattered throughout** - not in a dedicated section
- **Various formats** - `### [title](url)` for section headers, `[title](url)` inline, `- [title](url)` in lists
- **Quotes use `>` blockquotes** - our scraper grabs lines starting with `>` after each link
- **Talking points use `+`** - these are for the show, not scraped as quotes
- **Our data is already deduped** - duplicates in HedgeDoc are filtered out when scraping

#### Sync Specification (Action/Calculation/Data Pattern)

```clojure
;; DATA (Immutable)
(def our-data {:links [{:href "..." :title "..." :quote "..."} ...]})
(def hedge-doc-state {"url" -> {:line 123 :title "..." :hasQuote true/false :quote "..."}})

;; CALCULATIONS (pure - no side effects)
(defn normalize-url [url] "Strip tracking params")
(defn url-match [url hedge-index] "Find by URL")
(defn decide-update [our-link hedge-match]
  ;; :add - no quote in hedge, add ours
  ;; :skip-protect - has quote, different (preserve existing)
  ;; :skip-identical - has quote, same
  ;; :skip-new - not in hedge (need to add))
(defn proposed-change [our-link hedge-match decision]
  "Returns what WOULD change - for dry run audit")

;; ACTIONS (side effects)
(defn audit-sync [our-data hedge-index] "Dry run - returns audit report")
(defn execute-sync! [approved-changes] "Real sync after human approval")
```

#### Decision Rules

| HedgeDoc Has `>` Quote? | Matches Ours? | Action |
|-----------------------|---------------|--------|
| ✓ | ✓ | SKIP (already good) |
| ✓ | ✗ | PROTECT (preserve existing) |
| ✗ | - | ADD (our polished quote) |

#### Dry Run Audit Format

```
PROTECT: https://sfconservancy.org/GiveUpGitHub/ → "We realize this..." (line 321)
ADD:    https://www.phoronix.com/news/Linux-7.0-Btrfs-Changes → "A translation layer..." (line 281)
SKIP:   https://github.com/thereisnotime/sshroute (already matches)
```

#### Finding Links in HedgeDoc

The chrome skill doesn't work on CodeMirror (virtual rendering). Use JavaScript evaluation:

```javascript
chrome_evaluate_script {
  function: "() => (...)"
}
```

See `hedgedoc-editor` skill for CodeMirror API details.

#### Common Gotchas in Sync

1. **URL format variations** - trailing slash, http vs https, query params → normalize before matching
2. **Multiple occurrences** - update first only, dedupe after in our data
3. **List format** - links in `- [title](url)` format still match
4. **Don't overwrite existing good quotes** - our pipeline filters those
5. **Reverse Order Processing**: When syncing back to HedgeDoc, ALWAYS process updates in reverse order (bottom-to-top) to avoid line number shifts.

### Reflections from Episode 662 ("The GitHub Diet")

**Quote extraction is harder than it looks:**
- searxng often strips to just metadata - not enough detail
- websearch finds better context (Reddit, LWN discussions)
- Raw HTTP fetching gives you the HTML to parse yourself
- Sometimes you just have to write it from understanding

**Linux 7.0 articles on Phoronix:**
- Titles are feature headlines, not explanations
- Need websearch for the "why" behind the feature
- LWN.net has the best technical deep-dives
- Example: "remap-tree" is a "translation layer of logical block addresses"

**Forgejo ecosystem:**
- Main site has good marketing copy ("liberate your software from proprietary shackles")
- Wiki NixOS page is more technical ("fork of Gitea")
- Codeberg issues/PRs tell the story of features
- Federation = "enabling decentralized software development"

**Quote philosophy:**
- From the project itself when possible
- Explain what problem it solves, not just what it is
- Semi-colons better than em-dashes for flow
- Maximalist is fine - listeners can skip if they want

**The Disaster Recovery Lesson:**
- We lost uncommitted work due to a `git checkout` error.
- **Root Cause**: Attempting to fix syntax errors by reverting the file instead of fixing the parens.
- **Recovery**: VSCode's local history saved us. We found a high-fidelity recovery point in `~/.config/Code/User/History`.
- **New Mantra**: "Commit early, commit often, and never use checkout to fix a bracket."

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
