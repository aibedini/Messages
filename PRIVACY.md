# Messages Privacy Policy

Effective date: August 23, 2026

Messages (`com.autonomousone.messages`) is an open-source SMS and MMS client. This policy explains how the Android application handles personal and sensitive data.

## Core messaging

When you choose Messages as the default SMS application, it requests access to read, receive, write and send SMS/MMS messages. This access is required to display conversations and to send and receive messages at your direction. Message content remains on the device unless you separately enable SMS Gateway.

Contacts access is optional and is used on the device to display contact names and photos. Notifications are optional and alert you about incoming messages. Location is requested only when you explicitly use location sharing in a conversation.

## SMS Gateway — optional data transmission

SMS Gateway is disabled by default and requires separate, versioned consent. When enabled, it may transmit the sender phone number, full SMS text, message timestamp and event identifier, device model, Android and app versions, a per-installation identifier, gateway status and heartbeat time.

This information is sent over HTTPS to `https://gaitway.autonomousone.in` and, if configured by you, to your HTTPS webhook. The receiving service controls retention after receipt. Tokens and secrets are protected with Android Keystore encryption when available.

Authenticated gateway clients can request that the phone send SMS messages. Carrier charges may apply. The local API requires its generated API key; cloud access uses an encrypted bearer token.

The application does not use message or contact data for advertising and does not sell it. Gateway data is transmitted only to provide the functionality explicitly enabled by the user.

## Control and deletion

You can stop SMS Gateway or select **Revoke consent and stop Gateway** at any time. Revocation stops registration, heartbeat and forwarding and removes stored cloud credentials. Clearing application data or uninstalling deletes local preferences and credentials. Contact the operator of a configured backend or webhook to delete data it already received.

## Security and contact

Gateway credentials, registration and webhook events require HTTPS. Optional webhook payload signing uses HMAC-SHA256. Source and releases are at <https://github.com/aibedini/Messages>. For privacy or security questions, open an issue at <https://github.com/aibedini/Messages/issues>.
