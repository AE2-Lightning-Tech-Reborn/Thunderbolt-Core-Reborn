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

## Execution-first feasible-plan optimization

Port the final optimizer from `948c4c1` and `17d1ee1` together:

- Prefer fewer machine executions, using stock consumption as the tie-breaker.
  Never increase any incumbent stock draw or change stateful recipe firings.
- Use forward producibility constraints and execution/stock lower bounds to prune
  unhelpful candidates before invoking the planner.
- Propose cheap whole-graph policies, then reroute busy recipes in local regions;
  widen regions and chain substitutes when necessary.
- Charge each probe for its actual reachable region, within the existing shared
  search budget. Preserve completed executable plans on optional exhaustion and
  propagate external cancellation.
- Stop stalled optional search after 500 ms without a 1% execution gain, within
  the existing 2.8-second allowance and enclosing planning deadline.

The former Forge-only `maxConsumptionOptimizationWork` gate and graph-size probe
caps are removed, as requested. A graph with 6,000 unfunded alternatives now
verifies that useful local optimization still runs above the former 16,384-work
gate with only 256 units of search budget. Insufficient budget, stock preservation,
independent material balance, cancellation and incumbent retention are covered.

## Scope and API audit

- The global batch-provider adapters from `7559206` already exist in destination
  baseline `2fcb92e`; they are not reimported.
- Not applicable to official Useless 1.20.1: the Useless/Omni Alloy Furnace
  batch-throttle correction (`0297f2d`) requires the 1.21.1 BigInteger API.
  The inactive Useless bridges and their dedicated tests were removed after the
  2026-10-04 source audit; see `global-batch-provider-adapters.zh-CN.md`.
- Not applicable: `9c95dd4` makes a client injection optional because AE2 19.2.18
  removed `CPUSelectionList.formatStorage`. The actual AE2 15.4.10 sources still
  define that method with the expected descriptor, so its required injection is
  retained. The older branch already hooks `Tooltips.getByteAmount` as well.
- Retain AE2 15's array-based pattern API, Java 17 collections and threads, Forge
  registrations and Mixins, legacy NBT/packet codecs, and the destination's exact
  storage recovery fixes. Neither planner batch imports NeoForge APIs or Java 21
  requirements.

## Final validation

- `gradlew.bat test build` under Java 17, Forge 47.1.3 and AE2 15.4.10:
  897 tests, 896 passed, no failures/errors, one optional ExtendedAE Plus runtime
  probe skipped. Forge remapping and distributable JAR assembly succeeded.
- The final optimizer source matches `alpha` exactly. The remaining differences
  in `CraftPlannerV2` are the retained Forge replenishment/Java 17 allowances and
  bootstrap-reserve reuse.
- Verified Java 17 class version, Forge mod metadata, Mixin configuration/refmap,
  embedded MixinExtras and license in the distributable JAR.
- Optional-mod adapters, Mixins and storage code are unchanged from `2fcb92e`.
- Validation here covers unit/regression tests and the Forge build; no interactive
  client or server gameplay session was launched for this backport.

Artifact: `build/libs/thunderbolt-forge-1.20.1-2.0.0.jar`.
