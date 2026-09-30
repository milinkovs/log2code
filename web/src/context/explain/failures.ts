import type { ExplainFailure } from './useExplainSession';

/** What the tab says for each failure (T43, step 3); texts are part of the task. */
export function failureText(failure: ExplainFailure): string {
  switch (failure.kind) {
    case 'not_configured':
      return 'Explain needs a Gemini API key. Add LOG2CODE_GEMINI_API_KEY to infra/.env.local and restart log2code-api.';
    case 'rate_limited':
      return 'Gemini rate limit reached. Try again in a minute.';
    case 'invalid_key':
      return 'The Gemini API key was rejected. Check infra/.env.local.';
    case 'blocked':
      return 'Gemini blocked this answer.';
    case 'timeout':
      return "Gemini didn't answer in time.";
    case 'network':
      return "Couldn't reach log2code-api.";
    case 'interrupted':
      return 'The connection was interrupted.';
    case 'request':
      return `Could not start the explanation: ${failure.message}`;
    default:
      // `upstream`, and any code a later API version may add.
      return `Gemini returned an error: ${failure.message}`;
  }
}
