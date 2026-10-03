# Alpha planner backport to Forge 1.20.1

Source: `alpha` at `0297f2d`; destination baseline: `1.20.1` at `2fcb92e`.
Changes are grouped by functionality and retain the existing Java 17, Forge 47.1.3
and AE2 15.4.10 platform adaptations.

## Producibility-ranked cycle orientation

Port the cycle-orientation portion of `948c4c1`:

- Rank reachable recipe inputs by producibility and retain a producible direction
  inside each strongly connected component when ordinary cycle cuts fail.
- Try both stock-seeded and stock-free rankings within the shared search budget.
- Scale the default search-work budget with the reachable-graph limit so increasing
  that limit also permits the necessary orientation retries.
- Include the upstream regression graph whose valid route was lost by DFS cuts.

The Forge-specific replenishment certificates, Java 17 verification allowances,
bootstrap-reserve reuse and optional optimization guard remain intact in this batch.

Validation: Java 17 / Forge 47.1.3 / AE2 15.4.10 planner suite,
679 tests passed with no failures or skips.
