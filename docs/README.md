# docs/

Deep-dive documentation for the Openfire Federation plugin — design notes plus walkthroughs of the real
code. The [top-level README](../README.md) is the install/usage entry point; these go a layer deeper.

| Doc | Covers |
|-----|--------|
| [security.md](security.md) | Full trust model: the threat model, every control from the README's [Security](../README.md#security) table spelled out with its enforcement point and upgrade behavior, and the `SECURITY:` log tags. |
| [routing.md](routing.md) | Distance-vector (Bellman-Ford) overlay routing walked through the real `FederationRoutingTable` code — gossip merge, split-horizon, stale-route withdrawal, peer-down purge. |
| [room-mapping.md](room-mapping.md) | Room mapping & multi-hop MUC forwarding — the `muc-forward` hop decision, injection past MUC's non-occupant check, hub fan-out, and ghost-free teardown (`FederationIQHandler`/`FederationManager`). |
| [file-federation.md](file-federation.md) | Transparent HTTP-Upload (XEP-0363) file relay — annotate + stage at the origin, rewrite + pull at the destination, park-and-serve hubs, transit hops that never keep a copy (`FileRelayManager`). |

Each doc keeps the same shape: design context up top, annotated snippets from the actual source below.
