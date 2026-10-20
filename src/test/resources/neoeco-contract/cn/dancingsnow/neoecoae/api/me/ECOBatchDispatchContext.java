package cn.dancingsnow.neoecoae.api.me;

import appeng.api.crafting.IPatternDetails;
import java.util.List;
import java.util.UUID;
import net.minecraft.world.level.Level;

public record ECOBatchDispatchContext(IPatternDetails pattern, List<?> inputs, List<?> outputs,
        List<?> containers, Level level, UUID jobId) {}
