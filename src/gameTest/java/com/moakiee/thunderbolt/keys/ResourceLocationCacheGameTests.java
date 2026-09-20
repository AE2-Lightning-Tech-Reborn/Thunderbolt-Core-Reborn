package com.moakiee.thunderbolt.keys;

import com.moakiee.thunderbolt.core.keys.ResourceConstructionCache;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("thunderbolt_keys")
@PrefixGameTestTemplate(false)
public final class ResourceLocationCacheGameTests {
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void liveIdentifiersSurviveShortcutCollisionsAndResize(GameTestHelper h) {
        ResourceConstructionCache.configure(true);
        var held = new ArrayList<ResourceLocation>();
        for (int i = 0; i < 12_000; i++) held.add(ResourceLocation.parse("rl_cache:held_" + i));
        for (int i = 0; i < held.size(); i++) {
            var expected = held.get(i);
            require(ResourceLocation.parse(new String(expected.toString())) == expected, "parse lost live representative " + i);
            require(ResourceLocation.tryBuild(new String("rl_cache"), new String("held_" + i)) == expected,
                    "pair factory lost live representative " + i);
        }
        // Valid lowercase strings with identical Java hashes.
        var a = ResourceLocation.parse("rl_cache:an");
        var b = ResourceLocation.parse("rl_cache:c0");
        require(a.hashCode() == b.hashCode() && !a.equals(b), "collision fixture is invalid");
        require(ResourceLocation.parse("rl_cache:an") == a && ResourceLocation.parse("rl_cache:c0") == b,
                "hash collision merged identifiers");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void parsingKeepsNativeDefaultsSeparatorsValidationAndEquality(GameTestHelper h) {
        ResourceConstructionCache.configure(true);
        for (String path : new String[]{"", "path", "a/b.c-d_0"}) {
            var key = ResourceLocation.withDefaultNamespace(path);
            require(ResourceLocation.parse(path) == key && ResourceLocation.parse(":" + path) == key,
                    "default namespace differs");
            require(ResourceLocation.tryParse("minecraft:" + path) == key, "tryParse differs");
            require(ResourceLocation.fromNamespaceAndPath("", path) != key, "explicit empty namespace was defaulted");
            require(key.withPath(path) == key && key.withPath(p -> p) == key, "withPath missed canonical value");
            require(key.withPrefix("") == key && key.withSuffix("") == key, "prefix/suffix changed value");
        }
        var separated = ResourceLocation.bySeparator("ab/cd", '/');
        var unsplit = ResourceLocation.bySeparator("ab/cd", ':');
        require(separated.getNamespace().equals("ab") && separated.getPath().equals("cd"), "separator ignored");
        require(unsplit.getNamespace().equals("minecraft") && unsplit.getPath().equals("ab/cd"), "alias lost separator");
        require(ResourceLocation.tryBySeparator("ab/cd", '/') == separated, "try separator missed canonical value");
        // A successful custom-separator parse must not make an invalid colon parse succeed.
        ResourceLocation.bySeparator("good!path", '!');
        require(ResourceLocation.tryParse("good!path") == null, "alias bypassed validation");
        require(ResourceLocation.tryParse("BAD:path") == null && ResourceLocation.tryBuild("ok", "BAD") == null,
                "try factory accepted invalid components");
        boolean rejected = false;
        try { ResourceLocation.parse("bad:path:extra"); }
        catch (net.minecraft.ResourceLocationException expected) { rejected = true; }
        require(rejected, "throwing factory lost validation");
        var map = new HashMap<ResourceLocation, Integer>();
        map.put(separated, 1); map.put(unsplit, 2);
        ResourceConstructionCache.configure(true, true);
        require(ResourceLocation.bySeparator("ab/cd", '/') == separated, "unchanged config discarded live cache");
        ResourceConstructionCache.clear();
        require(ResourceLocation.bySeparator("ab/cd", '/') != separated, "explicit clear did not change generation");
        require(map.get(ResourceLocation.bySeparator("ab/cd", '/')) == 1, "reset broke hash-map equality");
        ResourceConstructionCache.configure(false, true);
        var fresh = ResourceLocation.bySeparator("ab/cd", ':');
        require(fresh != unsplit && map.get(fresh) == 2, "disabling cache broke original value equality");
        ResourceConstructionCache.configure(true);
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void concurrentFirstFactoriesChooseOneLiveRepresentative(GameTestHelper h) throws Exception {
        ResourceConstructionCache.configure(true);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var futures = new ArrayList<java.util.concurrent.Future<ResourceLocation>>();
            for (int i = 0; i < 8; i++) {
                final int mode = i % 4;
                futures.add(pool.submit(() -> {
                    require(start.await(5, TimeUnit.SECONDS), "start timeout");
                    return switch (mode) {
                        case 0 -> ResourceLocation.parse("rl_cache:concurrent");
                        case 1 -> ResourceLocation.tryParse("rl_cache:concurrent");
                        case 2 -> ResourceLocation.fromNamespaceAndPath("rl_cache", "concurrent");
                        default -> ResourceLocation.tryBuild("rl_cache", "concurrent");
                    };
                }));
            }
            start.countDown();
            var expected = futures.getFirst().get(5, TimeUnit.SECONDS);
            for (var future : futures) require(future.get(5, TimeUnit.SECONDS) == expected, "first construction raced");
        }
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void oldFactoryCannotPublishIntoNewGeneration(GameTestHelper h) throws Exception {
        ResourceConstructionCache.configure(false);
        var old = ResourceLocation.parse("rl_cache:reset");
        ResourceConstructionCache.configure(true);
        var entered = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var future = pool.submit(() -> ResourceConstructionCache.location("rl_cache", "reset", args -> {
                entered.countDown();
                try { require(finish.await(5, TimeUnit.SECONDS), "factory timeout"); }
                catch (InterruptedException e) { throw new AssertionError(e); }
                return old;
            }));
            try {
                require(entered.await(5, TimeUnit.SECONDS), "entry timeout");
                ResourceConstructionCache.clear();
                var current = ResourceLocation.parse("rl_cache:reset");
                require(current != old && current.equals(old), "generation fixture invalid");
                finish.countDown();
                require(future.get(5, TimeUnit.SECONDS) == old, "old factory changed result");
                require(ResourceLocation.parse("rl_cache:reset") == current, "old generation overwrote new cache");
            } finally { finish.countDown(); }
        }
        h.succeed();
    }

    private record Witness(ResourceLocation retained, WeakReference<String> input) {}
    private static Witness liveWitness() {
        String input = new String("rl_cache:weak_input");
        var value = ResourceLocation.parse(input);
        require(ResourceLocation.parse(input) == value, "second parse lost canonicality");
        return new Witness(value, new WeakReference<>(input));
    }
    private static WeakReference<ResourceLocation> unusedWitness() {
        var value = ResourceLocation.parse("rl_cache:unused");
        require(ResourceLocation.parse("rl_cache:unused") == value, "second parse lost canonicality");
        return new WeakReference<>(value);
    }
    private static void awaitCollection(WeakReference<?> reference) throws Exception {
        for (int i = 0; i < 100 && reference.get() != null; i++) {
            System.gc(); ResourceConstructionCache.maintain(); Thread.sleep(10);
        }
        require(reference.get() == null, "cache retained an unused reference");
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void weakAliasesReleaseInputsAndUnusedIdentifiers(GameTestHelper h) throws Exception {
        ResourceConstructionCache.configure(true);
        var witness = liveWitness();
        awaitCollection(witness.input);
        require(ResourceLocation.parse(new String("rl_cache:weak_input")) == witness.retained,
                "GC of caller string lost a live representative");
        awaitCollection(unusedWitness());
        h.succeed();
    }
}
