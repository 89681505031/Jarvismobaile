# Jarvis WhatsApp Voice Gateway

This module receives an incoming WhatsApp text message, asks the Jarvis brain for a reply, synthesizes that reply with the configured Jarvis voice, and sends the result back as a WhatsApp voice note.

## Flow

```text
WhatsApp text
  -> /api/webhook
  -> Jarvis brain
  -> Fish Audio TTS (OGG/Opus)
  -> WhatsApp media upload
  -> WhatsApp voice reply
```

## Deploy on Vercel

Create a Vercel project whose **Root Directory** is:

```text
whatsapp-gateway
```

Add the variables from `.env.example` in Vercel Project Settings -> Environment Variables.

The production endpoints will be:

- `GET /api/health`
- `GET /api/webhook` for Meta webhook verification
- `POST /api/webhook` for incoming WhatsApp events

## Meta webhook configuration

Use this callback URL:

```text
https://YOUR-DOMAIN.vercel.app/api/webhook
```

Set **Verify token** to exactly the same secret value stored as `WHATSAPP_VERIFY_TOKEN` in Vercel.

Subscribe the WhatsApp Business Account to the `messages` webhook field.

## Required server-side secrets

- `WHATSAPP_VERIFY_TOKEN`
- `WHATSAPP_ACCESS_TOKEN`
- `WHATSAPP_PHONE_NUMBER_ID`
- `FISH_API_KEY`
- `FISH_VOICE_ID`

Do not place these values in the Android APK or commit them to Git.

## Safe sender policy

By default Jarvis answers only numbers listed in `WHATSAPP_ALLOWED_NUMBERS`.

Example:

```text
WHATSAPP_ALLOWED_NUMBERS=48123456789,49123456789
```

Only set `WHATSAPP_ALLOW_ALL=true` when you intentionally want the bot to answer every sender.

## Jarvis brain contract

Configure `JARVIS_BRAIN_URL`. The gateway sends:

```json
{
  "text": "Incoming WhatsApp message",
  "from": "48123456789",
  "channel": "whatsapp",
  "message_id": "wamid..."
}
```

The brain can return:

```json
{ "reply": "Jarvis answer" }
```

The fields `text` or `message`, or a plain-text HTTP response, are also accepted.

For the very first connectivity test, you can temporarily omit `JARVIS_BRAIN_URL` and set `JARVIS_TEST_REPLY` to a fixed phrase.

## Voice

Fish Audio is called with `format: "opus"`. The gateway verifies that the result actually begins with an OGG container header before sending it as a WhatsApp voice note.

Default Fish model:

```text
s2.1-pro-free
```

Change it with `FISH_MODEL` if needed.

## Current milestone

Implemented:

- Meta webhook verification
- incoming text parsing
- sender allow-list
- Jarvis brain call
- Fish Audio Jarvis voice synthesis
- WhatsApp media upload
- WhatsApp voice-note reply
- health endpoint

Next:

- incoming WhatsApp voice message -> speech-to-text -> Jarvis -> voice reply
- durable message-id deduplication to prevent duplicates during retries
- optional webhook signature verification
