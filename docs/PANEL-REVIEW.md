# The panel, seen running

Written 11 September 2026, after the first time anyone opened this panel in a
running BOSS and looked at it properly.

**Since written, four and five are fixed and the call list has been rebuilt** as
the activity view described in the README, because a tracer nobody can read is not
a tracer. One, two, three, six and seven are untouched and still stand. They are
layout decisions worth making deliberately rather than in the same pass that found
them.

Judged against BossConsole 9.5.11, light theme, a 1920 wide window, the panel
docked where `plugin.json` puts it. Ordered by what I would change first.

## 1. At its default height the panel shows nothing it exists to show

Opening it from the tool picker gives a strip about 190px tall. What fits is the
gateway card and the word "Policy". The session line, the host-gap line and the
whole call list are below the fold, and there is no scrollbar on the panel itself,
so nothing signals that more exists. `docs/panel.png` is not a bad crop, it is
what the panel actually looks like when you open it.

The layout assumes vertical room it is not given. Five cards stacked at fixed
heights with `Ledger` taking `weight(1f)` means the ledger gets whatever is left,
and at the default height there is nothing left.

Worth considering: collapse the gateway card to one line once it is running,
since the endpoint is the only thing an operator copies and they copy it once.

## 2. Enormous empty space, then a wall of it

Dragged tall, the Calls card is around 350px of blank with one line of grey text
at the top left. The empty state is well written and badly placed. Everything
else sits in the top third of a full-width panel with the right two-thirds empty.

The panel is full width. Nothing in it uses that width except the two action
groups, which are pinned to the far right edge.

## 3. The actions are 1700px from the thing they act on

"Export" and "End + report" sit at x=1780 while "Agent session" sits at x=22.
Same for "Stop" against "Gateway running". On a narrow panel `weight(1f)` reads
as "label left, action right". At this width the association is gone, and the
eye has to travel the whole screen to connect them.

## 4. A call in flight is invisible

`ApprovalCoordinator.pending` exists and its KDoc says it is there "so the panel
can say why it is waiting". The panel never reads it. `WardenRuntime` exposes it
as `pendingApproval` and nothing consumes that either.

Live, this means: the dialog is up, a shell command is held, and the panel behind
it still reads "0 calls". After approval the call can sit in `forward` for two
minutes and the panel shows nothing for it. The operator has no way to see that
the gateway is holding something.

This is the one I would fix first after the layout, because it is the difference
between a panel you watch and a panel you check afterwards.

## 5. The grant timer does not tick

`GrantsRow` calls `runtime.grants.remaining()` directly. That is a plain
`ConcurrentHashMap` read, not Compose state, so the row renders whatever the
countdown was at the last recomposition and then freezes. A grant that expires
does not remove its own card; a grant that is created does not show one until
something else recomposes the panel, which in practice means the next call
landing. "Execute commands for 19m 43s more" is a number that stops being true
one second after it is drawn.

## 6. Four zeros

"0 calls · 0 stopped · 0 file changes · 0 agent notes" is four counters of which
three are almost always zero. Rendering only the non-zero ones, or only "no calls
yet", would say the same thing with less.

## 7. Small things

- The `Card` composable draws a border and a background on `SurfaceColor` over
  `BackgroundColor`. In the light theme those two are close enough that the cards
  read as one continuous surface with hairlines across it.
- "Calls" as a heading is thinner than the section deserves given it is the point
  of the panel.
- The ledger rows are good. Dot, tool name in mono, outcome in its colour, time,
  then the redacted preview truncated to one line. That part needs nothing.
- Newest-first is right and was immediately legible with three rows of different
  outcomes.

## What is already right

The empty states say what will fill them. The policy card states the refusal that
cannot be approved, in the UI rather than only in the README. The host-gap line
appears only once a session has earned it. Colours come from `BossThemeColors`
throughout and the panel looked native beside BOSS's own chrome.
