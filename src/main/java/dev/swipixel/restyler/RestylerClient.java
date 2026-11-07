package dev.swipixel.restyler;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.util.math.Vec3d;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Keys (changeable in Options > Controls): K on/off, O menu.
 * /restyler                                  status
 * /restyler mood|style|effect <name> <0-100>  (or off)
 * /vibe <words>                              CLIP picks moods / styles / effects from text (pins the look)
 * /vibe auto | global | biome | zone | world reload | why   world looks (see WorldLook)
 */
public class RestylerClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        Restyler r = Restyler.INSTANCE;
        r.init();
        WorldLook w = WorldLook.INSTANCE;
        w.init();
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(w::leave));

        UpdateCheck.start();
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
                client.execute(() -> UpdateCheck.showIfAny(client)));

        // end of world rendering (before the hand): camera + depth for the effects
        WorldRenderEvents.END.register(ctx -> {
            Vec3d p = ctx.camera().getPos();
            r.onWorldEnd(ctx.projectionMatrix(), ctx.positionMatrix(), p.x, p.y, p.z);
        });

        KeyBinding toggle = key("toggle", GLFW.GLFW_KEY_K);
        KeyBinding menu = key("menu", GLFW.GLFW_KEY_O);

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                ClientCommandManager.literal("restyler")
                        .executes(ctx -> {
                            Restyler.say("Restyler " + r.statusLine(), false);
                            return 1;
                        })
                        .then(ClientCommandManager.literal("mood")
                                .executes(ctx -> {
                                    Restyler.say("Moods: " + Mood.summary() + "  (/restyler mood <name> <0-100>, "
                                            + "/restyler mood off)", false);
                                    return 1;
                                })
                                .then(ClientCommandManager.literal("off").executes(ctx -> {
                                    Mood.allOff();
                                    Restyler.say("Moods off", true);
                                    return 1;
                                }))
                                .then(ClientCommandManager.argument("name", StringArgumentType.word())
                                        .suggests((ctx, sb) -> {
                                            for (String k : Mood.KEYS) sb.suggest(k);
                                            return sb.buildFuture();
                                        })
                                        .then(ClientCommandManager.argument("percent", IntegerArgumentType.integer(0, 100))
                                                .executes(ctx -> {
                                                    int i = Mood.index(StringArgumentType.getString(ctx, "name"));
                                                    if (i < 0) {
                                                        Restyler.say("Unknown mood. Try: " + String.join(", ", Mood.KEYS), false);
                                                        return 0;
                                                    }
                                                    Mood.set(i, IntegerArgumentType.getInteger(ctx, "percent") / 100f);
                                                    Restyler.say("Moods: " + Mood.summary(), true);
                                                    return 1;
                                                }))))
                        .then(ClientCommandManager.literal("style")
                                .executes(ctx -> {
                                    Restyler.say("Styles: " + Style.summary() + "  (/restyler style <name> <0-100>, "
                                            + "/restyler style off)", false);
                                    return 1;
                                })
                                .then(ClientCommandManager.literal("off").executes(ctx -> {
                                    Style.allOff();
                                    Restyler.say("Styles off", true);
                                    return 1;
                                }))
                                .then(ClientCommandManager.argument("name", StringArgumentType.word())
                                        .suggests((ctx, sb) -> {
                                            for (String k : Style.KEYS) sb.suggest(k);
                                            return sb.buildFuture();
                                        })
                                        .then(ClientCommandManager.argument("percent", IntegerArgumentType.integer(0, 100))
                                                .executes(ctx -> {
                                                    int i = Style.index(StringArgumentType.getString(ctx, "name"));
                                                    if (i < 0) {
                                                        Restyler.say("Unknown style. Try: " + String.join(", ", Style.KEYS), false);
                                                        return 0;
                                                    }
                                                    Style.set(i, IntegerArgumentType.getInteger(ctx, "percent") / 100f);
                                                    Restyler.say("Styles: " + Style.summary(), true);
                                                    return 1;
                                                }))))
                        .then(ClientCommandManager.literal("effect")
                                .executes(ctx -> {
                                    Restyler.say("Effects: " + Effect.summary() + "  (/restyler effect <name> <0-100>, "
                                            + "/restyler effect off)", false);
                                    return 1;
                                })
                                .then(ClientCommandManager.literal("off").executes(ctx -> {
                                    Effect.allOff();
                                    Restyler.say("Effects off", true);
                                    return 1;
                                }))
                                .then(ClientCommandManager.argument("name", StringArgumentType.word())
                                        .suggests((ctx, sb) -> {
                                            for (String k : Effect.KEYS) sb.suggest(k);
                                            return sb.buildFuture();
                                        })
                                        .then(ClientCommandManager.argument("percent", IntegerArgumentType.integer(0, 100))
                                                .executes(ctx -> {
                                                    int i = Effect.index(StringArgumentType.getString(ctx, "name"));
                                                    if (i < 0) {
                                                        Restyler.say("Unknown effect. Try: " + String.join(", ", Effect.KEYS), false);
                                                        return 0;
                                                    }
                                                    Effect.set(i, IntegerArgumentType.getInteger(ctx, "percent") / 100f);
                                                    Restyler.say("Effects: " + Effect.summary(), true);
                                                    return 1;
                                                }))))));

        // /vibe <words>: CLIP turns text into mood / style / effect sliders (pins); /vibe auto | global | biome |
        // zone | world | why: world looks. A prompt that starts with one of those words still works where it can't be
        // a sub-command ("/vibe zone of death", "/vibe world of ice", "/vibe why so serious").
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                ClientCommandManager.literal("vibe")
                        .executes(ctx -> {
                            Restyler.say("/vibe <words> sets moods, art styles and effects from text, /vibe off clears them. "
                                    + Vibe.status() + " | " + w.statusTag() + " (/vibe auto, global, biome, zone, world reload, why)", false);
                            return 1;
                        })
                        .then(ClientCommandManager.literal("off").executes(ctx -> {
                            Mood.allOff();
                            Style.allOff();
                            Effect.allOff();
                            Restyler.say("Vibe off", true);
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("auto")
                                .executes(ctx -> {
                                    w.auto();
                                    return 1;
                                })
                                .then(ClientCommandManager.argument("text", StringArgumentType.greedyString()).executes(ctx -> {
                                    vibe("auto " + StringArgumentType.getString(ctx, "text"));
                                    return 1;
                                })))
                        .then(ClientCommandManager.literal("global")
                                .executes(ctx -> {
                                    w.globalCommand(null);
                                    return 1;
                                })
                                .then(ClientCommandManager.argument("look", StringArgumentType.greedyString())
                                        .suggests((ctx, sb) -> suggest(sb, t -> t.isEmpty() ? with(line(t), "off") : line(t)))
                                        .executes(ctx -> {
                                            w.globalCommand(StringArgumentType.getString(ctx, "look"));
                                            return 1;
                                        })))
                        .then(ClientCommandManager.literal("biome")
                                .executes(ctx -> {
                                    w.biomeCommand("");
                                    return 1;
                                })
                                .then(ClientCommandManager.argument("args", StringArgumentType.greedyString())
                                        .suggests((ctx, sb) -> suggest(sb, RestylerClient::biomeSuggestions))
                                        .executes(ctx -> {
                                            w.biomeCommand(StringArgumentType.getString(ctx, "args"));
                                            return 1;
                                        })))
                        .then(ClientCommandManager.literal("zone")
                                .executes(ctx -> {
                                    w.zoneCommand("");
                                    return 1;
                                })
                                .then(ClientCommandManager.argument("args", StringArgumentType.greedyString())
                                        .suggests((ctx, sb) -> suggest(sb, RestylerClient::zoneSuggestions))
                                        .executes(ctx -> {
                                            String a = StringArgumentType.getString(ctx, "args");
                                            if (!w.zoneCommand(a)) vibe("zone " + a);
                                            return 1;
                                        })))
                        .then(ClientCommandManager.literal("world")
                                .executes(ctx -> {
                                    Restyler.say("/vibe world reload: re-reads world.properties, biomes.txt and this world's zones", false);
                                    return 1;
                                })
                                .then(ClientCommandManager.argument("args", StringArgumentType.greedyString())
                                        .suggests((ctx, sb) -> suggest(sb, t -> t.isEmpty() ? List.of("reload") : List.of()))
                                        .executes(ctx -> {
                                            String a = StringArgumentType.getString(ctx, "args");
                                            if (a.strip().equalsIgnoreCase("reload")) {
                                                w.reload();
                                                Restyler.say("World looks: files reloaded", false);
                                            } else {
                                                vibe("world " + a);
                                            }
                                            return 1;
                                        })))
                        .then(ClientCommandManager.literal("why")
                                .executes(ctx -> {
                                    w.why();
                                    return 1;
                                })
                                .then(ClientCommandManager.argument("text", StringArgumentType.greedyString()).executes(ctx -> {
                                    vibe("why " + StringArgumentType.getString(ctx, "text"));
                                    return 1;
                                })))
                        .then(ClientCommandManager.argument("text", StringArgumentType.greedyString()).executes(ctx -> {
                            vibe(StringArgumentType.getString(ctx, "text"));
                            return 1;
                        }))));

        ClientTickEvents.END_CLIENT_TICK.register(client -> w.tick(client));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (menu.wasPressed()) {
                client.setScreen(new RestylerScreen());
            }
            while (toggle.wasPressed()) {
                r.toggle();
            }
        });
    }

    /** /vibe <text>: CLIP picks the sliders (this pins the look: world layers are ignored until /vibe auto). */
    private static void vibe(String text) {
        Restyler.say("Generating with CLIP: \"" + text + "\" ...", true);
        Vibe.ensureLoaded(() -> {
            Thread t = new Thread(() -> {                 // CLIP on the CPU, off the game thread
                String msg;
                try {
                    msg = "Vibe \"" + text + "\": " + Vibe.apply(text);
                } catch (Exception e) {
                    msg = "Vibe failed: " + e;
                }
                final String m = msg;
                net.minecraft.client.MinecraftClient.getInstance().execute(() -> Restyler.say(m, false));
            }, "restyler-vibe");
            t.setDaemon(true);
            t.start();
        });
    }

    // ------------------------------------------------------------ tab completion for the greedy arguments

    private static final List<String> CONDS = List.of("@dawn", "@noon", "@dusk", "@night", "@rain", "@snow", "@thunder");

    /** Completes the last word; fn gets the words before it. */
    private static CompletableFuture<Suggestions> suggest(SuggestionsBuilder sb, Function<List<String>, List<String>> fn) {
        String rem = sb.getRemaining();
        int sp = rem.lastIndexOf(' ');
        String before = rem.substring(0, sp + 1).strip();
        String part = rem.substring(sp + 1).toLowerCase(Locale.ROOT);
        List<String> words = before.isEmpty() ? List.of() : Arrays.asList(before.split("\\s+"));
        SuggestionsBuilder off = sb.createOffset(sb.getStart() + sp + 1);
        for (String c : fn.apply(words)) if (c.toLowerCase(Locale.ROOT).startsWith(part)) off.suggest(c);
        return off.buildFuture();
    }

    private static List<String> with(List<String> a, String... more) {
        List<String> out = new ArrayList<>(Arrays.asList(more));
        out.addAll(a);
        return out;
    }

    /** Inside a slider line: slider names at the start and after a comma. */
    private static List<String> line(List<String> words) {
        if (words.isEmpty() || words.get(words.size() - 1).endsWith(",")) return WorldLook.sliderNames();
        return List.of();
    }

    private static List<String> biomeSuggestions(List<String> t) {
        if (t.isEmpty()) {
            List<String> out = new ArrayList<>(List.of("here", "list", "*"));
            out.addAll(WorldLook.INSTANCE.biomeIds());
            return out;
        }
        if (t.get(0).equalsIgnoreCase("list")) return List.of();
        if (t.size() == 1) {
            List<String> out = new ArrayList<>(List.of("reset", "~"));
            out.addAll(CONDS);
            out.addAll(WorldLook.sliderNames());
            return out;
        }
        String second = t.get(1);
        if (second.equalsIgnoreCase("reset")) return List.of();
        return line(second.startsWith("@") || second.equals("~") ? t.subList(2, t.size()) : t.subList(1, t.size()));
    }

    private static List<String> zoneSuggestions(List<String> t) {
        if (t.isEmpty()) return List.of("add", "box", "remove", "list", "edit", "fade");
        String sub = t.get(0).toLowerCase(Locale.ROOT);
        switch (sub) {
            case "remove", "edit", "fade" -> {
                if (t.size() == 1) return WorldLook.INSTANCE.zoneNames();
                if (sub.equals("fade")) return t.size() == 2 ? List.of("time", "hard", "crossfade") : List.of();
                if (sub.equals("edit")) return line(t.subList(2, t.size()));
                return List.of();
            }
            case "add" -> {
                return t.size() >= 3 ? line(t.subList(3, t.size())) : List.of();
            }
            case "box" -> {
                if (t.size() >= 2 && t.size() < 8) return List.of("~");
                return t.size() >= 8 ? line(t.subList(8, t.size())) : List.of();
            }
            default -> {
                return List.of();
            }
        }
    }

    private static KeyBinding key(String name, int code) {
        return KeyBindingHelper.registerKeyBinding(
                new KeyBinding("key.restyler." + name, InputUtil.Type.KEYSYM, code, "category.restyler"));
    }
}
