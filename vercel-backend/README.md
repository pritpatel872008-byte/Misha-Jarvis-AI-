# Misha AI API on Vercel

This folder is a standalone Vercel Python Functions backend. The Android app calls `POST /api/ai/chat`.

Request:
```json
{"messages":[{"role":"user","content":"Misha, how are you?"}]}
```

Response:
```json
{"reply":"Main yahin hoon..."}
```

## Required configuration

In the Vercel project's Production Environment Variables, set:
- `GEMINI_API_KEY` — your Google Gemini API key.

Optional:
- `GEMINI_MODEL` — defaults to `gemini-3.8-flash`. If a configured model returns 404, the backend retries once with the default model.

Anthropic/Claude is not used by this backend. The API returns a real configuration/provider error if Gemini is unavailable; it never returns a fake answer.

Never prefix the key with `NEXT_PUBLIC_` or `VITE_`, and never put it in the Android app. Deploy this directory as the Vercel project's Root Directory.
