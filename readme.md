# Grain

An Event-Sourced framework for building AI-native systems in Clojure.

## What is Grain?

Grain is a framework for building Event-Sourced systems using CQRS (Command Query Responsibility Segregation). It provides composable components that snap together like Lego bricks—start with an in-memory event store for quick iteration, then swap in Postgres with a single line change when you're ready.

Grain is also AI-native: agents and events share the same backbone, giving you a coherent architecture where agentic workflows are part of the domain model rather than bolted on as an afterthought.

## Architecture

```
                            ┌────────────────────────────────────────────────────────┐
                            │                      Write Side                        │
                            │                                                        │
  POST /command ───────────▶│  Command Processor ──▶ Validate ──▶ Handler ──▶ Events │
                            │         ▲                  ▲                      │    │
                            └─────────│──────────────────│──────────────────────┼────┘
                                      │                  │                      │
                                      │                  │ read                 │ append
                                      │        ┌─────────┴─────────┐            │
                                      │        │                   │            ▼
            Todo Processors ──────────┘        │    Read Model     │◀───┬───────────┐
                   ▲                           │                   │    │   Event   │
                   │                           └───────────────────┘    │   Store   │
                   │                                     ▲         proj └───────────┘
                   │        ┌────────────────────────────│─────────────┐      │
                   │        │              Read Side     │             │      │ publish
                   │        │                            │             │      ▼
                   │        │  Query Processor ──────────┘             │ ┌─────────┐
  POST /query ─────────────▶│                                          │ │ Pub/Sub │
                   │        └──────────────────────────────────────────┘ └─────────┘
                   │                                                          │
                   └──────────────────────────────────────────────────────────┘
                                                (async)
```

**Commands** are the only path to state change—they validate business rules and emit events. **Events** are immutable facts stored in the event store. **Queries** read from projections (read models) built from events. **Todo Processors** react to events asynchronously, enabling event-driven workflows.

## Core Concepts

### Commands (Write Side)

Commands change state by generating events:

```clojure
(defcommand :example create-counter
  {:authorized? (constantly true)}
  "Creates a new counter."
  [context]
  (let [id (random-uuid)
        name (get-in context [:command :name])]
    {:command-result/events
     [(->event {:type :example/counter-created
                :body {:counter-id id :name name}})]
     :command/result {:counter-id id}}))
```

### Events

Events are immutable facts about what happened:

```clojure
{:event/type :example/counter-created
 :event/id #uuid "..."           ; UUID v7
 :event/timestamp #inst "..."
 :event/body {:counter-id #uuid "..." :name "My Counter"}
 :event/tags #{[:counter #uuid "..."]}}  ; for efficient querying
```

### Queries (Read Side)

Queries read from projections without causing state changes:

```clojure
(defquery :example counters
  {:authorized? (constantly true)}
  "Returns all counters."
  [context]
  {:query/result (read-models/counters context)})
```

### Read Models / Projections

Read models are built by reducing over events:

```clojure
(defn apply-events [events]
  (reduce
    (fn [state {:event/keys [type body]}]
      (case type
        :example/counter-created
        (assoc state (:counter-id body) {:id (:counter-id body)
                                          :name (:name body)
                                          :value 0})
        :example/counter-incremented
        (update-in state [(:counter-id body) :value] inc)
        state))
    {}
    events))
```

## Getting Started

Add to your `deps.edn`:

```clojure
obneyai/grain-core
{:git/url "https://github.com/ObneyAI/grain.git"
 :sha "32a3b154129509c4b01a286a3e1fe7d6e3ad9519"
 :deps/root "projects/grain-core"}
```

See `bases/example-base` and `components/example-service` for a complete example application. Run `development/src/example_app_demo.clj` to start and interact with the example system.

## Available Packages

| Package | Summary |
| --- | --- |
| **grain-core** | CQRS/Event Sourcing + in-memory event store + Behavior Tree engine |
| **grain-event-store-postgres-v2** | Protocol-driven Postgres backend—swap with a config change |
| **grain-dspy-extensions** | DSPy integration for LLM workflows |
| **grain-mulog-aws-cloudwatch-emf-publisher** | AWS CloudWatch metrics & dashboards |

<details>
<summary>Package Details</summary>

### grain-core

Everything you need for CQRS/Event Sourcing with an in-memory event store:

```clojure
obneyai/grain-core
{:git/url "https://github.com/ObneyAI/grain.git"
 :sha "32a3b154129509c4b01a286a3e1fe7d6e3ad9519"
 :deps/root "projects/grain-core"}
```

### grain-event-store-postgres-v2

Postgres backend—require `ai.obney.grain.event-store-postgres-v2.interface` and switch from `:in-memory` to `:postgres`:

```clojure
obneyai/grain-event-store-postgres-v2
{:git/url "https://github.com/ObneyAI/grain.git"
 :sha "32a3b154129509c4b01a286a3e1fe7d6e3ad9519"
 :deps/root "projects/grain-event-store-postgres-v2"}
```

### grain-dspy-extensions

[DSPy](https://dspy.ai/) integration for sophisticated LLM workflows. Requires Python 3.12+ (we recommend [uv](https://docs.astral.sh/uv/) for environment management):

```clojure
obneyai/grain-dspy-extensions
{:git/url "https://github.com/ObneyAI/grain.git"
 :sha "32a3b154129509c4b01a286a3e1fe7d6e3ad9519"
 :deps/root "projects/grain-dspy-extensions"}
```

### grain-mulog-aws-cloudwatch-emf-publisher

[mulog](https://github.com/BrunoBonacci/mulog) publisher for CloudWatch metrics:

```clojure
obneyai/grain-mulog-aws-cloudwatch-emf-publisher
{:git/url "https://github.com/ObneyAI/grain.git"
 :sha "32a3b154129509c4b01a286a3e1fe7d6e3ad9519"
 :deps/root "projects/grain-mulog-aws-cloudwatch-emf-publisher"}
```

</details>

## Agent Framework

Grain includes a behavior tree engine with DSPy integration for building agentic workflows. Agents can reason over the same event-sourced domain as the rest of your system—with short-term program memory and long-term event-sourced memory.

See demos at [macroexpand-2-demo](https://github.com/ObneyAI/macroexpand-2-demo).

You can use the agent framework standalone or skip it entirely if you just want Event Sourcing.

## Why Grain?

We use [Event Modeling and Event Sourcing](https://leanpub.com/eventmodeling-and-eventsourcing) to design [Simple](https://www.youtube.com/watch?v=SxdOUGdseq4) systems. Grain combines proven ideas from conventional software architecture with modern agent workflows, giving us a single, composable toolkit for building AI-driven applications.

[Polylith](https://polylith.gitbook.io/polylith) enables us to evolve components independently and publish standalone tools from a single repository.

## Status

Grain is MIT licensed. We use it in production, but it's actively evolving. The core CQRS/Event Sourcing components are stable; agent-related components may change more rapidly.

## More Information

- **Examples**: `bases/example-base`, `components/example-service`, `development/src/example_app_demo.clj`
- **Slack**: [#grain](https://clojurians.slack.com/archives/C099K3D7XRV) on Clojurians
- **Issues**: [GitHub Issues](https://github.com/ObneyAI/grain/issues)
