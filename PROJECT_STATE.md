# FilterPoint Project Architecture & State

## Application Purpose
FilterPoint is a clinical nutrition shield and recipe adjuster. It scans food items (labels via ML Kit OCR, barcodes via Open Food Facts, produce via Gemini vision) and evaluates them against clinical conditions (CKD Stages 1-3, Stages 4-5, Dialysis, Type 2 Diabetes, GLP-1, Celiac) and custom user watchlists. It generates chemically sound ingredient substitutions to make unsafe recipes safe.

## Core Rules & Non-Negotiables
1. DUAL-URL INJECTION: In RecipeFragment.kt and HomeFragment.kt, the backend URL constant must ALWAYS be:
   private val backendAnalysisUrl by lazy {
   if (BuildConfig.DEBUG) getString(R.string.dev_URL) else getString(R.string.production_URL)
   }
   Never hardcode "https://" inside network calls in these fragments.
2. ONE FILE AT A TIME: Provide one complete compile-ready file or a surgical diff under 20 lines.
3. NO CLONED DISEASE CHECKBOXES: Custom slider overrides save to `custom_<member>.xml`. They must NEVER be parsed into `getAvailableDietProfiles()`.

## Single-Point Profile Responsibility Architecture
- HOME SCREEN (`HomeFragment.kt`):
  Single Point SELECT. Dropdown only lists existing profile names. Tapping one sets it active for scanning, analyzing, and recipe adjusting. Zero add/delete/rename options here.
- MY HEALTH SCREEN (`HealthFragment.kt`):
  Single Point EDIT. Top field (`etProfileName`) displays the active profile name. Editing it directly renames the profile across all SharedPreferences and scoped XML files via `appSettings.renameProfile()`.
- SETTINGS SCREEN (`SettingsActivity.kt`):
  Single Point ADD & DELETE. Contains `＋ Add New Profile` (creates profile, sets it active, and auto-navigates to My Health via `MainActivity.EXTRA_TARGET_TAB_ID`) and `🗑 Delete Profile` (guarded to maintain at least one profile).
- MACRO ADJUSTMENTS (`CustomProfileManager.kt`):
  Button labeled `Adjust Macros`. Contextual `Reset` button (with card background & terracotta outline) appears when custom overrides exist. Bulletproof `resolveNutrientLimits` guarantees sliders seed from the checked condition and never load at zero.

## Navigation & Hardware State Architecture
- BOTTOM NAVIGATION SYNC (`MainActivity.kt`):
  Synchronized via `navController.addOnDestinationChangedListener`. Pressing Android system back (`<`) updates `bottomNav.selectedItemId` so the previous tab does not stay frozen/highlighted.
- COOK MODE (`RecipeDetailFragment.kt`):
  Keeps screen awake during cooking via `view.keepScreenOn`. Automatically resets to `OFF` in `onStop()` when the user minimizes the app or turns off the screen.
- CLINICAL WATCHLISTS (`CustomWatchlistFragment.kt` / `fragment_custom_watchlist.xml`):
  Pinned sticky delete confirmation banner (`layoutConfirmBanner`), quick-add common chips, Avoid (Red) and Caution (Yellow) containers.

## Backend & Persistence Architecture
- Local Evaluation: `LabelEvaluator.kt` evaluates OCR/OpenFoodFacts JSON against active profiles, protein rules, and watchlists.
- Cloud AI: GCP Cloud Run (`GeminiAnalyzer.kt`) processes OCR text/images, produce vision, and recipe adjustments/instructions.
- Offline Cookbook: `SavedPersistenceManager.kt` saves recipes to `filesDir/saved_recipes/saved_<id>.json` with URL/title deduplication.

## Current State & Next Steps
- Status: Navigation, member management unification, Cook Mode, and custom macro calibration are complete and verified.
- Next Phase: Review `PaywallActivity.kt` / `BillingManager.kt` and perform final pre-beta compile verification.