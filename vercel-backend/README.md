# Misha AI API on Vercel

This folder is a standalone Vercel Python Functions backend. The Android app can call `POST /api/ai/chat`.

Request:
```json
{"messages":[{"role":"user","content":"Misha, how are you?"}]}
```

Response:
```json
{"reply":"Main yahin hoon..."}
```

## Required configuration

In the Vercel project Environment Variables, add **one** server-side secret:
- `ANTHROPIC_API_KEY` for Claude (preferred), or
- `GEMINI_API_KEY` for Google Gemini.

Optional: `ANTHROPIC_MODEL` or `GEMINI_MODEL`. Never prefix provider keys with `NEXT_PUBLIC_` or `VITE_`, and never put them in the Android app.

Deploy this directory as the Vercel project's Root Directory. The API returns a real configuration error if no provider key exists; it never returns a fake answer.
