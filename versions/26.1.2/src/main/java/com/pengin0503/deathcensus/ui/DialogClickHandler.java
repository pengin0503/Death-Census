package com.pengin0503.deathcensus.ui;

import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.CustomClickActionEvent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Receives vanilla custom-click events from dialogs and turns them into
 * {@link UiRequest}s for the common dispatcher. Payloads are client-controlled:
 * every field is parsed defensively, and the event is only cancelled after a
 * known action was actually handled.
 */
public final class DialogClickHandler {
    private static final List<String> INPUT_KEYS = List.of(
            "in_period", "in_category", "in_dimension", "in_query",
            "in_excuse", "in_optout", "in_type");

    private DialogClickHandler() {
    }

    @SubscribeEvent
    public static void onCustomClick(CustomClickActionEvent event) {
        ServerPlayer player = event.getPlayer();
        Identifier identifier = event.getIdentifier();
        if (player == null || identifier == null) {
            return;
        }
        Optional<String> action = UiActions.actionFromIdentifier(identifier.toString());
        if (action.isEmpty() || !(event.getPayload() instanceof CompoundTag payload)) {
            return;
        }
        Tag version = payload.get("v");
        if (!(version instanceof NumericTag versionTag) || versionTag.intValue() != 1) {
            return;
        }

        Map<String, String> inputs = new LinkedHashMap<>();
        String session = asString(payload.get("s"), "s");
        if (!session.isEmpty()) {
            inputs.put("s", session);
        }
        String rawPage = asString(payload.get("p"), "p");
        if (!rawPage.isEmpty()) {
            Optional<Integer> page = UiActions.parsePage(rawPage);
            if (page.isEmpty()) {
                return;
            }
            inputs.put("p", rawPage);
        }
        for (String key : INPUT_KEYS) {
            Tag value = payload.get(key);
            if (value != null) {
                inputs.put(key, asString(value, key));
            }
        }

        String targetUuid = "";
        Tag target = payload.get("t");
        if (target != null) {
            String raw = asString(target, "t");
            if (UiActions.parseUuid(raw).isEmpty()) {
                return;
            }
            targetUuid = raw;
        }
        long epoch = -1L;
        Tag death = payload.get("d");
        if (death != null) {
            Optional<Long> parsed = UiActions.parseEpoch(asString(death, "d"));
            if (parsed.isEmpty()) {
                return;
            }
            epoch = parsed.get();
        }

        UiRequest request = new UiRequest(player.getUUID(), action.get(), session,
                rawPage.isEmpty() ? 1 : Integer.parseInt(rawPage), targetUuid, epoch, inputs);
        UiResult result = UiActions.handle(request, new DialogUiContext(player));
        if (result instanceof UiResult.NoChange) {
            return;
        }
        DialogUi.openResult(player, action.get(), result);
        // Required by CustomClickActionEvent's contract when the click was acted on.
        event.setCanceled(true);
    }

    /** NBT arrives with explicit tag types; strings, booleans and numbers are all bounded. */
    private static String asString(Tag tag, String key) {
        if (tag instanceof StringTag stringTag) {
            return stringTag.value();
        }
        if (tag instanceof ByteTag byteTag) {
            return "in_optout".equals(key)
                    ? Boolean.toString(byteTag.byteValue() != 0)
                    : Integer.toString(byteTag.byteValue());
        }
        if (tag instanceof NumericTag numericTag) {
            if ("d".equals(key)) {
                return Long.toString(numericTag.longValue());
            }
            return Integer.toString(numericTag.intValue());
        }
        return "";
    }
}
