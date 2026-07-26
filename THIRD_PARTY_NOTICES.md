# Third-party notices

## Hue UI form vocabulary and stylesheets

The controls, labels and layout vocabulary of the four application screens are
derived from the Hue source code, and the screens are styled by Hue's own
stylesheets and web fonts, vendored under
`src/main/resources/hue-upstream/desktop/static`:

- Repository: https://github.com/cloudera/hue
- Source revision: `ed208faa3162a46455292adb66509b6f56591c6d`
- Relevant source: `apps/beeswax/src/beeswax/templates/execute.mako`,
  `apps/filebrowser/src/filebrowser/templates/listdir_components.mako`,
  `apps/hbase/src/hbase/templates/app.mako`,
  `desktop/core/src/desktop/templates/login.mako` and the stylesheets and fonts
  under `desktop/core/src/desktop/static/desktop`

Copyright 2010-2026 Cloudera, Inc. Licensed under the Apache License, Version
2.0. A copy of the license is available at
https://www.apache.org/licenses/LICENSE-2.0.

The original Hue application, its server APIs, branding assets and trademarks
are not included: the Hue wordmark and logo are replaced by kudos's own.
The form event handlers call kudos's own Spring API.
