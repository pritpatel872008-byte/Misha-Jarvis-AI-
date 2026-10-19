# Misha AI backend — local conversation + live web research

This backend deliberately uses **no hosted AI model or AI-provider API**. Gemini, Anthropic/Claude, OpenAI, and model-fallback chains are not part of the runtime.

## Runtime files
- `api/ai/chat.py` — local small-talk handling plus live public-web search, article retrieval, and extractive summarization.
- `api/ai/misha_emotions.py` — conversation style and emotional-safety guidance used by the local conversation path.

No provider API keys or model environment variables are required.

## Deploy
Set the Vercel project root to `vercel-backend` and deploy. The app sends POST requests to `/api/ai/chat` with a JSON body such as:

```json
{"messages":[{"role":"user","content":"Who is the President of India?"}]}
```

Greetings and simple emotional conversation are handled locally. Other requests use live DuckDuckGo public search results and attempt to read linked pages. The answer is an extractive summary of retrieved text, not an LLM-generated response. If web search is unavailable, the endpoint reports that honestly rather than inventing an answer.

## Limitations
- Web results depend on the search engine and destination websites being reachable.
- Some websites block automated page retrieval; their search snippets may be used instead.
- Android actions (calls, WhatsApp sending, app UI search, camera, flashlight) are separate device-side capabilities and must be verified by the Android app.
- Never report a device action as completed unless the app confirms it.
