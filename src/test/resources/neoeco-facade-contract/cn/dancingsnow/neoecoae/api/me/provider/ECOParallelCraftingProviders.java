package cn.dancingsnow.neoecoae.api.me.provider;

public final class ECOParallelCraftingProviders {
    public static ECOParallelCraftingProvider adapter;
    public static ECOParallelCraftingProvider find(Object provider) {
        return provider instanceof ECOParallelCraftingProvider parallel ? parallel : adapter;
    }
}
