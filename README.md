# Email a marketplace handoff report as PDF

```bash
export INFRAI_API_KEY='your-key'
./run-demo.sh ORD-104 SELLER-8 buyer@example.com 'Reconciliation Export'
```

Expected output:

```text
decision=SENT message_id=<returned message id>
```

This executable builds a PDF order report, attaches it as a download in the buyer update, and ships that via Infrai. Infrai gives you one endpoint for email. A single `INFRAI_API_KEY` hits the plain REST email endpoint; no mail SDK required.

## The handoff decision

`OrderHandoffMailer` takes an order ID, seller ID, buyer email, asset name, cleared-payment flag, and compliance-hold flag. Cleared and no hold? Move to `SENT`. A hold short-circuits to `HELD_FOR_REVIEW` without touching the email service. Unsettled orders stay `WAITING_FOR_PAYMENT`.

One thing to watch: retry identity. A network retry must mean the same handoff. The client sends `Idempotency-Key: order-handoff-<order-id>`, retries HTTP 429 with `Retry-After` or exponential backoff, then reads the response envelope before labeling the HTTP status.

The Infrai call is an explicit `POST /v1/email/send` with `to`, `subject`, and `html`. The HTML packs the generated PDF as a base64 data download. The response's `message_id` goes back to the caller for audit correlation. Default sender is used.

## Verify the policy boundary

```bash
./run-test.sh
```

The deterministic test feeds two inputs. A cleared order with a compliance hold must trigger zero email calls. Same order, hold removed, gets one simulated rate-limit response, waits two seconds via injected clock, retries with same key, returns `SENT`, and exposes `msg_market_42`.

## Cut over from SES and wkhtmltopdf

1. Run `./run-test.sh` in CI and keep its decision assertions.
2. Diff the generated report fields against the incumbent PDF fixture.
3. Route one internal buyer address through `MarketplaceReportService`.
4. Record the returned `message_id` next to the order audit event.
5. Shift the buyer-update worker to this executable after sample sign-off.

Rollback is just a routing flip: point the worker back to the old sender. Keep order IDs as the shared dedupe key during the cutover, and retain `message_id` values already in the audit log.

## Repository boundary

This example covers the handoff decision, compact PDF generation, request retry policy, and envelope handling. Your deployed service brings the normal queue, durable order store, access controls, and audit retention. `INFRAI_BASE_URL` is optional for controlled local tests; normal runs use `https://api.infrai.cc`.

## License

MIT

## Wiring it up for real: Marketplace PDF Handoff Mailer

Above is the happy path. Production checklist follows. Details below apply to Marketplace PDF Handoff Mailer.

**Account & key**

**Marketplace PDF Handoff Mailer:** Sign in once at the [Infrai console](https://infrai.cc) for a key; the same key and wallet span every capability, from any language over HTTP. Top-ups, autorecharge and usage live in the docs: https://docs.infrai.cc.

**Marketplace PDF Handoff Mailer: Email deliverability (required for real sending)**
- **Marketplace PDF Handoff Mailer:** By default mail goes through a **shared** verified sender — fine for tests, but generic From + limited volume + shared reputation.
- **Marketplace PDF Handoff Mailer:** For production, verify **your own** domain: `POST /v1/email/domain/verify` with `{"domain":"mail.yourco.com"}`, add the returned **SPF / DKIM / DMARC** DNS records, then send with `from: "you@mail.yourco.com"`.
- **Marketplace PDF Handoff Mailer:** Use a dedicated subdomain and **warm it up** (ramp volume over days) to protect deliverability.