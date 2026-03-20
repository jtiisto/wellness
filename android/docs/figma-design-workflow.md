# UI Design Workflow — Figma + Google Stitch

Design the native Android UI using Google Stitch for AI-generated mobile screens, Figma for refinement, and Figma Dev Mode for translating designs to Jetpack Compose.

---

## Tools Overview

| Tool | Role | Cost |
|------|------|------|
| **Google Stitch** | AI screen generation from text prompts | Free (350 generations/month) |
| **Figma** | Design refinement, component library, handoff | Free plan works; Professional ($15/mo) for Dev Mode |
| **UX Pilot AI** | Figma plugin for quick iterations | Free (7 screens), $14/mo for 70 screens |
| **Figma Dev Mode** | Developer handoff with Compose code specs | Included in Professional plan |

**Why this stack:**
- Google Stitch (formerly Galileo AI) is purpose-built for mobile UI, aligns with Material Design, and exports to Figma
- Figma is the industry standard for design refinement and developer handoff
- Google Relay (Figma-to-Compose export) was sunset April 2025 — manual Compose translation is now the standard approach

---

## Part 1: Account Setup

### 1.1 Create a Figma Account

1. Go to https://www.figma.com
2. Click "Get started for free"
3. Sign up with email or Google account
4. Choose the **Free plan** to start (upgrade to Professional later if you want Dev Mode)

### 1.2 Install Figma Desktop App (recommended)

1. Go to https://www.figma.com/downloads/
2. Download the desktop app for Windows
3. Install and sign in
4. The desktop app is smoother than the browser version for design work

### 1.3 Create a Google Stitch Account

1. Go to https://stitch.withgoogle.com
2. Sign in with your Google account
3. You get 350 generations/month on the Standard tier (free)

### 1.4 Install UX Pilot AI Plugin (optional, for in-Figma generation)

1. In Figma, go to the hamburger menu (top left) → Plugins → Browse plugins in Community
2. Search "UX Pilot AI"
3. Click "Install"
4. Access it later via: right-click canvas → Plugins → UX Pilot AI

---

## Part 2: Figma Basics (Quick Start)

### Key Concepts

- **File** — A design document. Create one file for the entire wellness app.
- **Page** — A tab within a file. Use one page per module (Journal, Coach, Analysis, Shell).
- **Frame** — A container for a screen design. Each screen is a frame sized to a phone screen.
- **Component** — A reusable design element (button, card, header). Changes to the main component propagate to all instances.
- **Auto Layout** — Figma's flexbox equivalent. Makes designs resize properly.
- **Styles** — Saved colors, text styles, and effects. Maps to Material 3 design tokens.

### Creating Your Project File

1. Open Figma → click "New design file"
2. Rename it: "Wellness Native" (double-click the title)
3. Create pages (tabs at the top of the left panel):
   - `Shell` — navigation, settings, shared components
   - `Journal` — all journal screens
   - `Coach` — all coach screens
   - `Analysis` — all analysis screens
   - `Components` — shared component library

### Setting Up Phone Frames

