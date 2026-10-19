# Changelog

## 1.7.0 — 2026-10-20

- Added named provider profiles with separate URLs, protocols, credentials, cached model lists and defaults; conversation state stores only the profile ID, never credentials.
- Enabled read-only metadata inspection for every protocol, including Anthropic-compatible gateways; remote providers use their standard models catalog.
- Distinguished declared context, loaded instance context, API input caps and local working budgets. Missing context fields and authentication errors are now explicit.
- Added an optional conservative GonkaRouter working budget of 180000 tokens, based on the published approximate 200K input cap; it is not presented as API-confirmed context.
- Preserve manual budgets when a catalog omits context; prevent late model lists from overwriting another provider selection.
- Use the saved provider name or gateway host in new reply labels instead of calling every Anthropic-format model Claude.
- Added HTTP and UI tests for named providers, credential isolation, chat resume, restart and external metadata, with six-language/theme rendering.

## 1.6.0 — 2026-10-20

- Verify installed engine contents against the tested snapshot regardless of legacy file dates.
- Connected extraction, confirmed V/W pairing, checking, visual revision comparisons and report regeneration to the original PDFCONTROL engine.
- Restored original PDF reports with available annotated drawing annexes; reports are actual files, not chat substitutes.
- Added a direct PDFCONTROL panel in six languages and both themes, with progress, cancellation and opening saved PDFs; no model request required.
- Added isolated jobs, source integrity manifests, explicit partial results and bounded paginated status responses.
- Detect workers that exited unexpectedly and retry atomic status writes on Windows sharing conflicts.
- Added real-engine MCP and Java panel tests on synthetic drawings, alongside existing UI/session tests.
- Made chat selection restoration transactional to prevent intermediate controls from saving incomplete state.

## 1.5.0 — 2026-10-19

- Slimmed footer controls to 26px, added vector send/stop/new-chat/folder icons and a rotating busy indicator.
- Added right-click rename and removed internal IDs from visible sidebar titles.
- Replaced the compose Downloads action with the selected working folder and its name.
- Context estimates update on draft edits and streamed deltas, with a 200ms dashboard refresh.
- Automatically read LM Studio metadata after a stable model/server selection and once per minute; fixed a stuck in-flight flag preventing further refreshes. Stale responses cannot change another model profile.
- Explicit settings snapshots now restore response profile, stream, tool mode, approvals, system prompt and auto-compaction alongside model/API/folder.
- Clarified that files produced by tools are already on disk; saving an extra copy remains optional.
- Verified all six languages, both themes, metadata retry/recovery, live streaming activity and complete settings restoration.

## 1.4.0 — 2026-10-19

- Added recoverable deletion, archived/active views, context menus and JSON/ZIP chat export.
- Added explicit settings/default model saving, cached model lists and portable historical project path correction.
- Added native LM Studio metadata inspection with separate theoretical maximum and loaded context; unknown stays unknown and chat loading remains offline.
- Added bottom context ring and model, reply-budget, tool-mode and permission selectors. Reply budgets are not reasoning levels.
- Added enforced read-only mode, including saved rules, writes and commands.
- Translated every new control into all six languages and extended offline integration tests.

## 1.3.0 — 2026-10-19

- Added per-chat automatic compaction controls in all six UI languages: enable checkbox and 30–95% threshold (default 70%).
- Requires an explicitly configured context limit; no automatic API request is made on chat load.
- Before a user message, compacts eligible older history at the threshold and sends the unchanged pending message once after success.
- Editing the draft or pressing Stop prevents automatic sending. Failed compaction preserves both draft and existing memory.
- Kept original archives, token accounting and automatic preferences isolated per conversation.
- Added a context preflight guard to subsequent tool-loop requests as well as the initial user request.

## 1.2.0 — 2026-10-19

- Added a searchable session sidebar with single-click resume, custom titles and pins.
- Saved each chat's model/provider, working folder, draft, context limit, usage and compact memory independently.
- Preserved old conversation files; loading a chat never makes an API request.
- Added an estimated next-request context breakdown; unknown limits remain unknown until configured.
- Captured server token usage for OpenAI-compatible and Anthropic requests, including streaming, caching and tool loops.
- Kept last-response and per-chat totals separate; missing or interrupted usage is marked incomplete.
- Added /status, /model, /new, /stop, /token, /history, /compact, /list, /switch, /resume, /title, /pin, /skills, /connectors, /context and /help, with localized suggestions.
- Added manual per-chat compaction: a factual model-generated summary plus the latest two exchanges is sent next time; the original archive is retained in full.
- Added verified local report references with Open, Open folder and Save a copy actions.
- Translated all new controls, descriptions and statuses into all six UI languages.
- Added offline loopback API and session/compaction integration tests before deployment.

## 1.1.0 — 2026-10-19

- Added FlatLaf Light and Dark themes, switchable without restarting.
- Added offline UI languages: Nederlands, Română, Español, Polski, English, Türkçe.
- Preserved drafts, streaming transcript, provider settings and permissions when changing appearance.
- Saved language and theme preferences beside the portable application.
- Displayed the version in the window title, appearance bar and About menu; VERSION is the single build source.
- Added an offline UI regression suite and build gate before updating the working distribution.
- Build output defaults to the sibling LocalChat folder; local settings and conversation history are retained.
