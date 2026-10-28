package com.moakiee.thunderbolt.neoecoqa;

import appeng.api.config.Actionable;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.*;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import com.google.common.collect.ImmutableSet;

/** Gives the upstream physical acceptance job a real requester and observable completion nexus. */
public final class NeoEcoGtoRequesterProbe {
    private NeoEcoGtoRequesterProbe() {}

    public static ICraftingSubmitResult submit(ICraftingService service, ICraftingPlan plan,
            ICraftingRequester original, ICraftingCPU cpu, boolean prioritizePower, IActionSource source)
            throws ReflectiveOperationException {
        if (original != null) throw new IllegalStateException("Upstream acceptance requester changed");
        var driver = Class.forName("cn.dancingsnow.neoecoae.client.diagnostics.GTOCompatibilityClientProbe");
        var field = driver.getDeclaredField("received");
        field.setAccessible(true);
        var received = (KeyCounter) field.get(null);
        var node = source.machine().orElseThrow().getActionableNode();
        var requester = new ICraftingRequester() {
            private ICraftingLink link;

            @Override public IGridNode getActionableNode() { return node; }
            @Override public ImmutableSet<ICraftingLink> getRequestedJobs() {
                return link == null ? ImmutableSet.of() : ImmutableSet.of(link);
            }
            @Override public long insertCraftedItems(ICraftingLink deliveredLink, AEKey key,
                    long amount, Actionable mode) {
                if (link == null || !link.getCraftingID().equals(deliveredLink.getCraftingID()))
                    throw new IllegalStateException("Output delivered to the wrong acceptance job");
                if (mode == Actionable.MODULATE) received.add(key, amount);
                return amount;
            }
            @Override public void jobStateChange(ICraftingLink changedLink) {
                org.slf4j.LoggerFactory.getLogger("thunderbolt_neoeco_qa").info(
                        "THUNDERBOLT_NEOECO_REQUESTER state done={} canceled={} job={}",
                        changedLink.isDone(), changedLink.isCanceled(), changedLink.getCraftingID());
            }
        };
        var result = service.submitJob(plan, requester, cpu, prioritizePower, source);
        requester.link = result.link();
        if (result.successful() && (requester.link == null || requester.link.isStandalone()))
            throw new IllegalStateException("Acceptance requester did not receive a linked job");
        return result;
    }
}
