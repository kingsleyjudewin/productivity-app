# DAYZERO — Money (Phase 4)

## What it does
- **MONEY tab**: spent today, budget, remaining, payment count, status (Within budget / Over by ₹X / No budget set).
- **Per-day budgets** (`budgets` table). A day's limit is its own row, else the latest earlier row. Setting
  "from today" or "from tomorrow" never rewrites earlier days. Quick options ₹100/200/300/500 or custom; currency
  and day-reset time come from Settings (same `DayKeys` logic as tasks and screen time).
- **Automatic tracking** via `NotificationListenerService` (`payments/PaymentListenerService`), for payment apps the user ticks
  in *MONEY → Payment apps*. Offered: Google Pay, PhonePe, Paytm, BHIM, CRED, MobiKwik, Freecharge (only those installed).
- **Manual entry / edit / delete / categories** (Food, Transport, Shopping, Education, Entertainment, Bills, Other);
  auto-detected rows can be corrected and categorised. A correction keeps the originally detected amount/type.
- **History** with filters Today / Yesterday / 7 days / Month / All, showing spent / received / refund / failed / pending / unverified and auto vs manual.
- **BUDGET EXCEEDED** notification: "You have crossed your daily spending limit by ₹40." Fires once per crossing
  (`budgets.exceededNotifiedAt`), is cleared when a delete/edit/budget change drops spending back under the limit, and can
  fire again on a later crossing. Never fires for past days.
- **Home** shows spent / budget and "₹X left" or "Over by ₹X" from live Room queries.

## What counts as spending
Only `EXPENSE`. `INCOME`, `REFUND`, `FAILED`, `PENDING`, `UNKNOWN` are stored but never counted. A `PENDING` payment that later
succeeds is upgraded in place (not double-counted). `UNKNOWN` = the parser was not sure; it waits for the user to confirm it in History.

## Parsing (`domain/money`)
`PaymentTextAnalyzer` is a shared, conservative rule engine; `GooglePayParser`, `PhonePeParser`, `PaytmParser` add per-app hints, and
`GenericUpiParser` handles any other selected app. Order: drop OTP-shaped text → drop reminders/requests/upcoming debits/promos →
strip cashback and balance figures → failed/pending → refund/income/expense → exactly one distinct amount, else `UNKNOWN`.
Amounts are integer paise.

## Duplicates
1. Same app + payment reference ⇒ same transaction (unique `dedupeKey`).
2. Otherwise same app + amount + compatible merchant inside 3 min (10 min vs a manual entry, 30 min for pending→final); different
   references are never merged, so genuine repeat payments both count.
3. `notification_seen` stores a SHA-256 fingerprint of (app, notification key, post time, text) so re-delivery after a restart or
   listener reconnect is ignored — even for a transaction the user deleted. Pruned after 14 days.

## Privacy
Android gives a notification listener *every* notification, so the service compares the package with the user's selection first and
drops everything else without reading it. Notification text is parsed in memory and discarded: only amount, merchant, reference, time and
source app are stored. No SMS, no OTP, no bank data, no network (the app declares no INTERNET permission).

## Database
Version 4 (`MIGRATION_3_4`): `transactions` + category, txnRef, originalAmountMinor, originalType, userEdited; types `SPENT→EXPENSE`,
`RECEIVED→INCOME`; `budgets` + exceededNotifiedAt; new `notification_seen`; the old single global budget becomes a day-zero budget row.
Migrations tested from v1, v2 and v3.

## NOT verified
No device or emulator was available. **The parsers were tested only against synthetic text written from commonly seen wording — never
against real Google Pay / PhonePe / Paytm notifications — so real-app compatibility is unproven.** Also untested on a device: the
notification-access flow, listener survival on Vivo/Funtouch (it may be killed in the background; re-enable in Notification access if tracking stops),
how the screens look, and the exceeded notification being delivered. Expect to tune the patterns after capturing real samples; anything not
understood falls back to `UNKNOWN`, which is safe (not counted) but needs manual confirmation.
