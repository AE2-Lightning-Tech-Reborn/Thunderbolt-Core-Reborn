package com.moakiee.thunderbolt.compat.neoeco;

import java.lang.reflect.*;
import java.util.UUID;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.KeyCounter;
import net.minecraft.world.level.Level;

/** Some platform releases lack this optional API; link only when the full contract exists. */
final class NeoEcoAllocatedApi {
    private static final Class<?> PROVIDER;
    private static final Class<?> RESERVATION;
    private static final Class<?> ENERGY_ACCOUNT;
    private static final Method PREPARE;
    static {
        Class<?> provider = null, reservation = null, energyAccount = null;
        Method prepare = null;
        try {
            provider = Class.forName("cn.dancingsnow.neoecoae.api.me.provider.ECOFastPathDispatchProvider");
            reservation = Class.forName("cn.dancingsnow.neoecoae.api.me.ECOFastPathFacade$Reservation");
            energyAccount = Class.forName("cn.dancingsnow.neoecoae.api.me.ECOFastPathFacade$EnergyAccount");
            var facade = Class.forName("cn.dancingsnow.neoecoae.api.me.ECOFastPathFacade");
            prepare = facade.getMethod("prepareAllocated", ICraftingProvider.class,
                    IPatternDetails.class, KeyCounter[].class, long.class, Level.class, UUID.class);
            prepare.getReturnType().getMethod("craftCount");
            prepare.getReturnType().getMethod("submit", energyAccount);
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            prepare = null;
        }
        PROVIDER = provider;
        RESERVATION = reservation;
        ENERGY_ACCOUNT = energyAccount;
        PREPARE = prepare;
    }
    static boolean available() { return PREPARE != null; }
    static boolean supports(Object provider) { return available() && PROVIDER.isInstance(provider); }
    static Prepared prepareAllocated(ICraftingProvider provider, IPatternDetails pattern,
            KeyCounter[] inputs, long count, Level level, UUID id) {
        if (!supports(provider)) return null;
        try {
            var value = PREPARE.invoke(null, provider, pattern, inputs, count, level, id);
            return value == null ? null : new Prepared(value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("NeoECO allocation preparation failed", e);
        }
    }
    record Prepared(Object value) {
        long craftCount() {
            try { return ((Number) value.getClass().getMethod("craftCount").invoke(value)).longValue(); }
            catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
        }
        boolean submit() {
            Object reservation = Proxy.newProxyInstance(
                    RESERVATION.getClassLoader(), new Class<?>[]{RESERVATION}, (proxy, method, args) -> {
                        return switch (method.getName()) {
                            case "commit", "refund" -> null;
                            case "toString" -> "TianshuAllocatedReservation";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == args[0];
                            default -> throw new UnsupportedOperationException(method.getName());
                        };
                    });
            Object account = Proxy.newProxyInstance(ENERGY_ACCOUNT.getClassLoader(),
                    new Class<?>[]{ENERGY_ACCOUNT}, (proxy, method, args) -> {
                        if (method.getName().equals("reserve")) return reservation;
                        if (method.getName().equals("toString")) return "TianshuAllocatedEnergy";
                        if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                        if (method.getName().equals("equals")) return proxy == args[0];
                        throw new UnsupportedOperationException(method.getName());
                    });
            try { return (boolean) value.getClass().getMethod("submit", ENERGY_ACCOUNT).invoke(value, account); }
            catch (InvocationTargetException uncertain) { throw new IndeterminateBatchException(uncertain.getCause()); }
            catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
        }
    }
    static final class IndeterminateBatchException extends RuntimeException {
        IndeterminateBatchException(Throwable cause) { super(cause); }
    }
}
