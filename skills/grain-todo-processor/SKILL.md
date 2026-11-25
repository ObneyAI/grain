---
name: grain-todo-processor
description: Implement an event-driven Grain processor and choose its checkpoint, effect and replay behaviour.
---

# Grain todo processors

Use `defprocessor` from `ai.obney.grain.todo-processor-v2.interface`. Subscribe
with `{:topics #{:area/event}}`. The handler receives the stored `:event` and
tenant context. Define each follow-up event type with [`defevent`](../grain-event/SKILL.md).
Return `{:result/events [...]}` to append those events with
the checkpoint, or `{}` to acknowledge the input.

For external work, return a zero-argument `:result/effect`. Set `:result/checkpoint`
according to which failure the operation can tolerate.

- `:after` runs the effect, then commits `:result/on-success` events with the
  checkpoint. A crash between the two can repeat the effect. Use a stable
  idempotency key. A thrown effect can be checkpointed with `:result/on-failure`
  events, so represent any required retry explicitly.
- `:before` commits the checkpoint first. A crash can leave the effect undone.

A new processor can replay historical triggers. To start after a known event,
initialize its checkpoint per tenant before any node polls that processor.
[Checkpoint initialization](../../docs/core-concepts.md#start-a-new-processor-after-a-known-event)
