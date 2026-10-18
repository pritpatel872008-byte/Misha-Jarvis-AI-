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
- `GEMINI_API_KEY` for Google Gemini (used for educational and technical questions when configured).
- `ANTHROPIC_API_KEY` for Claude as the conversational fallback, or as the primary provider when Gemini is not configured.

Optional: `ANTHROPIC_MODEL` or `GEMINI_MODEL`. The chat handler loads the Misha emotional-conversation guidance from `api/ai/misha_emotions.py`. Never prefix provider keys with `NEXT_PUBLIC_` or `VITE_`, and never put them in the Android app.

Deploy this directory as the Vercel project's Root Directory. The API returns a real configuration error if no provider key exists; it never returns a fake answer.
