# HomeSidePanel Weather Prototype Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a standalone browser prototype that demonstrates the approved split “日期与时间” and “天气” card design in light, dark, narrow, and wide layouts.

**Architecture:** Use one self-contained `index.html` because this is a temporary visual review artifact rather than production application code. Semantic HTML defines the side-panel context, CSS custom properties implement Material 3 light/dark tokens and responsive card layouts, and a small inline script handles theme switching, simulated weather refresh, and long-press settings preview.

**Tech Stack:** HTML5, CSS custom properties, vanilla JavaScript, Material Symbols Outlined web font, Chromium headless screenshots

## Global Constraints

- Do not modify Android production code in this phase.
- The date/time card is display-only and contains time, date, and greeting only.
- The weather card owns click-to-refresh and long-press-to-settings interactions.
- Use Material 3 semantic surfaces, dark mode, Outlined icons, stable dimensions, and no gradients or nested cards.
- Do not add a test framework or production abstraction for this visual prototype.

---

### Task 1: Build and visually verify the standalone prototype

**Files:**
- Create: `prototypes/home-side-panel-weather/index.html`

**Interfaces:**
- Consumes: the approved design in `docs/superpowers/specs/2026-08-04-home-side-panel-weather-card-design.md`
- Produces: a directly usable browser prototype at `prototypes/home-side-panel-weather/index.html`

- [ ] **Step 1: Create the panel structure and theme tokens**

Create one semantic document with:

- A centered phone/sidemenu viewport containing a restrained account header and surrounding panel context.
- A display-only `.date-time-card` with `#current-time`, `#current-date`, and `#greeting` elements.
- An interactive `.weather-card` with city, update time, current temperature, feels-like temperature, an Outlined weather icon, condition, and three bottom metrics.
- A hidden `.weather-settings` panel that replaces the weather content after a long press and includes a back icon button.
- A theme icon button outside the panel preview so it does not alter the Android UI composition being reviewed.

Define light and dark Material 3-style CSS variables for `surface`, `surface-container-low`, `primary-container`, `on-surface`, `on-surface-variant`, `outline-variant`, and `primary`. Use a maximum panel width around 420px, 24px card radii, 16px card gaps, and stable weather-card dimensions.

- [ ] **Step 2: Implement the approved card layouts**

Implement the date/time card as a two-column grid: large `HH:mm` at left, date and greeting at right. At narrow widths reduce horizontal gaps while preserving two columns and fixed typography hierarchy.

Implement the weather card with:

- Header row: city/district left and short update time right.
- Main row: large temperature and feels-like left; weather icon and condition right.
- Bottom row: three equal metrics for high/low, humidity, and wind.
- Single-line ellipsis for city and condition.
- Visible focus and hover states clipped to the card radius.

- [ ] **Step 3: Add review interactions**

Add inline JavaScript functions:

```js
function applyTheme(theme) { /* set data-theme and update button state */ }
function refreshWeather() { /* show stable loading state, then update sample values */ }
function openWeatherSettings() { /* replace weather card content after long press */ }
function closeWeatherSettings() { /* restore weather content */ }
```

Use pointer events to distinguish a normal click from a 550ms long press. Cancel the long press when the pointer moves beyond 12px. A successful long press must suppress the following click refresh. Use an `aria-live` toast for refresh feedback and keep the card height stable throughout loading.

- [ ] **Step 4: Render and inspect representative viewports**

Start a static server from the prototype directory:

```bash
python3 -m http.server 4173 --directory prototypes/home-side-panel-weather
```

Capture Chromium screenshots at desktop and narrow widths:

```bash
chromium --headless --disable-gpu --hide-scrollbars --window-size=1440,1000 --screenshot=/tmp/home-side-panel-weather-desktop.png http://127.0.0.1:4173/
chromium --headless --disable-gpu --hide-scrollbars --window-size=390,844 --screenshot=/tmp/home-side-panel-weather-mobile.png http://127.0.0.1:4173/
```

Inspect both screenshots for text overlap, clipped metrics, unstable card dimensions, excessive empty space, and sufficient contrast. Repeat the mobile capture with dark theme selected through the prototype query or persisted theme state, then correct any visible defects.

- [ ] **Step 5: Check file quality and report the preview URL**

Run:

```bash
git diff --check -- prototypes/home-side-panel-weather/index.html
```

Keep the local server running and provide `http://127.0.0.1:4173/` for user review. Report that Android code has not changed and invite concrete visual feedback before Compose implementation.
