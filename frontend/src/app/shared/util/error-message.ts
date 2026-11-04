function hasMessage(value: unknown): value is {message: string} {
  return typeof value === 'object' && value !== null && 'message' in value
    && typeof value.message === 'string' && value.message !== '';
}

/** Prefers the backend's structured error body (`{message}`) over the transport-level message. */
// oxlint-disable-next-line anti-slop/no-unknown-parameters -- SAFETY: this is the decoder for rxjs error callbacks, which are untyped; it narrows before every read.
export function errorMessage(err: unknown, fallback: string): string {
  const body = typeof err === 'object' && err !== null && 'error' in err ? err.error : undefined;
  if (hasMessage(body)) return body.message;
  if (hasMessage(err)) return err.message;
  return fallback;
}
