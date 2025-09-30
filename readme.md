# Grain

## What is this?

Grain is an “AI-native” framework for building Event-Sourced systems where Agentic Workflows are part of the domain model and not an afterthought.
If you’ve ever struggled to bolt an agent framework onto an existing system, Grain gives you a coherent architecture where agents and events share the same backbone.


## Why did you make it?

At ObneyAI, we use [Event Modeling and Event Sourcing](https://leanpub.com/eventmodeling-and-eventsourcing) to design [Simple](https://www.youtube.com/watch?v=SxdOUGdseq4) information systems and applications for our customers. We believe in investing in our tooling, because we always want to deliver the next project faster, but with a high degree of confidence in the platforms we deliver. Being in the emerging AI space, it's our job to wade through all the uncertainty and attempt to skate to where we think the puck is heading. Grain combines proven ideas from conventional software architecture with modern agent workflows, giving us (and you) a single, composable toolkit for building the next generation of AI-driven applications.

## Does it have an Agent Framework?

We have taken a look at the landscape of Agent Frameworks and determined two things:

1. There are better ways to go about it than what most existing Agent Frameworks are doing.
2. Agent Frameworks are not rocket science, they are largely just orchestration engines, so it's ok to make your own if you have a good reason.

Grain’s Agent Framework integrates directly with your Event-Sourced domain. Grain Agents are built with:

- [Fully declarative Behavior Trees](https://arxiv.org/abs/2404.07439)
- High leverage integration with [DSPY](https://dspy.ai/)
- Short-term program memory
- Long-term, event-sourced memory e.g. projections over your domain events.

This means your agents can reason over the same source of truth as the rest of your system.

A few demos are available [here](https://github.com/ObneyAI/macroexpand-2-demo)

## Can I use it?

Yes, you can use it. Grain is MIT licensed software. We use Grain in production for our clients, but that doesn't mean it's perfect or ready in every way. Large portions of Grain are relatively stable at this point, such as the core CQRS / Event Sourcing components, but other aspects may change rapidly, such as the components that make up the Agent Framework.

Using Grain feels like snapping Lego bricks together, each component is independent but plays nicely with the rest. Start with an in-memory event store for quick iteration, then swap in Postgres with a single line change when you’re ready.

One promise we can make is that we will never break your software, we enhance existing components routinely, but we avoid making breaking changes that violate existing contracts between components. If a change we want to make is revolutionary enough, we will introduce a new version of a component, that way consumers of existing versions aren't negatively impacted.

Our choice to deliver Grain as a simple system of cooperating components using [Polylith](https://polylith.gitbook.io/polylith) allows us this flexibility in addition to the ability to mix and match components in new and interesting ways to publish standalone tools as independent Polylith Projects from a single repository.

## How do I use it?

It may be a while before we have extensive documentation.

There are 2 main ways you can use Grain:

1. You can use Grain as a library if you don't foresee yourself needing to adjust any aspects of the components and framework to what you intend to build.

2. You could clone this repository and build your application directly within the framework, all of the pieces and parts would then be available for you to tweak and refine. The downside, of course, is that you would make it harder to take advantage of updates to Grain, but sometimes this level of control is a necessity.

We've tried to give a decent demonstration of how to build an application with Grain in the base in `bases/example-base` and the component in `components/example-service`. Additionally, see `development/src/example_app_demo.clj` for a demo of how to start and interact with the example system.

### Authentication / Authorization

At this time we leave these two aspects to the user, but this shouldn't be much of an issue, as the user has the option of composing their own routes together when using the webserver component or even using their own preferred webserver.

### Can I just use the Agent Framework?

Sure you can. Grain really shines when you drink the Event Sourcing koolaid and model your entire domain as an Event Sourced system, but it's totally possible to just use Grain's Event Sourced, Behavior-Tree Agent Framework on its own and integrate it into your more conventional application systems.

## Available Packages

Polylith projects are how we publish various aspects of Grain such that you can include them in your deps.edn file and pull them in as dependencies. 

Here is what we currently offer:

| Package | Summary |
| --- | --- |
| **grain-core** | CQRS/Event Sourcing utilities with in-memory backend + Behavior Tree engine. |
| **grain-event-store-postgres-v2** | Protocol-driven Postgres backend — swap in/out with a config change. |
| **grain-dspy-extensions** | DSPy integration + re-usable BT node for LLM workflows. |
| **grain-mulog-aws-cloudwatch-emf-publisher** | Mulog publisher for AWS CloudWatch metrics & dashboards. |



### grain-core

This is the core set of utilities that can power what you see in the example application in this repo. It's everything you need to build an application that follows CQRS / Event Sourcing principles. It comes with an in-memory backend for the Event Store component for getting started quickly. The Event Sourced Behavior Tree Engine is included in this package.

```clojure
obneyai/grain-core
{:git/url "https://github.com/ObneyAI/grain.git"
 :sha "fc7302311f88afb3eebbcb4d68d2046a91b00ad5"
 :deps/root "projects/grain-core"}
```

### grain-event-store-postgres-v2

This is a Postgres backend for the Event Store component, pull it in and require the `ai.obney.grain.event-store-postgres-v2.interface` namespace to load its multimethod implementation. This will allow you to simply switch between `:in-memory` and `:postgres` with a one line change with no other code changes required. Our event-store-v2 component is protocol-driven and presents a consistent API to callers, backends are expected to implement the spec. You can even implement your own backend!

```clojure
obneyai/grain-event-store-postgres-v2
{:git/url "https://github.com/ObneyAI/grain.git"
 :sha "fc7302311f88afb3eebbcb4d68d2046a91b00ad5"
 :deps/root "projects/grain-event-store-postgres-v2"}
```

### grain-dspy-extensions

This is where the magic of Grain's Agent Framework happens. [DSPY](https://dspy.ai/) is a best in class Python library from Stanford for working with LLMs in sophisticated ways. This package includes our `clj-dspy` component and a re-useable `dspy` Behavior Tree action node for orchestrating reliable Agentic Workflows. You'll just have to see some examples to experience the magic, it's difficult to explain how cool it is. However, you do take a dependency on Python when you use these tools, so you will want to set up a `python.edn` file in the root of your project directory with the following content: `{:python-executable ".venv/bin/python"}`. Then you will need to create a python virtual environment called `.venv` in the root of your directory using a tool of your choice. We like [uv](https://docs.astral.sh/uv/). You'll need to install at least Python `3.12`.

We think the dependency on Python is pretty neat! Python is really in the spotlight these days thanks to a lot of applied AI tooling being heavily Python based. It's fantastic that a Clojure application can combine the best of both the JVM and Python in order to create an innovative product that is more than the sum of its parts.

```clojure
obneyai/grain-dspy-extensions
{:git/url "https://github.com/ObneyAI/grain.git"
 :sha "fc7302311f88afb3eebbcb4d68d2046a91b00ad5"
 :deps/root "projects/grain-dspy-extensions"}
```

### grain-mulog-aws-cloudwatch-emf-publisher

Grain uses [mulog](https://github.com/BrunoBonacci/mulog) for logging and tracing. This is good for you, because it means if you have a preferred logging solution, all you have to do is implement a custom mulog publisher, intercept Grain's logs, and translate them into your own logging solution. This package is a custom publisher that we use to enable automatic creation of CloudWatch metrics in AWS for Dashboards, Alerting, and other observability use-cases.

```clojure
obneyai/grain-mulog-aws-cloudwatch-emf-publisher
{:git/url "https://github.com/ObneyAI/grain.git"
 :sha "fc7302311f88afb3eebbcb4d68d2046a91b00ad5"
 :deps/root "projects/grain-mulog-aws-cloudwatch-emf-publisher"}
```

## What's next?

- Comprehensive Documentation
- More examples

## Contact Us

If you have questions or want help getting started, then feel free to come find us in the Clojurian Slack in the [#grain](https://clojurians.slack.com/archives/C099K3D7XRV) channel.

If you have feedback or find bugs or problems, feel free to create a github issue.