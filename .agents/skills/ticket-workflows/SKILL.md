---
name: ticket-workflows
description: Use when implementing registration, problem reports, violation reports, nickname requests, moderator decisions, ticket messages, or audit history.
---
# Ticket Workflows

Read `docs/flows/TICKETS.md` and `docs/database/SCHEMA.md`.

Use one global numeric ticket sequence. Persist the ticket before posting its Telegram admin card. Treat Telegram messages as projections, never storage. Validate every state transition and actor. Moderator actions must record numeric Telegram ID, username snapshot, UTC timestamp, and audit event. Decision/claim operations must be idempotent so two moderators cannot process the same action concurrently.

Attachments should initially store Telegram file identifiers rather than downloading all media. Never hard-delete tickets through bot commands.
