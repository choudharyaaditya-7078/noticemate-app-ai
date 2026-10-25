# NoticeMate — Vibrant Aurora Liquid Glass & Settings Debug Logging

Adds a dedicated **Debug Logs** viewer inside **Settings** to inspect real-time PDF, OCR, and AI pipeline diagnostics while keeping the existing working PDF reader intact, and upgrades the **Dynamic Moving Aurora Background** with high-saturation, multi-stop luminous color waves behind the Liquid Glass UI.

### User Review & Critical Decisions

> [!IMPORTANT]
> The existing PDF reading and bilingual (`English` | `हिंदी`) analysis pipeline is working as expected and will be preserved without breaking changes. This update focuses strictly on **Settings Debug Logging** and **More Vibrant Moving Aurora Colors**.

- **Confirmed Scope 1 — Preserve Working PDF Pipeline**: Keep the current PDF/Image/Text extraction and AI analysis flow intact while instrumenting it with non-intrusive diagnostic log events.
- **Confirmed Scope 2 — Settings Debug Logs (`Profile › Settings › Debug Logs`)**: Add an in-memory diagnostic logger with a dedicated Settings screen featuring live log entries, **Copy Logs**, and **Clear Logs**.
- **Confirmed Scope 3 — High-Vibrancy Moving Aurora Background**: Increase color saturation, opacity, and multi-stop glow intensity across all 5 moving aurora orbs and the AGSL liquid caustic shader in both Dark and Light modes.

---

### 1. Overview & Core Concept

- **What It Does**:
  1. **Settings › Debug Logs**: Captures timestamped diagnostic events across file selection, PDF page inspection, OCR recognition, and AI model calls, displaying them in a clean Liquid Glass viewer in Settings with one-tap **Copy Logs** and **Clear Logs**.
  2. **Super-Vibrant Moving Aurora Background**: Enhances the 5 animated Lissajous aurora orbs and interactive AGSL liquid caustic shader with richer neon/jewel-tone multi-stop gradients so moving colors visibly refract through every translucent Liquid Glass card and button.
- **Target Audience / Persona**: Everyday students and citizens reading official notices who want a vivid, fluid Liquid Glass interface, plus easy access to diagnostic logs in Settings whenever troubleshooting is needed.
- **Key Value**: Full visibility into under-the-hood notice processing via Settings without cluttering the minimal Home screen, paired with a much more striking, colorful moving aurora backdrop.

---

### 2. User Experience & Visual Design

- **Key User Flows**:
  1. **Vibrant Aurora Home & Notice Screens**: Across Home, Recent Notices, PDF/Image/Text analysis, and Settings, users see rich, continuously flowing aurora color waves (Electric Cyan, Hyper Magenta, Ultraviolet, Neon Emerald, and Solar Gold/Coral) moving smoothly behind translucent Liquid Glass surfaces and reacting to touch.
  2. **Viewing Debug Logs in Settings**:
     - Tap the **Settings** gear icon on the top bar -> tap **Debug Logs** (`View runtime logs for PDF, OCR & AI`).
     - Inspect chronological log entries with level badges (`INFO`, `WARN`, `ERROR`), timestamps, stage tags (`PDF`, `OCR`, `AI`, `UI`), and execution durations in milliseconds.
     - Tap **Copy Logs** to copy the full diagnostic report to the clipboard, or **Clear Logs** to reset the buffer.
