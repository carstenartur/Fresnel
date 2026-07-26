/// <reference types="vite/client" />

import type { JSX as ReactJsx } from 'react';

/**
 * React 19 no longer publishes a global JSX namespace. Keep the existing
 * public component signatures source-compatible while they migrate to
 * explicit React.JSX references.
 */
declare global {
  namespace JSX {
    type Element = ReactJsx.Element;
  }
}
