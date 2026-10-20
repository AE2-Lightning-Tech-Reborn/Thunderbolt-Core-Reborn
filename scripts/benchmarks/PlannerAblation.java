package com.moakiee.thunderbolt.core.crafting.planner;

import java.lang.reflect.*;
import java.lang.management.ManagementFactory;
import java.math.BigInteger;
import java.util.*;

/** Public-planner ablation: deterministic test fixtures plus seeded generated cases. */
public final class PlannerAblation {
    record Sample(String name, CraftGraph graph, Object target, long amount) {}
    static Object call(String owner, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = Class.forName("com.moakiee.thunderbolt.core.crafting.planner." + owner).getDeclaredMethod(name, types);
        m.setAccessible(true); return m.invoke(null, args);
    }
    static Object field(Object value, String name) throws Exception {
        Method m = value.getClass().getDeclaredMethod(name); m.setAccessible(true); return m.invoke(value);
    }
    static Sample fixture(String name, Object fixture, Object target, long amount) throws Exception {
        return new Sample(name,(CraftGraph)field(fixture,"graph"),target,amount);
    }
    static List<Sample> samples() throws Exception {
        var all = new ArrayList<Sample>();
        for (int id = 0; id < 3000; id++) {
            var s = call("FeasibleConsumptionOptimizerTest","auditSample",new Class[]{int.class},id);
            all.add(fixture("dag-"+id,s,6,(long)field(s,"amount")));
        }
        for (int stock=0; stock<=32; stock++) {
            all.add(fixture("shared-"+stock,call("UpstreamBatchOptimizerAdditionalTest","sharedRaw",new Class[]{long.class},(long)stock),"T",5));
            all.add(fixture("two-"+stock,call("UpstreamBatchOptimizerAdditionalTest","twoOutputs",new Class[]{long.class,int.class},(long)stock,0),"T",5));
        }
        Object wide = call("TerminalFixedDepthOptimizerTest","seed2Sample233",new Class[]{});
        all.add(fixture("terminal-known",wide,"T",(long)field(wide,"amount")));
        for (int seed=0; seed<256; seed++) {
            var random=new Random(51715+seed); var b=CraftGraph.<String>builder().stock("R",5+random.nextInt(30)).stock("S",5+random.nextInt(30));
            b.pattern("T",1,List.of(CraftInput.of("R",1),CraftInput.of("S",1)));
            for(int i=1,n=17+random.nextInt(48);i<n;i++) b.pattern("T",1+random.nextInt(12),List.of(CraftInput.of("R",1+random.nextInt(10)),CraftInput.of("S",1+random.nextInt(10))));
            all.add(new Sample("terminal-"+seed,b.build(),"T",1+random.nextInt(35)));
        }
        for (boolean reversed : List.of(false, true)) {
            for (int stock=0;stock<=6;stock++) all.add(new Sample("policy-assembly-"+stock+"-"+reversed,
                (CraftGraph)call("FeasiblePolicyAlternativesTest","assembly",new Class[]{long.class,boolean.class},(long)stock,reversed),"machine",9));
            all.add(new Sample("policy-shared-"+reversed,(CraftGraph)call("FeasiblePolicyAlternativesTest","sharedSupplier",new Class[]{boolean.class},reversed),"machine",2));
        }
        return all;
    }
    static String quantities(Map<?,Long> values) {
        var stable = new TreeMap<String,Long>(); values.forEach((k,v)->stable.put(k.toString(),v)); return stable.toString().replace(',',';');
    }
    static String balance(Sample sample, CraftPlan plan) {
        if(!plan.feasible()) return "na";
        Map<Object,BigInteger> balances=new HashMap<>();
        for(Object o:plan.usedStock().entrySet()) {
            var e=(Map.Entry<Object,Long>)o;
            if(e.getValue()<0 || e.getValue()>sample.graph.stock(e.getKey()))return "invalid-stock";
            balances.merge(e.getKey(),BigInteger.valueOf(e.getValue()),BigInteger::add);
        }
        for(Object o:plan.firings().entrySet()) {
            var e=(Map.Entry<CraftPattern,Long>)o; var p=e.getKey();
            if(e.getValue()<=0)return "invalid-count";
            var n=BigInteger.valueOf(e.getValue());
            balances.merge(p.output(),BigInteger.valueOf(p.outputAmount()).multiply(n),BigInteger::add);
            for(Object io:p.inputs()) {var in=(CraftInput)io;balances.merge(in.key(),in.exactAmount().multiply(n).negate(),BigInteger::add);}
            for(Object oo:p.byproducts()) {var out=(CraftOutput)oo;balances.merge(out.key(),BigInteger.valueOf(out.amount()).multiply(n),BigInteger::add);}
        }
        if(balances.values().stream().anyMatch(v->v.signum()<0) || balances.getOrDefault(sample.target,BigInteger.ZERO).compareTo(BigInteger.valueOf(sample.amount))<0)return "invalid-balance";
        return "pass";
    }
    static String replay(Sample sample, CraftPlan plan) {
        if (!plan.feasible()) return "na";
        var pool = new HashMap<Object, BigInteger>();
        for (Object o : plan.usedStock().entrySet()) {
            var e = (Map.Entry<Object, Long>) o;
            pool.put(e.getKey(), BigInteger.valueOf(e.getValue()));
        }
        var pending = new LinkedHashMap<CraftPattern, Long>(plan.firings());
        while (!pending.isEmpty()) {
            boolean progress = false;
            for (var iterator = pending.entrySet().iterator(); iterator.hasNext();) {
                var e = iterator.next(); var p = e.getKey();
                if (sample.graph.patternsFor(p.output()).stream().noneMatch(route -> route == p)) return "invalid-recipe";
                var needed = new HashMap<Object, BigInteger>();
                for (Object in : p.inputs()) {
                    var input = (CraftInput) in;
                    if (input.returned() || input.reusableStockSource() != null) throw new AssertionError("corpus requires ordinary inputs");
                    needed.merge(input.key(), input.exactAmount(), BigInteger::add);
                }
                if (needed.entrySet().stream().anyMatch(n -> pool.getOrDefault(n.getKey(), BigInteger.ZERO).compareTo(n.getValue()) < 0)) continue;
                needed.forEach((key,n) -> pool.merge(key,n.negate(),BigInteger::add));
                pool.merge(p.output(),p.exactOutputAmount(),BigInteger::add);
                for (Object out : p.byproducts()) { var side = (CraftOutput) out; pool.merge(side.key(),side.exactAmount(),BigInteger::add); }
                if (e.getValue() == 1) iterator.remove(); else e.setValue(e.getValue()-1);
                progress = true;
            }
            if (!progress) return "invalid-replay";
        }
        return pool.getOrDefault(sample.target,BigInteger.ZERO).compareTo(BigInteger.valueOf(sample.amount))>=0 ? "pass" : "invalid-output";
    }

    public static void main(String[] args) throws Exception {
        var samples=samples();
        var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        long thread=Thread.currentThread().threadId();
        for(int i=0;i<500;i++){var s=samples.get(i);CraftPlannerV2.planDetailed(s.graph,s.target,s.amount);}
        System.out.println("case,feasible,executions,stock,missing,budget,ns,allocated,probes,improvements,balance,replay");
        for(var s:samples) {
            long bytes=bean.getThreadAllocatedBytes(thread),start=System.nanoTime();
            var result=CraftPlannerV2.planDetailed(s.graph,s.target,s.amount);
            long elapsed=System.nanoTime()-start,allocated=bean.getThreadAllocatedBytes(thread)-bytes;
            var p=result.plan(); var d=result.diagnostics();
            System.out.printf(Locale.ROOT,"%s,%s,%s,%s,%s,%s,%d,%d,%d,%d,%s,%s%n",s.name,p.feasible(),p.executionCount(),quantities(p.usedStock()),quantities(p.missing()),p.budgetExhausted(),elapsed,allocated,d.consumptionOptimizationProbes(),d.consumptionOptimizationImprovements(),balance(s,p),replay(s,p));
        }
    }
}
