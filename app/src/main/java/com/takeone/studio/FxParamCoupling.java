package com.takeone.studio;

/**
 * Keeps redundant FX-rack controls (tempo percent/BPM and speed multiplier/
 * percent) consistent without requiring a live StudioFragment instance.
 */
final class FxParamCoupling {

    private FxParamCoupling() {
    }

    static void sync(DspChain.EffectSlot slot, String changedId) {
        if (slot == null || changedId == null) return;
        if (slot.type == DspChain.EffectType.CHANGE_TEMPO) {
            int from = slot.extraParams.containsKey("tempo_bpmfrom")
                    ? slot.extraParams.get("tempo_bpmfrom") : 120;
            int to = slot.extraParams.containsKey("tempo_bpmto")
                    ? slot.extraParams.get("tempo_bpmto") : 120;
            if ("tempo_percent".equals(changedId)) {
                double pct = slot.value / 100.0;
                int nextTo = (int) Math.round(from * (1.0 + pct / 100.0));
                slot.extraParams.put("tempo_bpmto",
                        Math.max(30, Math.min(300, nextTo)));
            } else if ("tempo_bpmfrom".equals(changedId)
                    || "tempo_bpmto".equals(changedId)) {
                if (from > 0) {
                    double pct = ((double) to / from - 1.0) * 100.0;
                    int raw = (int) Math.round(pct * 100.0);
                    slot.value = Math.max(-7500, Math.min(30000, raw));
                }
            }
        } else if (slot.type == DspChain.EffectType.CHANGE_SPEED_AND_PITCH) {
            if ("speed_mult".equals(changedId)) {
                int rawPct = (slot.value - 100) * 100;
                slot.extraParams.put("speed_percent",
                        Math.max(-7500, Math.min(30000, rawPct)));
            } else if ("speed_percent".equals(changedId)) {
                int rawPct = slot.extraParams.containsKey("speed_percent")
                        ? slot.extraParams.get("speed_percent") : 0;
                int rawSpeed = (int) Math.round(
                        100.0 * (1.0 + rawPct / 10000.0));
                slot.value = Math.max(25, Math.min(400, rawSpeed));
            }
        }
    }
}
