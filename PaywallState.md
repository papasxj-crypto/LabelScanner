# FilterPoint Project Architecture & State

## Milestone Status
- Closed Beta is live with testers; the 14-day Google Play testing clock is officially running.
- Cloud Run instances are confirmed scaled to zero (`min-instances = 0`, request-based billing). Idle cost is $0.00.
- All 5 Google Play pre-launch warnings (16 KB alignment, R8 obfuscation, edge-to-edge, large-screen resizability) are resolved.
- Camera crash (FileProvider) and background thread crashes are resolved.
- Autoimmune Protocol (AIP) profile and backend swaps (cassava, nomato sauce) are live.

## Single-Point Profile Responsibility (Locked In)
- HOME SCREEN: Single Point SELECT (Existing profiles only).
- MY HEALTH: Single Point EDIT (Active profile name in `etProfileName` at the top; edit directly renames).
- SETTINGS: Single Point ADD & DELETE (`＋ Add New Profile` auto-navigates to My Health; `🗑 Delete Profile` removes).
- MACRO SLIDERS: "Adjust Macros" button with visible "Reset" button to restore clinical defaults. Never creates duplicate condition checkboxes.

## Current Objective: Phase 2 — Paywall & Google Play Billing
- Task: Build and finalize `BillingManager.kt`, `PaywallActivity.kt`, and `activity_paywall.xml`.
- Business Model: Two-tier freemium:
  - Free Tier: Food scans (barcode & label OCR) against standard default clinical profiles.
  - Pro Tier ($4.99/mo or $39.99/yr with 7-Day Free Trial): Unlocks AI Recipe Adjuster, Cook Mode, Custom Macro Sliders ("Adjust Macros"), and Custom Watchlists.
- The build currently has `buildConfigField("Boolean", "BYPASS_PAYWALL", "true")` for active beta testers. We need to wire real Google Play Billing for the upcoming public release.