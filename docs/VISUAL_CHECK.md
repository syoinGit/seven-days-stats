# Portal MVP visual check

## Confirmed surfaces

Rendered Thymeleaf output and responsive CSS were checked together with `PortalWebMvpTests`.

| Surface | Result |
| --- | --- |
| Anonymous Portal | Dark gateway panel, GAME//PORTAL brand, invite-only copy, one primary login action |
| Authenticated Portal | Watchpoint and Luminite cards, distinct amber/violet accents, all required status and progress fields |
| Portal interaction | Only an `offline` card can start; the sending state disables repeat clicks, accepted mock requests change the chip to `starting`, and timeout/API failures use an ARIA live region without falsifying server state |
| Luminite | Violet star/mineral theme, progress/server/connect summary, full Terraria event title/body, Terraria-only timeline, and empty-state copy |
| Watchpoint | Same page structure as Luminite: pill tabs inside a 1240px shell, the page in a wide column with cards in a sticky side column (timeline), one column for detail pages; amber accent, timeline and detail content unchanged |
| Mobile CSS | At 720px and below, cards and Luminite summary collapse to one column and actions become full-width rows |
| Keyboard/accessibility | Native links/buttons, visible `:focus-visible` outline, labelled game list, live start result, semantic headings and ordered timeline |

The test render verifies that an anonymous visitor cannot render Luminite, both authenticated game
cards render, the Terraria empty state renders, and security headers including CSP and frame denial
are present. A local H2-backed runtime smoke test also completed a real form login, loaded Portal and
Luminite with HTTP 200 responses, and accepted the CSRF-protected mock start request as `starting`.

No browser engine was available in this workspace, so a pixel screenshot was not generated. The
production checklist therefore retains a real desktop/mobile browser smoke test before DNS cutover.
