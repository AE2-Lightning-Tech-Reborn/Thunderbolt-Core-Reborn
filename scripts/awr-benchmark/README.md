# AWR → Thunderbolt v2 benchmark

This portable entry point runs the current Thunderbolt checkout against `.awr` v1–v3 data and saved benchmark manifests. It imports the selected trusted `awrlib.py` and calls `Graph(path).prepare()`; it does not duplicate AW's parser or normalization. No Minecraft process or native CP-SAT runtime starts. The default and only engine in this runner is `tb-v2`.

## Run

Requirements: Python 3.10+, JDK 21 (`JAVA_HOME` or `--java-home`), and the normal Thunderbolt Gradle build dependencies. Supply an AW checkout or an explicit trusted reader, datasets, and existing `<dataset>.{meta,targets,stock.*}.tsv` manifests. The reader must support the supplied AWR version. The runner validates each manifest's `awr_sha256` and recorded prepared counts; it does not generate a different target sample.

From the Thunderbolt checkout:

```sh
export JAVA_HOME=/path/to/jdk-21
python3 scripts/awr-benchmark/run.py \
  --aw-root /path/to/AW \
  --datasets recipes-vanilla,recipes-small \
  --warmup 1 --repeats 3 --time-limit 40
```

AWR files default to `AW_ROOT/temp`; manifests default to `AW_ROOT/bench/plans`. Override their locations with `--data-dir /path/to/datasets` and `--plan-dir /path/to/plans`. `--out-dir` selects the output directory; the default is `build/awr-benchmark/<timestamp>`. All three saved stock groups (`none`, `leaves`, `random20`) run in each pass. Warmup rows use `type: "warmup"`, a negative `repeat`, and `warmup: true`, so AW's summarizer excludes them. Measured rows use `type: "query"`, `stage: "-"`, and repeats starting at 0.

Use `--awrlib /path/to/awrlib.py` to select an independent reader, including an updated reader supplied with v2/v3 data. This overrides the reader under `--aw-root`; it never silently falls back to the old reader. `--aw-root` becomes optional when `--awrlib`, `--data-dir`, and `--plan-dir` are all supplied. The selected reader is executed as Python code and must be trusted; its absolute path and SHA-256 are recorded even when it is outside the AW checkout.

The command normalizes each graph once, asks Gradle for the current main runtime **and** compile classpaths, builds the checkout, compiles `AwrBench.java`, and starts one JVM per dataset. Commands are printed. No machine-specific classpaths, downloaded benchmark implementations, or hard-coded source revisions are used. The per-query deadline is cooperative, applies to planning, and is separate from import and replay; JVM flags are `-Xmx3g -Xss16m`. Other planner settings retain the checkout's defaults and any inherited `thunderbolt.*` system properties are listed in the header.

## Recipe outputs, costs, tags, and target stock

The adapter retains every positive byproduct from v2/v3 and the prepared recipe's v3 execution cost. A real recipe cost must be in `1..Integer.MAX_VALUE`; larger costs and malformed or unsupported output/effect metadata are rejected rather than truncated. With an older v1 reader that has no `cost` or `byproducts` fields, real cost defaults to 1 and extra outputs default to empty. A current reader supplies the corresponding defaults for v1/v2 itself.

AW indexes every output of a physical recipe as a producer. To preserve that behavior, TB receives one anchor projection per output, with the same complete inputs, the same cost, and all remaining outputs as `CraftOutput` byproducts. All projections share one source object identifying the original prepared recipe. Each selected projection firing is a complete physical execution. The report **sums** projection counts by the original recipe ID; it never deduplicates counts or grants extra output without paying for another execution. Independent validation and replay use the original full output list and these aggregated counts.

An AWR recipe is a tag conversion only when its **prepared output ID is at or above `n_real`**. The adapter verifies that every such recipe produces exactly one tag unit from exactly one real member unit, has cost 0, no workstation, and no byproducts or additional effects; otherwise it rejects the dataset. All ordinary recipes—including genuine 1:1 recipes—retain their positive cost. Real byproducts must refer to real items, and output IDs must be unique within one prepared recipe. The imported edges use the explicit APIs:

```java
CraftPattern<Integer> tag = CraftPattern.tagConversion(memberId, tagId, preparedRecipeId);
CraftPattern<Integer> real = CraftPattern.weighted(outputId, outputAmount,
        inputs, byproducts, preparedRecipeSource, executionCost);
// Existing ordinary constructors still default to cost 1.
BigInteger realExecutions = plan.executionCount();
```

