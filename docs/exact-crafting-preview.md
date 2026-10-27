# Exact V2 crafting previews

V2 retains its existing executable `long` path. When the calculation reaches the
saturating arithmetic boundary, or the AE2 byte estimate exceeds `long`, it produces
a separate BigInteger preview. Recipe multipliers, output scaling, durability stock,
aggregate requirements, batch ceilings, missing amounts and preview byte accounting
retain exact values. Infinite capacity is separate from a finite quantity.

The wide sweep reuses V2's normalized dependency graph, cycle cuts and byproduct
ordering. It is an exact quantity calculation for a retained diagnostic route, not
an extension of the bounded integer-flow solver to arbitrary precision. Multi-route
and seed-ordered/cyclic cases carry an incomplete-diagnosis flag; their missing list
is not a proof that every alternative is infeasible. This distinction is shown in
the Tianshu byte tooltip. Normal-sized jobs continue through the original solver.

The AE2 projection always has `simulation=true` and no executable pattern counts.
The original requested output is retained, including for CRAFT_LESS probes; an
overflow preview must not silently become a smaller executable order. The server
confirmation menu and crafting service also reject the preview explicitly.

Exact amounts are attached by weak identity to the plan and its summary. A tagged
extension follows the complete native confirmation packet, after other mods'
summary extensions. Native summary fields are clamped compatibility data only.
Both the AE2 and Tianshu material tables read the exact attachment. Compact values
use decimal arithmetic; tooltips show full quantities, scaled by AEKey units.

AE2's calculation input remains `long` even though its native amount menu uses
`int`. `CraftingRequestMenu.thunderbolt$planRequest` bridges this UI limit through
the existing confirmation hook: every positive amount through `Long.MAX_VALUE`
still reaches the normal selected planner and CPU admission. Crossing
`Integer.MAX_VALUE` alone never requests an exact backend.

An addon can register `ExactCraftingBackend` for an existing algorithm with
`CraftingPlanningEngines.registerExactBackend`. Requests above `long`, and genuine
intermediate-overflow previews, can use it only when that algorithm is the first
resolved choice and the backend reports online execution capability. Registering
a backend does not select its algorithm. A preferred vanilla or other algorithm
is never skipped. Without an eligible backend, intermediate-overflow results stay
non-executable previews; an explicit over-long request is rejected.

The same TB confirmation hook owns exact planning, algorithm tracking, CPU
filtering, summary conversion, replan and Back. Exact plans implement
`ExactCraftingPlan`; an extended input implements `CraftingAmountMenu` to restore
the full amount on Back. Addons supply graph capture/execution rather than adding
another confirmation-menu mixin. AE2LT registers Tianshu support: its extended
input also requires an online Tianshu CPU and selection of V2, and exact execution
requires the multidimensional tier. Ordinary long requests retain ordinary CPU
matching. Input transport and backend selection are separate decisions.

TB itself does not implement addon execution, persistence or storage I/O.
Representation is bounded to 32768 bits per amount; exact report decoding also
bounds entry count and payload size. Resource-budget failures must not become
rounded executable plans.

Validation covers normal-order exponential demand, coefficients above `long`,
batch rounding, stock above the old saturation cap, shared DAGs, catalysts,
finite-use tools, byproducts, cycle diagnostics, exact amount wire encoding,
display formatting, non-executable plan export and ordinary executable plans.
