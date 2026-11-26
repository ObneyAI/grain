# Retention for Grain's own events

Of Grain's infrastructure events, only control-plane heartbeats declare bounded
history. Operators activate the policy separately. For the API and rollout
procedure, see [Event definitions and retention](event-definitions-and-retention.md).

| Event | History |
| --- | --- |
| `:grain.control/node-heartbeat` | Once activated, keep at least one hour of history and the latest heartbeat for each node. The active-node projection uses the newest store timestamp to determine liveness. |
| `:grain.control/node-departed`, `:grain.control/lease-acquired`, `:grain.control/lease-released` | Permanent record of node departures and lease changes. |
| `:grain/todo-processor-checkpoint` | Permanent. Catch-up and CAS use the newest checkpoint for each tenant and processor. |
| `:grain/todo-processor-effect-failure` | Permanent failure record. |
| `:grain/tx` | Survives while any member survives. Compaction removes the marker and its metadata when it deletes the last member. Empty-append markers survive. |
| `:grain.retention/policy-activated`, `:grain.retention/policy-deactivated`, `:grain.retention/compacted` | Permanent record of policy changes and deleted event and marker IDs. |

## Sizing a rollout

At the default five-second interval, each continuously running node writes
17,280 heartbeats per day. Each heartbeat append also creates a transaction
marker. Run `retention/estimate` for the system tenant and heartbeat type to
count the eligible events and markers in the next batch.