1. Select the Frame tool (F key, or the # icon in the toolbar)
2. In the right panel under "Design", find the frame presets
3. Select "Android Large" (360 × 800) — this is the standard Android frame size
4. Each screen you design should be its own frame at this size

### Essential Shortcuts

| Action | Shortcut |
|--------|----------|
| Frame tool | F |
| Rectangle | R |
| Text | T |
| Move/select | V |
| Zoom to fit | Shift + 1 |
| Zoom to selection | Shift + 2 |
| Pan | Space + drag |
| Zoom | Ctrl + scroll |
| Duplicate | Ctrl + D |
| Group | Ctrl + G |
| Auto Layout | Shift + A |
| Run plugin | Ctrl + / then type plugin name |

---

## Part 3: AI Screen Generation with Google Stitch

Google Stitch generates high-fidelity mobile screens from text descriptions. Use it to create initial designs, then export to Figma for refinement.

### 3.1 Workflow

1. Open https://stitch.withgoogle.com
2. Start a new project
3. Enter a text prompt describing the screen (see Section 5 for specific prompts)
4. Stitch generates a design — iterate by giving feedback in the chat
5. When satisfied, export to Figma using the Stitch-to-Figma plugin
6. Refine in Figma (adjust spacing, colors, component structure)

### 3.2 Tips for Good Prompts

- **Be specific about the platform:** Always include "Android mobile app" and "Material 3 / Material Design"
- **Describe the data, not just the layout:** "A list showing supplement names with checkboxes and optional quantity fields" is better than "a checklist screen"
- **Mention interaction patterns:** "Bottom sheet for adding a new tracker", "Pull-to-refresh on the list", "Swipe to delete"
- **Reference Material components by name:** "TopAppBar", "BottomNavigationBar", "FloatingActionButton", "Card", "Checkbox", "OutlinedTextField"
- **Specify the color mood:** "Clean, minimal health app with a calming green/teal accent color"

### 3.3 Exporting Stitch → Figma

1. In Stitch, click the export/share button on your design
2. Select "Export to Figma" (or copy the design and paste into Figma)
3. The export preserves layers and structure, making it editable in Figma

---

## Part 4: Refining Designs in Figma

### 4.1 Material 3 Design Kit

Import Google's official Material 3 component library into Figma:

1. Go to https://www.figma.com/community/file/1035203688168086460 (Material 3 Design Kit)
2. Click "Open in Figma" → this creates a copy in your drafts
3. In your Wellness Native file, you can copy components from the M3 kit as needed
4. Or use it as a reference for correct component sizing, spacing, and styling

### 4.2 Setting Up Design Tokens

Create consistent styles that map to your Material 3 theme:

**Colors** (right panel → Local styles → Color styles):
- Primary, OnPrimary, PrimaryContainer, OnPrimaryContainer
- Secondary, Tertiary (same pattern)
- Surface, OnSurface, SurfaceVariant
- Error, OnError
- Sync status colors: green (#4CAF50), red (#F44336), amber (#FFC107), gray (#9E9E9E)

**Typography** (text styles):
- Display Large/Medium/Small
- Headline Large/Medium/Small
- Title Large/Medium/Small
- Body Large/Medium/Small
- Label Large/Medium/Small

Use the Material 3 type scale: https://m3.material.io/styles/typography/type-scale-tokens

### 4.3 Building Reusable Components

On the `Components` page, create components for elements shared across modules:

1. **Sync Status Indicator** — small colored dot (green/red/amber/gray) with optional label
2. **Bottom Navigation Bar** — 3 tabs: Journal, Coach, Analysis with icons
3. **Top App Bar** — title + sync indicator + settings gear
4. **Settings Sheet** — server URL, force sync, debug export, data export
5. **Empty State** — illustration + message for "no data" / "not connected"
6. **Error Toast** — bottom notification for sync errors

To create a component: design the element → select it → right-click → "Create Component" (or Ctrl+Alt+K)

### 4.4 Design Iteration Tips

- **Use Auto Layout** (Shift+A) on everything — it makes designs resize like real UI
- **Name your layers** — double-click layer names in the left panel. Good names like "tracker_card" or "set_entry_row" make Compose translation easier
- **Use variants** for component states — e.g., a Checkbox component with "checked" and "unchecked" variants
- **Keep spacing consistent** — Material 3 uses 4dp increments (4, 8, 12, 16, 24, 32)

---

## Part 5: Screen-by-Screen Design Prompts

Use these prompts in Google Stitch to generate initial designs. Each prompt describes the screen's purpose, data, and key interactions. Refine the outputs in Figma.

### 5.1 Shell / Navigation

**Bottom Navigation + App Shell:**
```
Android mobile app using Material 3 design. Bottom navigation bar with 3 tabs:
Journal (book/diary icon), Coach (dumbbell/fitness icon), Analysis (chart/insights icon).
Each tab has a label and icon. The active tab is highlighted with the primary color.
Above the content area, a TopAppBar with the module name as title, a small colored
sync status dot (green = synced, red = pending changes, amber = conflicts, gray = offline)
next to the title, and a settings gear icon on the right. Clean, minimal health app
aesthetic with a calming teal/green primary color.
```

**Settings Screen:**
```
Android Material 3 settings screen for a health tracking app. Full-screen page
(not a dialog). Sections:

1. "Server" section: text field showing the current server URL (e.g. http://100.x.x.x:9000)
   with an edit button. Connection status indicator below (green "Connected" or
   red "Not reachable").

2. "Sync" section: "Force Sync" button with a description "Re-sync all data with server".
   Last sync time shown as subtitle text.

3. "Data" section: "Export Data" button (exports local data as JSON),
   "Debug Log" button (downloads recent sync logs).

4. "About" section: app version, client ID (short hex string).

Clean, minimal style. Use appropriate Material 3 components (OutlinedTextField,
FilledButton, ListItem with supporting text).
```

### 5.2 Journal Module

**Daily Tracker View (main screen):**
```
Android Material 3 mobile screen for a daily health habit tracker. This is the main
screen of the Journal module.

At the top: a date selector showing today's date (e.g., "Wednesday, Mar 19") with
left/right arrow buttons to navigate between days.

Below: a vertical list of tracked items, grouped by category. Each category has a
subtle header (e.g., "Supplements", "Habits", "Metrics").

Two types of tracker items:
1. Simple trackers: a row with the tracker name and a checkbox (e.g., "Vitamin D ✓",
   "Creatine ☐"). Tapping the row toggles the checkbox.
2. Quantifiable trackers: a row with the tracker name, a numeric input field, and a
   unit label (e.g., "Water [___] glasses", "Sleep [___] hours"). The input field is
   compact, inline.

Each row should be a Card or ListItem with enough touch target size. The list should
feel scannable — this screen is used multiple times per day for quick check-ins.

Floating action button in the bottom-right corner for adding a new tracker.
Calm, minimal health app design with teal/green accent.
```

**Add/Edit Tracker Dialog:**
```
Android Material 3 bottom sheet dialog for adding or editing a tracker in a health
habit app.

Fields:
1. "Name" — OutlinedTextField (e.g., "Vitamin D")
2. "Category" — dropdown/exposed dropdown menu with options like "Supplements",
   "Habits", "Metrics", "Symptoms", plus an option to type a custom category
3. "Type" — segmented button or radio group: "Simple (checkbox)" or
   "Quantifiable (number)"
4. If Quantifiable is selected: additional fields appear:
   - "Unit" — OutlinedTextField (e.g., "mg", "glasses", "hours")
   - "Default value" — number input (optional)

Bottom of the sheet: "Cancel" text button and "Save" filled button.
Clean Material 3 styling.
```

**Conflict Resolution Screen:**
```
Android Material 3 screen showing a sync conflict that needs user resolution.
Health tracker app context.

Header: "Sync Conflict" with an amber warning icon.

Explanation text: "This item was modified on another device. Choose which version
to keep."

Two cards side by side (or stacked on narrow screens):
1. "This Device" card: shows the local version of the data with field values
   (e.g., name, value, completed status, last modified time)
2. "Server" card: shows the server version with the same fields, with differences
   highlighted in a subtle accent color

Below the cards: two buttons:
- "Keep This Device" (outlined button)
- "Keep Server Version" (filled button)

If multiple conflicts exist: a counter at the top ("1 of 3 conflicts") with
a "Resolve All (use server)" text button for batch resolution.
```

### 5.3 Coach Module

**Today's Workout View (main screen):**
```
Android Material 3 mobile screen showing today's workout plan in a fitness coaching
app. This is the main screen of the Coach module.

At the top: today's date and workout title (e.g., "Monday — Upper Body Strength").
Below the title: metadata chips showing location (e.g., "Gym"), phase
(e.g., "Hypertrophy"), and estimated duration (e.g., "75 min").

The workout is organized into blocks. Each block has:
- A block header with the block type and title (e.g., "Main Work: Compound Lifts")
- Duration and rest guidance as subtitle text
- A list of exercises within the block

Each exercise row shows:
- Exercise name (e.g., "Barbell Bench Press")
- Target info as chips or subtitle (e.g., "4×8-10 @ RPE 7", "3×30s")
- A checkbox or completion indicator on the left
- Tapping the row expands it or navigates to the exercise detail

If there are checklist items for an exercise, show them as indented checkboxes
below the exercise name.

If no workout is planned for today, show an empty state: "Rest day" or
"No workout planned" with a calendar icon.

Bottom: a "Complete Workout" button (disabled until exercises are logged).
Athletic but clean design, teal/green accent.
```

**Exercise Logging Detail:**
```
Android Material 3 bottom sheet or full screen for logging an exercise during
a workout. Fitness coaching app.

Header: exercise name (e.g., "Barbell Bench Press") with the target info below
(e.g., "4 sets × 8-10 reps @ RPE 7").

If the exercise has guidance notes, show them in a subtle info card at the top.

Set logging table/list:
Each set is a row with:
- Set number (1, 2, 3, 4...)
- Weight input field (with unit toggle: kg/lbs)
- Reps input field
- RPE input field (optional, 1-10 scale)
- Completion checkbox

Pre-filled with target values where available. The user modifies actuals.

For timed exercises (like planks): show duration input instead of weight/reps.

Below the sets: an optional "Notes" text field for the exercise.

For exercises with checklist items: show checkboxes below the sets section
(e.g., "✓ Retract scapula", "✓ Feet flat on floor").

"Add Set" text button below the last set row.
Compact layout — this is used in the gym with sweaty hands, so inputs should
have generous touch targets.
```

**Session Feedback (post-workout):**
```
Android Material 3 screen for post-workout feedback. Shown after tapping
"Complete Workout".

1. "Any pain or discomfort?" — multi-line text field with placeholder
   "Describe any issues..." (optional)
2. "General notes" — multi-line text field with placeholder
   "How did the workout feel?" (optional)

Summary section below: compact stats of the completed workout
(exercises completed: 8/10, total sets: 24, workout duration).

"Submit" filled button at the bottom.
Keep it simple — the user just finished working out and wants this to be quick.
```

**Workout Calendar/History:**
```
Android Material 3 screen showing a monthly calendar view of workouts.
Fitness coaching app.

Calendar grid showing the current month. Days with planned workouts have a
colored dot below the date number. Days with completed logs have a filled
dot or checkmark. Rest days are plain.

Tapping a day with a workout shows a preview card below the calendar:
workout title, completion status, and a "View Details" link.

Navigation: left/right arrows to change months. "Today" chip to jump back.
```

### 5.4 Analysis Module

**Query Selection Screen (main screen):**
```
Android Material 3 mobile screen for selecting an AI health analysis query.
This is the main screen of the Analysis module.

Header: "Analysis" with a subtitle "AI-powered health insights".

A vertical list of available query cards. Each card shows:
- Query label (e.g., "Post-Workout Analysis", "Weekly Review",
  "Pre-Workout Briefing")
- Short description below the label (e.g., "Analyze your latest workout
  performance and recovery")
- A play/run icon button on the right side of the card

Some queries have an input field that appears when selected
(e.g., "Location" text field for pre-workout briefing that accepts
location context).

"Run" filled button at the bottom (or per-card).

Below the query list: a "Recent Reports" section header with a list of
previously generated reports showing: query label, date generated, and
a "View" link. Each report row is compact.
```

**Report Loading/Progress:**
```
Android Material 3 screen showing an AI analysis report being generated.
Health analysis app.

Center of screen: a circular progress indicator (indeterminate).
Below it: "Generating your report..." text.
Below that: elapsed time counter (e.g., "Running for 45 seconds").
Below that: subtle text "The AI is analyzing your health data using
journal entries, workout logs, and other available data."

At the bottom: "Cancel" text button.

Clean, minimal, centered layout. The user should feel like something
meaningful is happening, not just waiting.
```

**Report View:**
```
Android Material 3 mobile screen displaying a completed AI health analysis
report. The report content is markdown rendered as rich text.

Top bar: query label as title (e.g., "Post-Workout Analysis"), with the
generation date as subtitle. Share icon and delete icon in the top bar.

Content area: scrollable rendered markdown. Should support:
- Headers (h1, h2, h3)
- Bold, italic text
- Bulleted and numbered lists
- Tables (data tables with health metrics)
- Code blocks (if present)
- Horizontal rules as section dividers

The markdown content discusses health topics like workout performance,
supplement adherence, recovery recommendations. Use placeholder content
that looks like a real health analysis.

Bottom: "Run Again" outlined button to re-run the same query.
Text should be readable — use Body Large (16sp) as the base text size.
```

---

## Part 6: Design-to-Code Translation

Since Google Relay is no longer available, translation from Figma to Jetpack Compose is done manually, assisted by Figma's developer tools.

### 6.1 Using Figma Dev Mode (Professional plan, $15/mo)

1. In Figma, switch to Dev Mode (toggle in the top toolbar, or Shift+D)
2. Click any element to see:
   - Exact dimensions, padding, margins
   - Colors (with your defined style names)
   - Typography specs (font, size, weight, line height)
   - Border radius, shadows, opacity
3. Code panel shows generated code — select "Android" to see values in dp/sp units
4. Use these specs to write Compose code with exact values

### 6.2 Using Figma's MCP Server (for AI-assisted code generation)

Figma offers an MCP server that brings design context into coding tools:

1. In Dev Mode, select a component or screen
2. The MCP server provides structured design data to AI coding assistants
3. Claude Code can read this context to generate more accurate Compose code
4. This is the closest replacement for what Relay used to do

### 6.3 Manual Translation Cheat Sheet

| Figma Concept | Compose Equivalent |
|---------------|-------------------|
| Frame with Auto Layout (vertical) | `Column` |
| Frame with Auto Layout (horizontal) | `Row` |
| Fixed size frame | `Box(modifier = Modifier.size(width.dp, height.dp))` |
| Fill container | `Modifier.fillMaxWidth()` |
| Spacing between items | `Arrangement.spacedBy(X.dp)` or `Spacer` |
| Padding | `Modifier.padding(X.dp)` |
| Corner radius | `shape = RoundedCornerShape(X.dp)` |
| Drop shadow | `Modifier.shadow(elevation.dp)` |
| Text style | `Text(style = MaterialTheme.typography.bodyLarge)` |
| Color style | `MaterialTheme.colorScheme.primary` |
| Image/icon | `Icon(Icons.Default.Name)` or `Image()` |

### 6.4 Recommended Workflow for Each Screen

1. **In Figma:** finalize the screen design
2. **Extract design tokens:** note all colors, text styles, spacing values
3. **Identify components:** which parts are reusable Compose components?
4. **Write Compose top-down:** start with the screen scaffold, then fill in sections
5. **Match specs:** use Dev Mode measurements for exact padding, sizing
6. **Preview:** use Compose Preview annotations to verify without deploying

---

## Part 7: Design Workflow Timeline

This maps to the implementation phases in plan.md:

### During Phase 0-1 (while building data/sync layer)

- Set up Figma account and project file
- Import Material 3 Design Kit
- Familiarize yourself with Figma basics using the Shell screens as practice
- No pressure to finalize anything — these are throwaway explorations

### Phase 2 (dedicated design phase, parallel with Phase 1 completion)

1. **Week 1: Generate and iterate**
   - Use Google Stitch prompts from Section 5 to generate all screens
   - Export each to Figma, organize into the correct page
   - Don't refine yet — get all screens generated first

2. **Week 2: Refine and systematize**
   - Build the shared component library (Components page)
   - Apply consistent color and typography styles across all screens
   - Ensure all screens use the same spacing rhythm (4dp increments)
   - Add interaction notes (what happens when you tap X, swipe Y)
   - Design empty states, loading states, and error states for each screen

3. **Week 3: Review and finalize**
   - Walk through each module as a user flow (open app → navigate → perform task)
   - Check that all sync states are represented (synced, pending, conflict, offline)
   - Verify touch targets are at least 48dp × 48dp
   - Export design tokens for the Compose theme

### Phase 4 (UI integration)

- Translate each screen from Figma to Compose, one module at a time
- Use Dev Mode for exact measurements
- Build shared Compose components first (from the Components page), then screens

---

## Part 8: File Organization in Figma

```
Wellness Native (file)
├── Shell (page)
│   ├── Bottom Navigation
│   ├── Settings Screen
│   ├── Server Connection Screen
│   ├── Empty State (no connection)
│   └── Sync Status States (green/red/amber/gray)
├── Journal (page)
│   ├── Daily Tracker View
│   ├── Add/Edit Tracker (bottom sheet)
│   ├── Conflict Resolution
│   ├── Journal Settings
│   ├── Empty State (no trackers)
│   └── Loading / Syncing State
├── Coach (page)
│   ├── Today's Workout
│   ├── Exercise Logging Detail
│   ├── Session Feedback
│   ├── Workout Calendar
│   ├── Empty State (rest day)
│   ├── Empty State (no plan)
│   └── Loading / Syncing State
├── Analysis (page)
│   ├── Query Selection
│   ├── Report Loading
│   ├── Report View
│   ├── Report History
│   └── Empty State (no reports)
└── Components (page)
    ├── Sync Status Indicator
    ├── Bottom Nav Bar
    ├── Top App Bar
    ├── Tracker Row (simple)
    ├── Tracker Row (quantifiable)
    ├── Exercise Row
    ├── Set Entry Row
    ├── Query Card
    ├── Report Preview Card
    ├── Error Toast
    └── Empty State Template
```
