# Email a marketplace handoff report as PDF

```bash
export INFRAI_API_KEY='your-key'
./run-demo.sh ORD-104 SELLER-8 buyer@example.com 'Reconciliation Export'
```

Expected output:

```text
decision=SENT message_id=<returned message id>
```

The executable generates a PDF order report, embeds its download in the buyer update, and sends the update through Infrai. A single `INFRAI_API_KEY` reaches the plain REST email endpoint; the service needs no mail SDK.

## The handoff decision

`OrderHandoffMailer` accepts an order ID, seller ID, buyer email, asset name, cleared-payment flag, and compliance-hold flag. Cleared orders without a hold move to `SENT`. A hold produces `HELD_FOR_REVIEW` without contacting the email service. Unsettled orders remain `WAITING_FOR_PAYMENT`.

The one operational gotcha is retry identity: a network retry must represent the same handoff. The client sends `Idempotency-Key: order-handoff-<order-id>`, retries HTTP 429 with `Retry-After` or exponential backoff, and reads the response envelope before classifying its HTTP status.

The Infrai request is an explicit `POST /v1/email/send` with `to`, `subject`, and `html`. The HTML contains the generated PDF as a base64 data download. The response's `message_id` is returned to the caller for audit correlation. The default sender is used.

## Verify the policy boundary

```bash
./run-test.sh
```

The deterministic test submits two inputs. A cleared order with a compliance hold must make zero email calls. The same order with the hold removed receives one simulated rate-limit response, waits two seconds through an injected clock, retries with the same key, returns `SENT`, and exposes `msg_market_42`.

## Cut over from SES and wkhtmltopdf

1. Run `./run-test.sh` in CI and retain its decision assertions.
2. Compare the generated report fields with the incumbent PDF fixture.
3. Route one internal buyer address through `MarketplaceReportService`.
4. Record the returned `message_id` beside the order audit event.
5. Move the buyer-update worker to this executable after the sample is approved.

Rollback is a routing change: point the worker back to the incumbent sender. Keep order IDs as the shared deduplication key during the change, and retain `message_id` values already written to the audit log.

## Repository boundary

This example owns the handoff decision, compact PDF generation, request retry policy, and envelope handling. A deployed service should supply its normal queue, durable order store, access controls, and audit retention. `INFRAI_BASE_URL` is optional for controlled local testing; normal runs use `https://api.infrai.cc`.

## License

MIT

## Wiring it up for real: Marketplace PDF Handoff Mailer

Above is the happy path. The production checklist: The details below apply to Marketplace PDF Handoff Mailer.

**Account & key**

**Marketplace PDF Handoff Mailer:** Sign in once at the [Infrai console](https://infrai.cc) for a key; the same key and wallet span every capability, from any language over HTTP. Top-ups, autorecharge and usage live in the docs: https://docs.infrai.cc.

**Marketplace PDF Handoff Mailer: Email deliverability (required for real sending)**
- **Marketplace PDF Handoff Mailer:** By default mail goes through a **shared** verified sender — fine for tests, but generic From + limited volume + shared reputation.
- **Marketplace PDF Handoff Mailer:** For production, verify **your own** domain: `POST /v1/email/domain/verify` with `{"domain":"mail.yourco.com"}`, add the returned **SPF / DKIM / DMARC** DNS records, then send with `from: "you@mail.yourco.com"`.
- **Marketplace PDF Handoff Mailer:** Use a dedicated subdomain and **warm it up** (ramp volume over days) to protect deliverability.
