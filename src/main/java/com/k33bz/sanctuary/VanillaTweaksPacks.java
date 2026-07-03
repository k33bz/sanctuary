package com.k33bz.sanctuary;

import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.ResourcePackActivationType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Bundled Vanilla Tweaks datapacks + crafting tweaks (https://vanillatweaks.net/), each
 * registered as its own built-in datapack so servers toggle them individually and per-world
 * with vanilla {@code /datapack enable|disable "sanctuary:vt/<name>"} — nothing is active
 * unless an admin opts in. The pack list ships in {@code vt_packs.txt}; the packs themselves
 * live under {@code resourcepacks/vt/}. Redistribution per Vanilla Tweaks' terms: bundled
 * within a substantially larger free project, with attribution here, in credits.txt, and on
 * the download page.
 */
public final class VanillaTweaksPacks {
    private VanillaTweaksPacks() {
    }

    public static void register() {
        ModContainer container = FabricLoader.getInstance().getModContainer(Sanctuary.MOD_ID).orElse(null);
        if (container == null) {
            return;
        }
        int count = 0;
        try (InputStream in = VanillaTweaksPacks.class.getResourceAsStream("/vt_packs.txt")) {
            if (in == null) {
                return;
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                String name = line.trim();
                if (name.isEmpty()) {
                    continue;
                }
                boolean ok = ResourceManagerHelper.registerBuiltinResourcePack(
                        Identifier.fromNamespaceAndPath(Sanctuary.MOD_ID, "vt/" + name),
                        container,
                        Component.literal("VT " + name.replace('_', ' ')),
                        ResourcePackActivationType.NORMAL);
                if (ok) {
                    count++;
                }
            }
        } catch (Exception e) {
            Sanctuary.LOGGER.warn("[sanctuary] failed to register Vanilla Tweaks packs", e);
            return;
        }
        Sanctuary.LOGGER.info("[sanctuary] registered {} bundled Vanilla Tweaks packs (all opt-in; "
                + "enable per world via /datapack enable)", count);
    }
}