- **Visual Identity & Theme**:
  - *Aesthetic Direction*: High-craft **Liquid Glass / Dynamic Glass** over a **Super-Vibrant Moving Aurora** canvas.
  - *Color Palette & Mood*:
    - **Dark Mode Aurora Orbs**:
      - Orb 1 (Electric Cyan): `#00F0FF` at `0.85` core alpha fading through `#0088FF` (`0.45`) to transparent.
      - Orb 2 (Hyper Magenta): `#FF1E8E` at `0.82` core alpha fading through `#D91670` (`0.42`) to transparent.
      - Orb 3 (Ultraviolet Indigo): `#8B3DFF` at `0.82` core alpha fading through `#5B21B6` (`0.44`) to transparent.
      - Orb 4 (Neon Emerald Aqua): `#00F5A0` at `0.76` core alpha fading through `#00B4D8` (`0.38`) to transparent.
      - Orb 5 (Solar Amber Coral - Touch-Reactive): `#FF8A00` at `0.78` core alpha fading through `#FF3D54` (`0.40`) to transparent.
    - **Light Mode Aurora Orbs**:
      - Boosted jewel/pastel saturation (`#00C6FF`, `#FF4791`, `#8E54E9`, `#00E1B4`, `#FF9F1C`) with `0.78–0.85` core alphas over a luminous ice-pearl base (`#EEF4FF`) so the moving colors remain unmistakably vivid in daylight.
  - *Component Styling & Layout*:
    - Translucent `GlassCard` surfaces (`0.38` dark / `0.46` light surface alpha) allow more of the vibrant moving aurora colors to bleed through while preserving crisp text contrast andspecular rim highlights.

---

### 3. Key Product Decisions & Trade-Offs

- **Decision 1: Non-Intrusive Ring-Buffer Diagnostic Logger**
  - *Chosen Approach*: Maintain an in-memory thread-safe ring buffer of the latest 250 structured log entries exposed as a reactive `StateFlow`.
  - *Why*: Zero disk bloat or performance overhead on 6 GB RAM phones while providing immediate, copyable step-by-step traces in Settings.
  - *Alternatives Considered*: Persistent file logging on disk (adds unnecessary storage I/O and cleanup complexity).
- **Decision 2: Multi-Stop Core+Halo Radial Gradients for Aurora Orbs**
  - *Chosen Approach*: Upgrade each moving orb from a simple 2-stop gradient (`color -> transparent`) to a 3-stop luminous gradient (`bright core -> rich mid-tone halo -> transparent`) with larger radii (`0.82x–0.92x` screen dimension) and higher AGSL caustic wave intensity (`0.28` alpha).
  - *Why*: 2-stop radial gradients lose saturation quickly away from the center; 3-stop gradients create lush, overlapping color fields that mix dynamically as the 5 orbs cross paths.

---

### 4. Technical Architecture & Data Strategy *(Technical Reference)*

- **Architecture & Component Diagram**:

```
┌─────────────────────────────────────────────────────────────────────────┐
│                     BokehGlassBackground (Vibrant 5-Orb                 │
│                  Multi-Stop Aurora + AGSL Caustic Shader)               │
└───────────────────────────────────┬─────────────────────────────────────┘
                                    │
        ┌───────────────────────────┼───────────────────────────┐
        ▼                           ▼                           ▼
┌───────────────────┐     ┌───────────────────┐     ┌─────────────────────┐
│   Minimal Home    │     │  PDF / Image /    │     │  Profile › Settings │
│   & Recent List   │     │  Text Pipelines   │     │   › Debug Logs UI   │
└─────────┬─────────┘     └─────────┬─────────┘     └──────────▲──────────┘
          │                         │                          │
          └────────────► ┌──────────▼──────────┐ ──────────────┘
                         │ AppDiagnosticLogger │
                         │ (StateFlow RingBuf) │
                         └─────────────────────┘
```

- **Data Model & State**:
  - `DiagnosticLogEntry`: Holds `id`, `timestampFormatted`, `level` (`INFO`, `WARN`, `ERROR`), `tag` (`PICKER`, `PDF`, `OCR`, `AI`), and `message`.
  - `AppDiagnosticLogger`: Singleton logger recording events from file selection, PDF text/page extraction, OCR recognition, and AI gateway requests/responses, plus `formatAllLogsAsText()` for clipboard export and `clear()`.
- **Interactive Component & State Mapping**:
  - `SettingsMainScreen`: Adds a **Debug Logs** `GlassCard` item (`settings_debug_logs_card`) showing the current log count badge and navigating to `NoticeAppRoute.SETTINGS_DEBUG_LOGS`.
  - `SettingsDebugLogsScreen`: Displays a scrollable list of structured log entries inside a translucent `GlassCard`, with **Copy Logs** (`copy_debug_logs_button`) and **Clear Logs** (`clear_debug_logs_button`) actions and full system Back navigation to Settings.
