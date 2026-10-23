import { startHost } from '../host';

void startHost().catch((error: unknown) => {
  console.error(
    'Vazie Keep host failed to start',
    error instanceof Error ? error.message : 'unknown',
  );
});