Tag edges have execution cost 0 inside TB's objective. They remain in `plan.firings()`, material balances, and replay; their separate total is `tag_exec`. The runner does not flatten tags or enumerate member combinations, so a tag demand can use a mixture of real members. Inputs are validated as positive signed-long quantities with unique item IDs. AWR's workstation list is recipe registry metadata, not an additional consumed input or modeled machine capacity. Formats with extra effects require an explicit adapter and are rejected here.

The default `--target-stock additional` follows AW's **additional production** convention. The complete inventory remains available, including initial target stock, and TB receives:

```java
long effectiveAmount = Math.addExact(requestedAmount, stock.getOrDefault(target, 0L));
```

Balance checks and replay require `effectiveAmount`; rows report both the original `amount` and `effective_amount`. Existing target stock can still serve as an intermediate ingredient. Overflow produces an error row rather than silently changing the request. `--target-stock available` uses TB's ordinary contract: existing target stock may directly satisfy the requested amount. Do not compare these two modes as if their tasks were identical.

## Output and interpretation

`<dataset>.tb-v2.jsonl` starts with a configuration header containing the TB/AW commits and working-tree status, SHA-256 hashes of the AWR file, metadata, target/stock manifests, normalized graph, AW normalizer, runner sources, and the TB Java/build source tree, plus counts, stock convention, JVM version, timing configuration, and planner property overrides. The normalized input and configuration are saved under `<out-dir>/<dataset>/`; the original manifests are identified by path and hash.

Each query includes planning time, feasibility, missing material, used stock, planner diagnostics, all `[recipe_id, count]` firings, and an independent balance check. `cost` is the exact weighted sum of physical execution counts times each prepared recipe's cost, measured in **underlying Minecraft recipe executions**. `recipe_exec` counts physical prepared-recipe executions without weights and without tag transfers. This matches the older AW harness's unweighted `cost` definition; compare that old field with `recipe_exec`, not TB's weighted `cost`, when v3 costs exceed 1. `tag_exec` counts the separate zero-cost transfers. `cost_consistent` checks the independently reconstructed weighted value against `plan.executionCount()`.

JSON item handles are **1-based**, matching AW manifests; recipe IDs are **0-based in `Graph.prepare()` order**, including recipes that now have multiple TB anchor projections. The versioned binary intermediate uses 0-based item IDs, stores each original recipe once with all outputs and its cost, and retains the prepared order. The Java reader also accepts the earlier unversioned single-output/cost-1 intermediate. The header reports physical recipe and projected pattern counts separately.

The independent validator first checks that every stock draw is nonnegative and within the original inventory, and that every firing uses an original graph projection with a positive count and the correct physical source identity. Replay then starts with only `used_stock`, consumes each enabled physical recipe's inputs once, produces every output once, and checks the final effective target. `pass` is a concrete order witness. `stuck_inconclusive` or `replay_budget` does not prove the plan impossible; another order may work. Invalid stock, firings, weighted cost, or a proved final balance failure mark the row `invalid` and `feasible: false`; `planner_feasible` retains the planner's original declaration. Replay time is separate from `plan_ms`; query rows also contain the `timeout` boolean and `target_name` expected by AW's summarizer. No global optimality claim is made: use the actual costs, feasibility, replay status, and `budget_exhausted` when comparing with AW results from the same normalized inputs and stock convention.

The adapter preserves prepared multi-output material columns, execution costs, tags, and target-stock semantics for this entry point. It does not alter AW's existing benchmark scripts or its engine settings, and it does not establish cross-engine agreement on every cyclic graph or replenishment policy.

## Small regression checks

`python3 scripts/awr-benchmark/test_adapter.py --awrlib /path/to/awrlib.py` checks actual v1/v2/v3 miniature files, old-reader defaults, weighted multi-output serialization, and rejection of malformed tags, outputs, and costs. It does not start Java or Gradle.

`TestAwrBench.java` is a standalone Java check compiled alongside `AwrBench.java` with the runner's generated classpath. Its main class is `com.moakiee.thunderbolt.core.crafting.planner.TestAwrBench`. It verifies secondary-output discovery, reuse of both joint outputs, shared source identity, summed anchor counts, weighted costs, explicit tags, legacy binary input, and replay with seeds and zero-input recipes.
