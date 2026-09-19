package com.moakiee.thunderbolt.comparison;

import appeng.api.stacks.AEItemKey;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Shared external harness: no implementation-specific cache or helper is called. */
final class DirectFactoryBenchmark {
    private static volatile Object sink;
    private static void loop(int mode, ItemStack stack, int calls) {
        if (mode == 0) { for (int i=0;i<calls;i++) sink=AEItemKey.of(stack); }
        else { for (int i=0;i<calls;i++) sink=AEItemKey.of(stack); }
    }
    static void run() {
        var inputs=new ItemStack[]{new ItemStack(Items.IRON_INGOT),new ItemStack(Items.IRON_INGOT,64)};
        var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        long thread=Thread.currentThread().threadId();
        int calls=1_000_000;
        double[][] times=new double[2][9], bytes=new double[2][9];
        for (int round=-8;round<9;round++) for(int index=0;index<2;index++) {
            int mode=Math.floorMod(round+index,2);
            loop(mode,inputs[mode],20_000);
            long allocated=bean.getThreadAllocatedBytes(thread), start=System.nanoTime();
            loop(mode,inputs[mode],calls);
            long elapsed=System.nanoTime()-start, used=bean.getThreadAllocatedBytes(thread)-allocated;
            if(!(sink instanceof AEItemKey key)||!key.matches(inputs[mode]))throw new AssertionError("wrong factory value");
            if(round>=0){times[mode][round]=elapsed/(double)calls;bytes[mode][round]=used/(double)calls;}
        }
        for(int mode=0;mode<2;mode++){
            Arrays.sort(times[mode]);Arrays.sort(bytes[mode]);
            System.out.printf(java.util.Locale.ROOT,"DIRECT_FACTORY mode=%s count=%d ns=%.3f min=%.3f max=%.3f bytes=%.2f%n",
                System.getProperty("comparison.mode"),inputs[mode].getCount(),times[mode][4],times[mode][0],times[mode][8],bytes[mode][4]);
        }
        if(Boolean.getBoolean("plainCost.measureSize"))try {
            var agent=ClassLoader.getSystemClassLoader().loadClass("bench.SizeAgent").getMethod("sizeOf",Object.class);
            var key=AEItemKey.of(inputs[0]);
            System.out.println("DIRECT_KEY_SIZE mode="+System.getProperty("comparison.mode")+" bytes="+agent.invoke(null,key));
        }catch(Exception e){throw new AssertionError(e);}
    }
}
