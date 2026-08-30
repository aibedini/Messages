# Files

- [Gateway Startup, Reboot Recovery, and Self-Heal](gateway-lifecycle.md) - How the SMS gateway goes from a user toggle (or a boot/START_STICKY recovery) to a fully reconciled running state, and how network loss, a stale DHCP bind, a bind failure, and component death are each healed by the single ConnectionSupervisor reconcile loop.
- [Workflow: Incoming Message to UI, Webhook, and Notification](incoming-message-pipeline.md)
- [Workflow: Sending (UI, REST, EVE, Scheduled)](send-pipeline.md) - End-to-end trace of every send entry point (chat UI, long-press schedule, REST /api/v1/sms/send, EVE /send queue, GMweb pull) through the SmsSender funnel, provider dispatch, durable SENT/DELIVERED callbacks, the send_segments ledger, and the UI/Room updates — including the two reboot-proof scheduling paths and the 503-vs-200 machine-outcome contract.
