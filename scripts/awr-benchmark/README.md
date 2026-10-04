# AWR → Thunderbolt v2 benchmark

This portable entry point runs the current Thunderbolt checkout against an AW checkout's original `.awr` data and saved benchmark manifests. It imports that checkout's `bench/awrlib.py` and calls `Graph(path).prepare()`; it does not duplicate AW's parser or normalization. No Minecraft process or native CP-SAT runtime starts. The default and only engine in this runner is `tb-v2`.

## Run

Requirements: Python 3.10+, JDK 21 (`JAVA_HOME` or `--java-home`), and the normal Thunderbolt Gradle build dependencies. Supply an AW checkout with `bench/awrlib.py`, datasets, and existing `bench/plans/<dataset>.{meta,targets,stock.*}.tsv` manifests. The runner validates each manifest's `awr_sha256` and recorded prepared counts; it does not generate a different target sample.

From the Thunderbolt checkout:

```sh
export JAVA_HOME=/path/to/jdk-21
python3 scripts/awr-benchmark/run.py \
  --aw-root /path/to/AW \
  --datasets recipes-vanilla,recipes-small \
  --warmup 1 --repeats 3 --time-limit 40
```

AWR files default to `AW_ROOT/temp`; manifests default to `AW_ROOT/bench/plans`. Override their locations with `--data-dir /path/to/datasets` and `--plan-dir /path/to/plans`. `--out-dir` selects the output directory; the default is `build/awr-benchmark/<timestamp>`. All three saved stock groups (`none`, `leaves`, `random20`) run in each pass. Warmup rows use `type: "warmup"`, a negative `repeat`, and `warmup: true`, so AW's summarizer excludes them. Measured rows use `type: "query"`, `stage: "-"`, and repeats starting at 0.

The command normalizes each graph once, asks Gradle for the current main runtime **and** compile classpaths, builds the checkout, compiles `AwrBench.java`, and starts one JVM per dataset. Commands are printed. No machine-specific classpaths, downloaded benchmark implementations, or hard-coded source revisions are used. The per-query deadline is cooperative, applies to planning, and is separate from import and replay; JVM flags are `-Xmx3g -Xss16m`. Other planner settings retain the checkout's defaults and any inherited `thunderbolt.*` system properties are listed in the header.

## Tag conversion and target-stock contract

An AWR recipe is a tag conversion only when its **prepared output ID is at or above `n_real`**. The adapter verifies that every such recipe produces exactly one tag unit from exactly one real member unit, has no workstation or additional effect metadata, and otherwise rejects the dataset. All ordinary recipes—including genuine 1:1 recipes—retain cost 1. The imported synthetic edge uses the explicit API:

```java
CraftPattern<Integer> tag = CraftPattern.tagConversion(memberId, tagId, preparedRecipeId);
// Ordinary recipe constructors still charge one execution per firing.
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

Each query includes planning time, feasibility, missing material, used stock, planner diagnostics, the exact real execution `cost`, `tag_exec`, all `[recipe_id, count]` firings, and an independent balance check. JSON item handles are **1-based**, matching AW manifests; recipe IDs are **0-based in `Graph.prepare()` order**. The binary intermediate uses 0-based item IDs and retains the same recipe order.

The independent validator first checks that every stock draw is nonnegative and within the original inventory, and that every firing uses an original graph recipe with a positive count. Replay then starts with only `used_stock`, executes enabled recipe batches, and checks the final effective target. `pass` is a concrete order witness. `stuck_inconclusive` or `replay_budget` does not prove the plan impossible; another order may work. Invalid stock, firings, or a proved final balance failure mark the row `invalid` and `feasible: false`; `planner_feasible` retains the planner's original declaration. Replay time is separate from `plan_ms`; query rows also contain the `timeout` boolean and `target_name` expected by AW's summarizer. No global optimality claim is made: use the actual `cost`, feasibility, replay status, and `budget_exhausted` when comparing with AW results from the same normalized inputs and stock convention.

The adapter fixes tag execution accounting and target-stock alignment for this entry point. It does not alter AW's existing benchmark scripts or its engine settings, and it does not establish cross-engine agreement on every cyclic graph or replenishment policy.
